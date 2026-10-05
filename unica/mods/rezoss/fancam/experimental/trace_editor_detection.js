// SPDX-License-Identifier: GPL-3.0-or-later
// Read-only hooks for the pinned auto-reframing ABI, public test video only.
const lib = Process.getModuleByName('libauto-reframing-arm64-v8a.so');
let inputs = 0;
let outputs = 0;
let decoded = 0;
let processed = 0;
Interceptor.attach(lib.getExportByName('_ZN2sr5image25preprocess_letterbox_flagEPNS0_14PreprocessInfoEPfPKlPKhS5_i'), {
    onEnter(args) {
        this.capture = inputs < 3;
        this.destination = args[1];
        this.info = args[0];
        this.shape = [args[4].readS64().toNumber(), args[4].add(8).readS64().toNumber()];
    },
    onLeave() {
        if (!this.capture || inputs >= 3) return;
        send({stage: 'preprocess', index: inputs++, source_shape: this.shape,
              scale: [this.info.readFloat(), this.info.add(4).readFloat()],
              padding: [this.info.add(8).readS32(), this.info.add(12).readS32()]},
             this.destination.readByteArray(320 * 320 * 3 * 4));
    }
});
Interceptor.attach(lib.getExportByName('_ZN2sr7reframe13DecoderYOLOv76decodeEPKfPKlRKNS_5image14PreprocessInfoERKNSt3__18functionIFbRKNS0_12DetectionBoxEEEE'), {
    onEnter(args) {
        this.result = this.context.x8;
        if (outputs < 3) send({stage: 'locator_output', index: outputs++}, args[1].readByteArray(6300 * 29 * 4));
    },
    onLeave() {
        if (decoded++ >= 10) return;
        const begin = this.result.readPointer();
        const end = this.result.add(8).readPointer();
        const count = end.sub(begin).toInt32() / 40;
        if (count < 0 || count > 6300) throw new Error('Unexpected decoder vector ABI');
        const boxes = [];
        for (let i = 0; i < count; i++) {
            const box = begin.add(i * 40);
            boxes.push({score: box.readFloat(), category: box.add(4).readS32(),
                xyxy: [8, 12, 16, 20].map(offset => box.add(offset).readFloat())});
        }
        send({stage: 'native_decoder', count, production_score_survivors: boxes.filter(b => b.score >= 0.95).length, boxes});
    }
});
Interceptor.attach(lib.getExportByName('_ZN2sr7reframe14VideoDetection7processERNS0_9FrameDataEl'), {
    onEnter(args) { this.frame = args[1]; },
    onLeave() {
        if (processed++ >= 10) return;
        const begin = this.frame.add(8).readPointer();
        const end = this.frame.add(16).readPointer();
        send({stage: 'production_detection', count: end.sub(begin).toInt32() / 584});
    }
});
send({stage: 'ready', process_id: Process.id});
