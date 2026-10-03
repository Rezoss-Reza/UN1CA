#!/usr/bin/env python3
"""One synthetic request, with temperature/CPU sampling and an external stop guard."""
import re
import subprocess
import time

ADB = "/mnt/c/platform-tools/adb.exe"
PACKAGE = "io.mesalabs.rezoss.qwen"

def shell(command):
    return subprocess.check_output([ADB, "shell", command], text=True, timeout=10)

def thermal():
    battery = shell("dumpsys battery")
    temperature = re.search(r"^\s*temperature:\s*(\d+)", battery, re.M)
    status = re.search(r"Thermal Status:\s*(\d+)", shell("dumpsys thermalservice"))
    if not temperature or not status:
        raise RuntimeError("thermal telemetry unavailable")
    return int(temperature[1]), int(status[1])

temperature, status = thermal()
if temperature >= 380 or status >= 2:
    raise SystemExit("Refusing test: phone already above operational thermal limit")
print(f"baseline battery_c={temperature / 10:.1f} thermal={status}", flush=True)
test = subprocess.Popen([ADB, "shell", "am", "instrument", "-w",
    "io.mesalabs.rezoss.bridgesafety/io.mesalabs.rezoss.qwentest.BridgeSafetyTest"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
started = time.monotonic()
try:
    while test.poll() is None:
        temperature, status = thermal()
        elapsed = int(time.monotonic() - started)
        print(f"sample elapsed_s={elapsed} battery_c={temperature / 10:.1f} thermal={status}", flush=True)
        cpu = shell("top -b -n 1 -o PID,%CPU,ARGS -m 100")
        for line in cpu.splitlines():
            if ("librezoss_llama.so" in line or PACKAGE in line) and "top -b" not in line:
                # Never print arguments, file paths, or keys from native processes.
                fields = line.split()
                if len(fields) >= 2 and fields[0].isdigit():
                    print(f"engine_process pid={fields[0]} cpu_percent={fields[1]}", flush=True)
        if temperature >= 380 or status >= 2 or elapsed >= 65:
            shell("am force-stop " + PACKAGE)
            print("STOP: thermal or wall-clock guard", flush=True)
            break
        time.sleep(2)
    output, _ = test.communicate(timeout=15)
    print(output)
finally:
    if test.poll() is None:
        shell("am force-stop " + PACKAGE)
        test.terminate()
        test.communicate(timeout=10)
