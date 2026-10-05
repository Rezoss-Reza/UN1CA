S23U Horizontal Lock adaptation - experimental native work
=========================================================
Status: experimental ROM adapter now enabled for dm3q in rezoss/customize.sh.
See rom/README.txt for payload, limitations, verification and rollback.
The connected phone still has stock vendor binaries; ROM build/flash is pending.
Earlier sections below document the diagnostic history, not current packaging.

Confirmed baseline
------------------
S23U source: S918BXXUAZZI8; donor S26U: S948BXXU4ZZHK.
Mode 2 reaches S23U EISv3 but stock horizon initialization is disabled.
Previous temporary enable=1/max=180 test returned success twice; saved video
still tilted, according to the user. Accelerometer was registered, gravity
was disabled. Registration does not prove frame samples were delivered.
Selecting ordinary Super Steady tuning in a previous combined experiment
coincided with a camera-provider crash; the exact cause was not established.

Donor behavior to adapt
----------------------
S26U enables horizon in mode 2 and defaults maximum roll to 180 degrees.
It chooses separate Horizontal Lock chromatix selectors (type5=29/type6=71),
clears gravity-disable state, reads samsung.android.uniplugin.horizontalRotation
at recording start, converts discrete orientation codes to algorithm references,
and retains that reference during recording. Quaternion-derived roll affects
its horizon state; output transforms are published through ICA metadata.
Donor selectors and struct offsets are not compatible S23U tuning values.
S26U eis_initialize/eis_process and S23U eis3_* are different binary interfaces.
A direct donor node/algorithm replacement is not an established port strategy.

Implementation direction
------------------------
Retain SM8550 camera provider, sensor calibration, existing distortion correction
and ICA output route. First locate the missing per-frame behavior. Then either
patch the existing S23U horizon processing, or implement an SM8550-compatible
native adapter that fixes a recording reference, estimates roll from synchronized
sensor data, and composes a bounded counter-rotation into the consumed transform.
Before integration, recover exact coordinate conventions, matrix/grid composition,
recording-state input, crop margins, and CFI signatures. An uncalled additional
library or an enable/angle-only patch cannot establish the desired behavior.
Full 180-degree correction and usable crop quality remain unproven on S23U.

Implemented probe
-----------------
experimental/frame_trace.c temporarily instruments native call and return sites:
  EIS initialization:     ELF VA 0x4c580 / 0x4c584
  distortion processing: ELF VA 0x44c94 / 0x44c98
  stabilization:         ELF VA 0x44e50 / 0x44e54
Checks six original instructions before installing software breakpoints.
Captures mode, init fields, actual gyro/accel/gravity counts and first/last samples
with microsecond timestamps, call results, output validity and 176 raw output bytes. The current version also reads the first stabilization
matrix through its output pointer, plus the matrix domain and row count.
Sensor records are read as six doubles and timestamp (stride56); axis conventions
and the raw output prefix are not yet interpreted as a correction angle.
This covers ExecuteAlgo's split path, not every ProcessRequest drain/fallback path,
not the final published ICA metadata, and not the downstream encoder warp.

Default: no parameter changes. Optional --enable-180 repeats the previously
accepted mode-2 enable/180 initialization only; preserves existing tuning.
This optional experiment is NOT a complete S26U behavior adaptation.
No gravity registration or other pipeline state is artificially fabricated.

Risk and cleanup
----------------
Camera-provider ptrace/software breakpoints can affect timing, drop frames or
crash Camera. Do not use resulting clips as stabilization performance benchmarks.
Runner checks exact live node/algorithm hashes and refuses an already traced
provider. Native timer is bounded to 180 seconds, restores all six instructions
and detaches threads on normal expiry/SIGTERM/SIGINT. Do not SIGKILL the tracer.
Cleanup cannot be guaranteed after tracer/provider fatal failure; inspect the
provider and restart it if restoration is unverified before another experiment.
With --enable-180 the runner force-closes/reopens Camera after VERIFIED detach,
because removing hooks does not clear modified active algorithm initialization.
Wait for completion or save recordings promptly before the timer expires.

Build and validation
--------------------
Run from repository root; all binaries and logs go to fresh /tmp directories:
  unica/mods/rezoss/horizontal-lock/experimental/build.sh /tmp/hl-build-NEW
  python3 unica/mods/rezoss/horizontal-lock/experimental/test_probe.py \
    --build-dir /tmp/hl-build-NEW --output-dir /tmp/hl-mock-NEW

Tests run synthetic native processes on the connected rooted phone. They verify
mode0/1/2 read-only scope, optional override scope, first/last sensor values and
timestamps, both processing outputs, instruction restoration and timer detach.
These tests do not prove sensor calibration, live camera output or video leveling.

Camera trace (rooted exact S23U firmware; coordinate a user recording first):
  python3 unica/mods/rezoss/horizontal-lock/experimental/run_probe.py \
    --binary /tmp/hl-build-NEW/frame_trace --output /tmp/hl-live-NEW.jsonl \
    --enable-180 --seconds 180

Record Super Steady followed by Horizontal Lock with a gentle sideways tilt.
The resulting capture determines whether samples/correction are absent or output
is disabled; final output-consumer tracing may still be needed before a patch.

Custom library prototype
------------------------
native/horizon_core.c builds libunica_horizon_core.so for Android arm64/API29.
Its private C API accepts an ALREADY CALIBRATED image-domain roll and explicit
recording reference. Only mode2 starts correction; the reference stays latched
until stop. It wraps/clamps angle to a configurable limit up to pi, composes
centered inverse rotation with an existing 3x3 output-to-input matrix, computes
rotation crop requirements, and refuses an insufficient available zoom budget.
Existing EIS rotation is estimated using the center projective Jacobian and
subtracted before composition. Invalid/nonfinite/singular/reflected inputs are
rejected. This local estimate still requires validation across the full warp.

This is candidate geometry and recording-reference logic, not recovered Samsung
source or the donor eis_* ABI. It has no sensor fusion/filter, gravity registration,
vendor-tag reader, CFI wrapper, CHI loader integration, metadata publisher, grid
composer, or final source-crop validation. It is not loaded by Camera.
The node adapter must calibrate axes, provide the correct recording reference,
verify the existing EIS rotation estimate, and validate the final warp/crop.
Axis/reference errors or spatially varying perspective rotation may overcorrect. Do not inject this core blindly.
No stabilization performance or full 180-degree camera support is claimed.

Build/test:
  unica/mods/rezoss/horizontal-lock/native/build.sh /tmp/hl-core-NEW
  cc -std=c11 -O2 -Wall -Wextra -Werror \
    unica/mods/rezoss/horizontal-lock/native/horizon_core.c \
    unica/mods/rezoss/horizontal-lock/native/test_core.c -lm \
    -o /tmp/hl-core-NEW/test_core_host
  /tmp/hl-core-NEW/test_core_host

Validated 2026-10-04: host, ASan/UBSan host, and actual Android shared-library
loading/test execution. Tests verify recording-reference retention, inverse-warp
sign and composition order, existing EIS rotation accounting, full-turn corner geometry, crop refusal, angle wrap,
invalid-input rejection, and modes0/1 bypass. Android tests run outside Camera.

Live capture 2026-10-04
-----------------------
Evidence: /tmp/s23-horizon-frame/device-frames-180.jsonl and live-summary.json.
Provider PID3109; mode2 init enable1/max180, stock mode2 tuning retained.
Mode1:254 captured stabilization returns0;214 valid matrix outputs.
Mode2:268 captured stabilization returns0;238 valid matrix outputs.
Startup/no-output frames are included in these counts and are not proof of failure.
Mode2 gyro counts28..30 and accel counts31..33; gravity counts0 throughout.
Both modes' stabilization grid-enable remained0; perspective matrices were enabled.
Mode1 matrix domain3456x2592; mode2 domain2400x1352, recovered from output fields
and FillPerspectiveAndGridTransform disassembly. Matrix coefficients were not
captured by the first probe version, so actual horizon correction remains unknown.
Distortion output has a different layout: its raw offset0xa4 from the first version
must NOT be interpreted as an alignment-enable flag. Only stabilization flag
interpretations are used in live-summary.json.
Native cleanup restored all instructions and detached96threads with0failures.
Camera was reopened to clear modified algorithm initialization. Provider retained
PID3109; live vendor hashes remained unchanged; main-thread TracerPid0 verified.

The improved probe additionally captures the first stabilization matrix and its
domain/row fields; mock tests verify these reads. The next calibration capture
must relate measured tilt to that matrix before connecting the candidate core.

Matrix calibration capture 2026-10-04
------------------------------------
Evidence: /tmp/s23-horizon-frame/device-matrix-calibration.jsonl and
matrix-calibration-summary.json. User later clarified this recording was made in PORTRAIT. It must not be
used to infer the landscape recording-reference or axis mapping.
537 stabilization returns were captured;30 had no output during startup,
507 had valid enabled perspective matrices. All processing returns were0.
For those507 matrices, polar rotation of the projective Jacobian at the declared
domain center ranged from-7.84717 to+7.89569 degrees. Domain2400x1352.
This is measured local transform rotation, NOT proof of a hard8degree algorithm
limit, final encoded-video correction, or exact physical tilt calibration.
Mode2 init enable1/max180 again succeeded; gravity remained disabled.
The trace therefore shows enable/180 alone does not produce full-range matrix
rotation in this experiment. Next integration must recover synchronized frame IDs,
calibrated image axes/reference, actual crop budget, and the consumed warp mapping.
Cleanup restored all instructions, detached96threads with0failures, reopened
Camera to clear modified initialization, and verified96providerthreads untraced after Camera reopened.

Corrected landscape calibration 2026-10-04
------------------------------------------
User repeated the recording starting in landscape and reported done.
Evidence: /tmp/s23-horizon-frame/device-matrix-landscape.jsonl and
landscape-summary.json. Initial accel vector approximately[-10.16,-0.32,0.96]
versus portrait approximately[0.21,9.33,2.75], confirming distinct device pose.
419 stabilization calls returned0;389 had enabled, valid perspective matrices;
30 had no output during startup. Matrix domain stayed2400x1352.
Local center-Jacobian rotation ranged-7.83942..+8.22275degrees despite mode2
horizon enable1/max180. This remains a local transform measurement, not proof
of an exact hard8degree cap or final consumed-video behavior.
Both gyro and accel samples were delivered; gravity samples absent.
Cleanup restored all six instructions and detached96threads with0failures.
Camera reopened after verified cleanup to clear overridden initialization.

Current deliverable is an uninstalled custom geometry/reference library plus
reproducible probe. Remaining camera adapter work includes exact input/output
frame-ID synchronization, orientation reference/mount conversion, full-warp crop
validation, and verified CHI/ICA integration. No persistent camera patch applied.

Frame-to-CHI experimental adapter (2026-10-04)
--------------------------------------------
New source: native/horizon_stream.{c,h}, native/test_stream.c,
experimental/adapter_trace.c, build_adapter.sh, run_adapter.py,
adapter_pipeline_mock.c and test_adapter_pipeline.py.
The custom library runs in a temporary native tracer, not inside the provider.
The tracer reads EIS accelerometer batches, saves orientation by input frame ID,
and matches the delayed output frame to the CHI perspective payload request.
The USB-right landscape recording measured 465 valid EIS outputs, each delayed
30 frames. Its initial accelerometer direction was positive X. This differs
from the earlier unspecified landscape pose and defines this experiment's axes.
Exact S23U algorithm code requires CENTERED perspective coordinates; the stream
adapter conjugates the library's pixel-coordinate transform accordingly.
CHI metadata count is at +0x14, not +0x10. The first earlier publication trace
used the wrong offset; it did not establish actual request/payload matching.

Guards require mode 2, sensor 0, one centered perspective matrix, no grid,
2400x1352 domain, exact output/request frame match and byte-identical stock
matrix before writing. Correction is limited to 45 degrees and a maximum
2x additional geometric zoom; all composed corners must remain inside the
assumed matrix domain. This domain check does not establish the physical sensor
crop budget or downstream ICA behavior. Missing matches bypass correction.
Orientation currently uses low-pass accelerometer batches, without gyro fusion
or precise exposure-center interpolation. Reference resets at EIS initialization,
not an independently traced recording-start event. Only USB-right landscape
starting near level is calibrated; full 180-degree/S26U parity is unproven.

Android synthetic integration: four cases passed (read-only mode 2, write-enabled
modes 0/1/2). With a simulated 30-frame delay, all 40 mode-2 publication matrices
were matched and corrected when enabled. Modes 0 and 1 received no writes.
Each case verified instruction restoration and detach. Android stream tests
passed delayed-frame, sensor, landscape, centered-coordinate, crop and stale
sample checks. These synthetic tests do not prove saved-camera-video leveling.

Rebuild and verify in fresh temporary directories:
  bash unica/mods/rezoss/horizontal-lock/experimental/build_adapter.sh /tmp/hl-new-build
  python3 unica/mods/rezoss/horizontal-lock/experimental/test_adapter_pipeline.py \
    --build-dir /tmp/hl-new-build --output-dir /tmp/hl-new-tests
Live runner defaults to read-only; --apply writes temporary per-request ICA
matrices and reopens Camera after verified cleanup. It verifies exact firmware
hashes and refuses an already-traced provider. Provider instrumentation may
cause camera stalls or crashes; vendor binaries and tuning are not replaced.
No persistent camera modification or production adapter has been installed.

Live adapter validation status
------------------------------
The first read-only adapter recording attempt crashed provider PID3109 with
SIGTRAP/TRAP_BRKPT at node+0x4c580 (initialization hook). No EIS initialization
or correction-write events were captured. Automatic instruction restoration
was not reported because that provider and tracer exited. Replacement provider
PID10915 was verified untraced; both vendor library hashes remain unchanged.
Evidence: /tmp/hl-adapter-work/provider-crash-tombstone.txt and
live-adapter-dryrun.jsonl. Root cause is not conclusively established.
A potential arming race was corrected: all enumerated threads now remain stopped
until complete attachment and breakpoint installation, with re-enumeration for
threads created during attachment. Synthetic tests now create an additional
worker after arming and execute initialization on it; all four cases pass.
The revised tracer completed a 30-second live Camera reopen check, restoring
all instructions and detaching32threads with0failures, provider PID unchanged.
That check did not exercise EIS initialization (photo mode). Live Horizontal
Lock frame/publication matching and corrected saved video remain unverified.

Revised read-only Horizontal Lock capture completed without reported crash.
Evidence: /tmp/hl-adapter-work/live-adapter-dryrun-v4.jsonl. All433 captured
adapter-publication events matched output frame ID and exact stock matrix to
CHI request/payload. Initial crop logic accepted111 and refused322 candidates.
This establishes publication routing, not downstream encoded-frame behavior.
Cleanup restored all8instructions and detached96threads with0failures.
The crop calculation now searches additional output-coordinate shrink within
the 2x total geometric budget, preserving stock translation and perspective.
Host ASan/UBSan and Android tests verify the composed corners and refusal when
the transform center itself is outside the domain. Synthetic mode guards and
30-frame publication tests passed with this crop implementation.
An experimental live write capture is pending; permanent provider integration,
actual physical crop calibration, gyro fusion, recording-start reference, and
S26U-equivalent full-angle video behavior are still unproven.

Live temporary write capture completed: {"matched_writes": 465, "correction_degrees": [-12.3392643, 10.488127], "zoom": [1.00376447, 1.40003661], "untraced_threads_after_camera_restart": 50, "saved_video_result": "awaiting user playback"}
Evidence: /tmp/hl-adapter-work/live-adapter-apply-v6.jsonl. All8 hook instructions
restored;96threads detached without failure. Camera reopened after cleanup.
Both vendor library hashes still match the original firmware. Playback result
and downstream transform consumption remain unverified.

Playback feedback: improved, but some tilt remained. Complete horizon lock is
NOT achieved. The temporary adapter is now removed from the live process.
Approximate per-frame diagnostic using the average of the logged first/last
accelerometer samples, minus the stock matrix polar rotation and added
correction, gives residual -1.184..+0.963 degrees across465 matched frames.
This is not a measurement of encoded-video roll: only sample endpoints are
logged, accelerometer motion bias remains, and projective local rotations do
not prove the combined downstream warp. Current orientation low-pass time
constant is120ms, so motion lag is a plausible contributor, not a proven cause.
Further work must distinguish transient lag from held-pose residual and trace
composition of perspective, alignment and grid in the ICA consumer. Do not
blindly increase correction gain or claim full S26U behavior from this test.

Playback clarification: the user describes rapid shaking between level and
slightly tilted, plus zoom-in/zoom-out, rather than sustained horizon tilt.
The adaptive additional crop changed1.00376447..1.40003661, with a maximum
adjacent-frame step0.03356803. All465 modified requests105..569 were contiguous.
The remaining30 publications570..599 occur at recording drain and are not
instrumented by the ExecuteAlgo hooks; end-of-clip changes remain possible.
Do not claim the accelerometer residual explains the visible shaking.

Fixed-crop experiment: hl_stream_centered_fixed holds additional crop at2x,
retaining stock EIS translation/perspective. Fixed-scale and full-footprint
checks passed host ASan/UBSan and Android tests. Synthetic delayed-publication
mode0/1/2 tests passed including worker creation and verified hook cleanup.
Only the custom crop schedule changed; orientation filtering and ICA alignment/
grid publication are unchanged. This isolates pumping from rotational shake.
Physical source crop and recording-drain composition remain unverified.

Fixed-crop live test FAILED before any correction write: provider SIGTRAP at
initialization hook+0x4c580. Only armed event captured, no EIS init or matrices.
The all-thread-stop arming change did NOT eliminate the intermittent failure;
its earlier successful run is insufficient evidence of a lifecycle fix.
Evidence: /tmp/hl-adapter-work/fixed-crop-provider-tombstone.txt and
live-adapter-fixed-v2.jsonl. Replacement provider PID25827 is untraced, hashes
unchanged, Camera reopened. Old provider process memory no longer exists.
Fixed-crop behavior has host/Android synthetic verification only; it was not
applied to live video in this attempt. Further live hooks require lifecycle/
signal diagnostics and reliable multithreaded regression coverage first.
