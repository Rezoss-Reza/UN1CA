Confirmed experimental ReID v19 Gallery trial - 2026-10-05
========================================================
Outcome: FAILS sustained middle-person focus. No ROM integration.
Evidence: out/archived/UN1CA-FanCam-Tracking-20261005/gallery-v19-test/

The user explicitly requested a correct v19 repeat. The original device DLC
was backed up to original-Reid.dlc before a temporary bind mount. No original
system model bytes, ROM mod payloads, XML or customize.sh were overwritten.
The candidate in /data/local/tmp was given system_file context; SELinux
policy was not changed. Only Video Editor was force-stopped/restarted.

Candidate SHA-256:
993fe6023f5d8e5787632f2c2f3f4b57af427ce8fe8ec8fe0cb60d0da2d0f2f8
Both PID 1 and the NEW Video Editor PID 30381 mount namespaces saw this
exact candidate hash. editor-model-verification.json preserves this proof.
The original clip was copied to Movies/UN1CA_FanCam_v19_Test.mp4, verified
against the host SHA-256, and registered as media ID 21814, 720x1280,
14954 ms. clip.json identifies this deliberate public test copy.

The user opened this copy, selected the middle dancer, played the complete
preview and reported that focus widened again after a few seconds. Analysis
was captured before exiting/exporting could delete the editor's temp files.
tracks-after-preview.json has 150 frames and an actual process-log selection
marker for ID 2. Selection is now API-log confirmed, unlike the earlier run.

IDs and detection evidence:
  initial left/middle/right IDs: 1/2/0.
  selected ID 2 track ends at 3.4 seconds, 35 postprocessed entries.
  at 3.5 seconds the middle dancer is still detected at
    [306,467,473,987], but its raw track ID is -1.
  middle/right remain detected during the association gap.
  ID 3 appears on the right at 4.6 seconds and on the middle at 4.7 seconds;
    ID 4 later follows the right dancer.
  left ID 1 has a complete postprocessed record, despite some raw-ID gaps.

This repeats the earlier ID fragmentation, despite improved isolated model
numeric accuracy. The 450 serialized appearance vectors each have 49..75
negative components, consistent with fitted v19 output. The older first128
OSNet output is nonnegative. Combined with the editor namespace hash and
fresh reproduction, this rules out merely reading the older model's cached
appearance vectors. Only vector hashes/sign counts are persisted, not the
embeddings or private media titles. Feature metadata does not establish why
the native tracker rejects or reassigns a target.

BOX_INFO still means subject-track boxes, NOT final editor crop rectangles.
The v19 preview failure is user-observed; no new v19 export or native crop
keyframes were captured. Do not call the earlier export a v19 artifact.

Earlier export comparison
-------------------------
User export: out/archived/fan_cam_Shorts_2.mp4.
Before activating v19, the device still had the original experimental ReID
hash and no ReID mount in the editor namespace. The export cannot be treated
as a confirmed v19 test.
compare_export_crop.py matches SIFT features against original_source.mp4
and estimates a uniform scale/translation, searching +/-2 source frames.
All 150 sampled export frames matched, maximum median reprojection error
0.291 pixels. These are measured rendered transforms, not native keyframes.
  0.0 sec: zoom 2.0577, source crop [199.4,382.3,549.3,1004.4].
  1.0 sec: zoom 1.6144.
  2.0 sec: zoom 1.5014.
  3.0 sec: zoom 1.5220.
  3.8 sec onward: effectively full source frame, 1.0000 zoom.
The earlier 3.4-second selected-ID loss and measured 3.8-second full-frame
fallback are temporally consistent, not proof of the native fallback policy.
Evidence: shorts2-crop-comparison/report.json and source-export-comparison.jpg.

Rollback completed
------------------
temporary_reid_test.py restore unmounted only the diagnostic model and
closed the test editor. The original hash was verified in PID 1's namespace:
f9ce6a502fa9ad1b33b5d20bfdd33159c9040613a5f737cc1f18ee8313788c2b
state.json now says restored-original; no editor process remains loaded with
the temporary model. The deliberate test clip remains in Movies for future
reproduction. Probe files under /data/local/tmp are inactive.

Next diagnostic target
----------------------
Investigate production association around 3.0..4.8 seconds: native pose/face
activation, clean-feature gates, matching costs, memory update and target
expiry/reacquisition. Do not shrink Locator boxes or claim more quantization
alone fixes this behavior. Require continuity of the explicitly selected
track, then final crop/keyframe, preview and export proof before ROM rollout.

Verification:
  <ML-venv-python> unica/mods/rezoss/fancam/reid/test_export_crop.py
  python3 unica/mods/rezoss/fancam/experimental/test_tracking_records.py
  python3 -m py_compile unica/mods/rezoss/fancam/experimental/temporary_reid_test.py
  git diff --check
