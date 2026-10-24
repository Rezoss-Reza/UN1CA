PortROM audio integration for dm3q / One UI 9

Audio author: PortROM By Lindir Music
Telegram: https://t.me/LindirContactTG
Original terms: PortROM-TERMS.txt (preserved from the supplied archive).
The GPL headers in build/controller code do not license the audio assets.

UN1CA Settings > UI Settings > PortROM audio, immediately below Play boot
sound effect. Default OFF. A selection change requires a manual restart.
The switch shows the saved selection; its summary separately reports whether
the mounted directory is active, or a restart is pending. A failed mount never
reports active. No automatic reboot or live mount/unmount is performed.

assets/audio contains exactly 194 changed files, 102 additional tones, and
two power sounds from PortROM V1.0.2. The 72 identical files are inherited
from the original ROM at build time. Each imported file has its ZIP member,
SHA-256 and size recorded in assets/manifest.json. No audio is re-encoded;
Samsung's additional haptic channels and tags remain intact.

The mod runs after paradigm and the earlier boot/S Pen mods. prepare.py copies
the current original directory into new /tmp staging, then merges PortROM.
Only the merged tree is installed at /system/etc/unica/portrom-audio/audio.
The original /system/media/audio tree remains intact in the ROM image.
Init bind-mounts the merged tree at /system/media/audio only on the existing
load_persist_props_action boot event, if persist.sys.unica.portrom_audio=true.
The image is read-only; the mod grants no additional SELinux permissions.
OFF + restart leaves the original directory visible and removes extra tones
from that directory. MediaProvider's picker cache may need time to rescan.
An extra tone selected as the default may become unavailable after disabling;
select an original tone if necessary. Existing audio selections are not rewritten.

Boot up (ExtremeROM Special Edition).ogg is mapped to ui/PowerOn.ogg.
Power Off (ExtremeROM Special Edition).ogg is mapped to ui/PowerOff.ogg.
The earlier bootsound mod must be enabled. Its existing switch still controls
startup playback. Shutdown uses Samsung SoundPlayer's existing system-volume
and silent-shutdown checks. Samsung OMC/enterprise overrides retain precedence.
No framework/system-server patch, new app, root manager or daemon is used.
The new sounds use the existing Calm/Fun/Galaxy/Retro folders; this does not add
new Apple/PortROM theme choices or alter the sound-theme Settings UI.

Build/verification (Ubuntu/WSL, from repository root):
  bash unica/mods/portrom-audio/build.sh
  python3 unica/mods/portrom-audio/tests/run.py
  bash -n unica/mods/portrom-audio/customize.sh

build.sh only compiles the new Settings controller and regenerates its patch
in settings/smali; it never modifies extracted firmware/target smali. Existing
generated patches are backed up before a changed regeneration. Compile-only
stubs are never shipped. The Settings row follows the existing theme and
is hidden when the merged audio/init files were not installed (disabled or
unsupported mod). Supported target: dm3q, SDK 37.

Offline checks are not device proof. After building/flashing a ROM containing
this mod, check OFF baseline, ON + restart, all extra picker entries, startup
and normal/silent shutdown playback, then OFF + restart and original recovery.
For read-only device diagnostics use /mnt/c/platform-tools/adb.exe:
  shell getprop persist.sys.unica.portrom_audio
  shell ls -l /system/media/audio/.portrom-v1.0.2
  shell cat /proc/1/mountinfo
The marker should exist only while PortROM is actually mounted. If selected
ON but absent after restart, inspect init/SELinux logs before claiming success.
