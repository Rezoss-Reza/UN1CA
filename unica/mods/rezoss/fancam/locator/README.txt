UN1CA experimental person-only Locator for SM8550

This directory contains the model-development scripts. The tested DLC is now
wired into customize.sh for dm3q through ../experimental/ as Locator.dlc.
This source integration is not proof of Gallery tracking or export support.
There is no synthetic detector: images are processed by pretrained
YOLOv7-tiny weights. Unsupported Samsung categories are deliberately zero.

Verified on an S23 Ultra on 2026-10-03:
- Real SNPE 2.44 inference with a newly compiled HTP_V73_SM8550_8MB cache.
- All 164 profiled layers executed on DSP; no CPU layers in the profile.
- Samsung's actual YOLOv7 decoder accepted all seven DSP output tensors.
- bus: three persons; zidane: two persons; image1/image2: two persons each.
- horses, image3 and blank: zero persons.
- Quantized/reference person box IoU: 0.9048 to 0.9957 on these samples.
- The seven-input run averaged 4.368 ms total inference time. This is a small
  sample, not a sustained video, thermal, accuracy or power benchmark.

The device test uses SDK libraries staged under /data/local/tmp, not the
ROM's Samsung SNAP service. SNAP/MCA loading, video tracking, Gallery export
and ReID are not proven. The original S26U ReID still targets V81.
Do not treat the candidate as a complete My FanCam port.

Interface:
Input images: RGB float values in [0,1], NHWC [1,320,320,3].
Output: [1,6300,29,1], anchor-major raw YOLO probabilities/regression values.
Strides: 8/16/32. Anchors are pinned in locator_contract.py.
Fields 0..3: raw center/size parameters; field 4: objectness.
Field 7 (class index 2): conditional COCO person probability, NOT multiplied
by objectness. Samsung gates objectness separately, then VideoDetection
filters class scores below 0.95. The original combined-score export was
wrong for this production contract despite passing standalone decoder tests.
Other class scores: zero. Output regression parameters are rescaled to
preserve the checkpoint's trained boxes with Samsung's different anchors.
Consequently output quantization spans [0,1.099030733109], rather than the
original S26U [0,0.999984741211]. Retain the candidate's own encodings.
The DLC retains portable weights as well as its V73 compiled cache.

Native decoder provenance:
libauto-reframing-arm64-v8a.so SHA256
e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835
DecoderYOLOv7 constructor: 0x57860; decode_preds_anchor: 0x5795c.
Anchor table: 0x29128; stride table: 0x29170.
DetectionCategoryV2 name table: 0x79e10; human is class 2.
DetectionBox ABI: 40 bytes; PreprocessInfo ABI: 32 bytes.
probe_device.py refuses a different decoder hash or SoC.
The native probe is for this inspected ABI only, not a general library API.

Model provenance:
Source: https://github.com/WongKinYiu/yolov7
Commit: a207844b1ce82d204ab36d87d496728d3d2348e7
Weights: https://github.com/WongKinYiu/yolov7/releases/download/v0.1/yolov7-tiny.pt
Weights SHA256:
0fa1423606133c06362ec716029dade1169d5631e83195b75d8efa66d2ee0b77
Upstream source/model license: GNU GPL version 3; retain the upstream license
when redistributing the model or a package based on it. Qualcomm SDK files
are not bundled in the archived candidate.

Local environment used:
Python: /tmp/fancam-locator-sm8550-20261003/venv/bin/python
QAIRT: /tmp/doc-dewarp-sm8550-sdk/qairt/2.44.0.260225
NDK: /home/rezoss79/Android/Sdk/ndk/28.2.13676358
Python packages: torch 2.5.1+cpu, torchvision 0.20.1+cpu, numpy 1.26.4,
onnx 1.17.0, onnxruntime 1.20.1, opencv-python-headless 4.11.0.86,
scipy 1.15.3, pandas 2.3.3, pyyaml 6.0.3, requests 2.34.2,
tqdm 4.70.1, matplotlib 3.10.9, seaborn 0.13.2, protobuf 4.25.9.

Build with a fresh artifact directory (commands run from repository root):

task_root=/tmp/fancam-locator-sm8550-20261003
prototype=unica/mods/rezoss/fancam/locator
sdk=/tmp/doc-dewarp-sm8550-sdk/qairt/2.44.0.260225

"$task_root/venv/bin/python" "$prototype/export_locator.py" \
    --upstream "$task_root/yolov7" --weights "$task_root/yolov7-tiny.pt" \
    --output "$task_root/model"

"$task_root/venv/bin/python" "$prototype/verify_reference.py" \
    --upstream "$task_root/yolov7" --weights "$task_root/yolov7-tiny.pt" \
    --model-dir "$task_root/model"

"$task_root/venv/bin/python" "$prototype/build_dlc.py" --sdk "$sdk" \
    --model-dir "$task_root/model" \
    --runtime-libs /tmp/doc-dewarp-sm8550-sdk/deps/usr/lib/x86_64-linux-gnu \
    --python-libs /tmp/doc-dewarp-sm8550-sdk/python/cpython-3.10.22-linux-x86_64-gnu/lib

/home/rezoss79/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++ \
    -std=c++17 -O2 -Wall -Wextra -Werror -static-libstdc++ \
    "$prototype/native_decoder_probe.cpp" -ldl \
    -o "$task_root/native_decoder_probe"

"$task_root/venv/bin/python" "$prototype/probe_device.py" --sdk "$sdk" \
    --model-dir "$task_root/model" --native-probe "$task_root/native_decoder_probe" \
    --image-dir "$task_root/yolov7/inference/images" \
    --runtime-libs /tmp/doc-dewarp-sm8550-sdk/deps/usr/lib/x86_64-linux-gnu

The device script stages only public sample inputs, SDK libraries, the
candidate and the decoder probe under:
/data/local/tmp/unica-locator-person-20261003
It runs without root and makes no package, system-file or SELinux changes.
Device-results JSON, native boxes, the DSP profile and annotated images are
saved beside the generated model. Raw SDK runtime files remain in /tmp.

Saved first candidate:
out/archived/UN1CA-Locator-Person-SM8550-v1/Locator-person-SM8550-v1.dlc
SHA256:
8b7f713ba4ac67ce4dc4cdf9cdd94eab04cd7ccf303a5e5c357acba5457ea444

Next validation: load this candidate through Samsung SNAP/MCA in an isolated
probe, then exercise varied images and video before implementing ReID.
