S26U DLC replacement/recompilation experiments for S23 Ultra / SM8550
2026-10-03

Scope and safety
---------------
These scripts are not referenced by customize.sh. No firmware, ROM install
input, Camera provider, SELinux rule or floating feature was changed.
Device probes run without root under separate /data/local/tmp/unica-dlc-*
directories, with SDK 2.44 libraries staged there. This is not a test of the
ROM's Samsung SNAP/MCA service or Camera's own inference path.

Donor audit
-----------
Source: /home/rezoss79/dev/s26u_oneui9
124 paths, 116 distinct SHA256 hashes, including model aliases.
114 unique DLCs have missing/stripped portable static tensors.
The only retained-weight trained model is:
  01-7_DocumentScan_DocSegment_v1.dlc
The other model with no missing statics, aiisp_dummy_model.dlc, is a one-op
reshape with no learned parameters and is NOT an AI ISP replacement.

All donor files loaded through the SDK reader. Generic ZIP CRC validation
fails on the donor containers; every entry is checked in inventory.json.
The SDK-generated candidates declare CRC=0 for every ZIP entry, so generic
ZIP CRC validation also fails for them. Preserve the SDK serialization;
do not rewrite metadata or describe this as a passing ZIP integrity test.
SDK reader/device inference tests pass and SHA256 hashes are verified.
Original donor files
remain untouched; their hashes and input/output contracts are recorded.

1. HumanPetDetection-SM8550-v1.dlc
---------------------------------
A NEW trained replacement using the public COCO YOLOv7-tiny checkpoint,
not recovered Samsung weights. It extends the earlier person-only Locator
approach with dog/cat/person scores mapped to Samsung class IDs 0/1/2.
Input: RGB float [0,1], NHWC [1,320,320,3].
Output: output [1,6300,29,1]. All remaining category scores are zero.
Trained anchor regression is rescaled for the hash-pinned Samsung decoder;
retain this DLC's own quantization encodings, not donor encodings.
Faces, pet faces, gestures and other Samsung categories are NOT implemented.

Device results:
- All 163 profiled layers ran on DSP. Ten-input sample mean: 5.334 ms.
- All ten tensors passed Samsung's actual native YOLOv7 decoder.
- bus: 3 humans; zidane: 2 humans; image1/image2: 2 humans each.
- image3: 1 dog; cats: 2 cats; horses/sudoku/blank: no target detections.
- Float/DSP box IoU: 0.9043 to 0.9958.
- IMPORTANT quality failure: the public white-dog photo is classified as
  cat (about 0.71 confidence), also by the original pretrained float model.
  This remains a recorded error, not a successful dog detection.
These are small smoke tests, not an accuracy/video/thermal/power benchmark.
Candidate SHA256:
a13cab170f15b759a2b58dc5555e7ea15f56b65c614a5e0b8b4ac83ab6a694ef

Source: https://github.com/WongKinYiu/yolov7
Commit: a207844b1ce82d204ab36d87d496728d3d2348e7
Weights: https://github.com/WongKinYiu/yolov7/releases/download/v0.1/yolov7-tiny.pt
Weights SHA256:
0fa1423606133c06362ec716029dade1169d5631e83195b75d8efa66d2ee0b77
Upstream GPLv3 license is included in the model archive; review applicable
model/dataset and redistribution terms before shipping this in a ROM.

Native decoder SHA256:
e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835
The existing locator/native_decoder_probe.cpp now accepts an OPTIONAL class
bitmask. Default remains human-only (4); 7 enables dog/cat/human. Default
behavior was retested against the original Locator DSP bus tensor.
This proves tensor decoding, not the HumanPetDetection MCA consumer path.

2. DocSegment-SM8550-v1.dlc
--------------------------
The ORIGINAL S26U trained graph and retained parameters, compiled for V73.
186 static tensors, 4,241,811 bytes of portable tensor data, 180 graph ops.
Donor SHA256:
5842aae4b7cc0036b613a5a476358c2cd74eaf34604ffa4b84edd69752abd056
Input: data [1,224,224,3], uFxp16.
Outputs: output_1 [1,224,224,12], output_2 [1,14,14,12],
         output_3 [1,14,14,1], output_4 [1,1,1,2], all uFxp16.
Both original V81 and new HTP_V73_SM8550_8MB cache records are retained.
Candidate SHA256:
387b052e9eff920ff9bc5cd9906af6bcd64f067cb43bcdbef676e261f852875a

Device results:
- Strict cache validation and all 182 profiled layers running on DSP.
- Three-input sample mean: 7.209 ms.
- All four outputs compared against the original donor on host CPU.
- Cosine similarity >=0.99975; relative RMSE <=0.0491 across 12 comparisons.
- Samples: real photographed Sudoku sheet, public bus image and blank.

LIMITATIONS: the numeric test resizes RGB and subtracts 127.5, guided by the
donor input encoding. Native preprocessing/channel order, class meaning and
actual document boundary quality have NOT been verified. Stock S23U's
SS_segmenter_cnn.dlc has the same tensor shapes but uFxp8, while this donor
is uFxp16. Do not silently substitute the donor .info or treat it as drop-in.
This is document SEGMENTATION, NOT DocDewarping_v2 or the document crash fix.
The donor is Samsung firmware, not an open-license replacement model.

Remaining candidates and blockers
---------------------------------
ReID and HumanPetPose:
  Both need output_1_feature [1,128,1,1] AND output_0_pose [1,174,640,1].
  A feature-only OSNet replacement would leave the required pose head missing.
  Need native pose decoding/preprocessing and embedding-distance calibration
  before exporting a genuine combined replacement; no fixture outputs.
KeywordClassification:
  output1 [1,507,1,1] and output2 [1,512,1,1]. Category labels and feature
  semantics must be recovered before replacing the classification model.
DocDewarping_v2:
  data [1,512,512,3], two [1,128,128,2] float outputs. Need to establish warp
  direction, coordinate convention, output role and native postprocessing.
Segmentation/depth/remaster/AI ISP/OCR:
  Separate domain-specific trained replacements may be possible, but matching
  shape alone does not establish semantics. Depth/ISP also depend on camera
  calibration and native processing. No unsupported donor was enabled.

Public sample sources
---------------------
YOLOv7's inference/images at the pinned commit.
https://raw.githubusercontent.com/opencv/opencv/4.10.0/samples/data/sudoku.png
https://raw.githubusercontent.com/pytorch/hub/master/images/dog.jpg
https://huggingface.co/datasets/huggingface/documentation-images/resolve/main/coco_sample.png
Additional sample hashes:
sudoku.png: 3cd9ab173f7178c0a16bba9f867062b58b5d60918d5aeb8ced85cba0b24c353a
dog.jpg: f3f87bb8ab3c26c7ecfd3ac60421d7f32b0503d1d6c5baf8bac42ed93d86351a
cats.png: cf6f3c4befa148732c7453e0de5afab00f682427435fead2d88b07a9615cdac2

Reproduce from repository root
------------------------------
Reuse the isolated Python environment/package versions documented in
../locator/README.txt. SDK libraries are NOT included in the model archive.
Use fresh host/device directory names for each run; scripts refuse to
overwrite an existing candidate or reuse an existing device staging folder.

py=/tmp/fancam-locator-sm8550-20261003/venv/bin/python
export PYTHONDONTWRITEBYTECODE=1
proto=unica/mods/rezoss/fancam/dlc_audit
sdk=/tmp/doc-dewarp-sm8550-sdk/qairt/2.44.0.260225
deps=/tmp/doc-dewarp-sm8550-sdk/deps/usr/lib/x86_64-linux-gnu
pylibs=/tmp/doc-dewarp-sm8550-sdk/python/cpython-3.10.22-linux-x86_64-gnu/lib
upstream=/tmp/fancam-locator-sm8550-20261003/yolov7
samples=/tmp/s26u-dlc-audit-20261003/samples
runroot=/tmp/s26u-dlc-retest

"$py" "$proto/audit_models.py" --donor "$HOME/dev/s26u_oneui9" \
  --stock out/fw/SM-S918B_EUX --output "$runroot/inventory" --sdk "$sdk" \
  --runtime-libs "$deps" --python-libs "$pylibs"

"$py" "$proto/prepare_models.py" --kind humanpet \
  --output "$runroot/humanpet-retest" --samples "$samples" --upstream "$upstream" \
  --weights /tmp/fancam-locator-sm8550-20261003/yolov7-tiny.pt

"$py" "$proto/prepare_models.py" --kind docsegment \
  --output "$runroot/docsegment-retest" --samples "$samples" --upstream "$upstream" \
  --source-dlc "$HOME/dev/s26u_oneui9/vendor_a/vendor/etc/saiv/image_understanding/db/SS_segmenter/01-7_DocumentScan_DocSegment_v1.dlc"

for model in humanpet-retest docsegment-retest; do
  "$py" "$proto/run_candidate.py" --sdk "$sdk" --runtime-libs "$deps" \
    --python-libs "$pylibs" --model-dir "$runroot/$model" --build --device
done

/home/rezoss79/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++ \
  -std=c++17 -O2 -Wall -Wextra -Werror -static-libstdc++ \
  unica/mods/rezoss/fancam/locator/native_decoder_probe.cpp -ldl \
  -o "$runroot/native_decoder_probe"

"$py" "$proto/verify_humanpet.py" --model-dir "$runroot/humanpet-retest" \
  --native-probe "$runroot/native_decoder_probe" --samples "$samples" --upstream "$upstream"

Saved candidates/evidence:
out/archived/UN1CA-S26U-DLC-SM8550-Experiments-v1/
No SDK binaries, public input photos or Samsung consumer libraries are
bundled there. Reports/annotated test outputs and model licenses/provenance
are retained. Next gate is isolated native SNAP/MCA/Camera loading, not
blind replacement in customize.sh.
