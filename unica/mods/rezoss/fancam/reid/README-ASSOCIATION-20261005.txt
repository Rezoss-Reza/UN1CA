My FanCam native association replay - 2026-10-05
================================================
Evidence: out/archived/UN1CA-FanCam-Tracking-20261005/native-association-replay/
No ROM payload, customize.sh, system model bytes or vendor runtime changed.

Exact public v19 Gallery records were recovered for all 150 analyzed frames.
capture_public_replay.py verifies every timestamp, box, track ID and vector
hash against gallery-v19-test/tracks-after-preview.json before saving blobs.
This is not a replay of approximate half-second host samples.

live_tracker_config.cpp reads only Video Editor structural configuration;
no debugger attach, process suspension, memory write or heap dump is used.
The caller pins the library SHA-256; the probe also verifies vtable methods.
The bounded watch captured live objects at 0.2 and 9.6 seconds during fresh
analysis of UN1CA_FanCam_Config_Probe_20261005, using installed ReID v2.
Both captures have identical parameters and tracker dimensions 768x1280.
Valid source width remains 720; this capture alone does not justify changing
Samsung's padded-frame geometry.

Gallery parameters differ from VideoTrack constructor defaults at tracker
offsets 0x30, 0x58, 0x60, 0x68 and 0x84. Using the captured parameters, the
native replay matches ALL 150 Gallery ID arrays exactly (zero mismatches).
Thus the parameter provenance differs from the earlier v19 session, but
the complete observed v19 association failure is reproduced exactly.

Demonstrated mismatch
---------------------
Target::operator& uses clamped 1 - dot(feature_memory, current_feature),
not its separately exported, norm-aware calculate_cosine_distance helper.
The real saved v19 vectors have L2 norms 31.63..47.35, not approximately 1.
Dot products saturate competing eligible matches to zero cost. At 2.7 sec
the first middle-dancer rejection has native dynamic ambiguity flag 0x400.
At 3.5 sec the middle body pose passes the box-aware gate, activation is
0.80952382, its previous timestamp is 3.4 sec and memory has seven entries.
The middle and right matches both have zero costs and are dynamically
rejected with 0x400. Memory retains the same timestamps rather than updating.
The ROI interval expires by 3.7 sec; it is not the initial rejection cause.

Changing ONLY the input vectors to unit length in the isolated replay keeps
the middle at ID 2 for all 19 analyzed frames from 3.0 through 4.8 seconds.
At 3.5 sec its own cost becomes 0.01559103, competing right cost 0.18512103;
the own dynamic gate passes. Left/right spatial-rank proxies retain IDs
1/0 in 150/150 frames. Middle retains ID 2 in 149/150; 13.1 sec returns -7,
with native gate flag 0x80. Spatial rank is not annotated identity truth.
This remaining single-frame exception and Gallery continuity need testing.

Diagnostics and limitations
---------------------------
native_tracker_replay.cpp records box-aware pose gates, activation, pair
costs, dynamic gate flags, ROI expiry and actual memory timestamps. Its
extra clean-feature query uses all frame boxes, no occlusion boxes, and
runs AFTER update; it is not an interception of every admission decision.
Integer Gallery serialization rounds pose coordinates and scores.
Normal native tracker destruction completes on all full replay runs.
The first attempt incorrectly interpreted add_target's zero success code;
the return ABI and FrameTrackData's second ID vector were corrected before
any replay acceptance claim. Failed artifacts are retained separately.

Narrow model repair
-------------------
normalize_appearance_contract.py adds an explicit L2 normalization output
branch, using MatMul rather than the previously problematic ReduceSum.
All 16 real host inputs retain bit-identical pose outputs and unit-length
appearance; output names/shapes and preprocessing remain unchanged.
v21b rebuilt with SNPE 2.10, SM8550/V73 cache, mixed Div8 and 16-bit
activations. Stock SNAP reference gates and native decoding/body checks
pass all 16 regression inputs. DSP feature norms are 0.99933..1.00089.
The 91-crop follow-up retains the prior left-dancer pose outlier at frame
390 (RMSE 0.399673); all 85 held-out appearance directions pass >=0.98.
The 16-input longevity bundle completes 120 executions per input, 1,920
total, with explicit SNAP dispose/destructor. The probe then uses _Exit;
normal global process shutdown and Gallery longevity are not established.
Candidate SHA-256:
64de5d1f753c143aa02cf97799b739097f64c152b41ba3a3c3c06d9b7522fb06
gallery-v21-test/state.json is prepared-not-mounted, with both host/device
original DLC backups. The temporary helper recognizes v21 explicitly;
v19 remains its default. Activation needs explicit experimental approval.
Gallery/crop/export continuity remains unverified. No candidate is mounted.

Reproduce:
  <ML-python> normalize_appearance_contract.py --source <v19c> --output <fresh-source>
  python3 run_tracker_replay.py --records <public-records> --probe <native-probe> \
      --output <fresh-output> --live-config <live-config-watch.jsonl>
  Repeat with --unit-features for the normalization-only counterfactual.
  python3 -m py_compile unica/mods/rezoss/fancam/reid/*.py
  git diff --check
