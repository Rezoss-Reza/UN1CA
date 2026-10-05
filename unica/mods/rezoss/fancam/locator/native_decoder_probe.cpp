// SPDX-License-Identifier: Apache-2.0
// Isolated ABI probe for the hash-pinned Samsung YOLOv7 decoder.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <functional>
#include <vector>

struct DetectionBox {
    float score;
    int category;
    float xyxy[4];
    float normalized_xyxy[4];
};

struct PreprocessInfo {
    float scale[2];
    int padding[2];
    int bounds[4];
};

static_assert(sizeof(DetectionBox) == 40);
static_assert(sizeof(PreprocessInfo) == 32);

int main(int argc, char** argv) {
    if (argc < 3 || argc > 5) {
        std::fprintf(stderr, "Usage: native_decoder_probe LIBRARY OUTPUT_FLOAT32_RAW [CLASS_MASK [SCORE_THRESHOLD]]\n");
        return 2;
    }
    unsigned long class_mask = 1UL << 2;
    if (argc >= 4) {
        char* end = nullptr;
        class_mask = std::strtoul(argv[3], &end, 0);
        if (!argv[3][0] || *end || class_mask == 0 || class_mask > 0xffffff) return 2;
    }
    float threshold = 0.5f;
    if (argc == 5) {
        char* end = nullptr;
        threshold = std::strtof(argv[4], &end);
        if (!argv[4][0] || *end || !std::isfinite(threshold) || threshold < 0 || threshold > 1) return 2;
    }
    std::ifstream file(argv[2], std::ios::binary | std::ios::ate);
    constexpr size_t count = 6300 * 29;
    if (!file || file.tellg() != static_cast<std::streamoff>(count * sizeof(float))) {
        std::fprintf(stderr, "Unexpected raw tensor size\n");
        return 2;
    }
    file.seekg(0);
    std::vector<float> output(count);
    if (!file.read(reinterpret_cast<char*>(output.data()), count * sizeof(float))) return 2;
    for (float value : output) if (!std::isfinite(value)) return 2;
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        std::fprintf(stderr, "dlopen: %s\n", dlerror());
        return 1;
    }
    using Constructor = void (*)(void*, const long*);
    using Destructor = void (*)(void*);
    using Filter = std::function<bool(const DetectionBox&)>;
    using Decoder = std::vector<DetectionBox> (*)(void*, const float*, const long*,
                                                 const PreprocessInfo&, const Filter&);
    auto constructor = reinterpret_cast<Constructor>(dlsym(library, "_ZN2sr7reframe13DecoderYOLOv7C2EPKl"));
    auto destructor = reinterpret_cast<Destructor>(dlsym(library, "_ZN2sr7reframe13DecoderYOLOv7D2Ev"));
    auto decode = reinterpret_cast<Decoder>(dlsym(library, "_ZN2sr7reframe13DecoderYOLOv76decodeEPKfPKlRKNS_5image14PreprocessInfoERKNSt3__18functionIFbRKNS0_12DetectionBoxEEEE"));
    if (!constructor || !destructor || !decode) {
        std::fprintf(stderr, "Decoder symbol lookup failed\n");
        return 1;
    }
    alignas(16) unsigned char instance[0x160] = {};
    const long input_shape[] = {1, 3, 320, 320};
    const long output_shape[] = {1, 6300, 29, 1};
    const PreprocessInfo info = {{1, 1}, {0, 0}, {0, 0, 320, 320}};
    constructor(instance, input_shape);
    Filter filter = [class_mask, threshold](const DetectionBox& box) {
        return box.category >= 0 && box.category < 24 &&
               (class_mask & (1UL << box.category)) && std::isfinite(box.score) && box.score >= threshold;
    };
    auto boxes = decode(instance, output.data(), output_shape, info, filter);
    std::printf("{\"native_decoder\":true,\"count\":%zu,\"boxes\":[", boxes.size());
    for (size_t i = 0; i < boxes.size(); ++i) {
        const auto& box = boxes[i];
        if (i) std::printf(",");
        std::printf("{\"class\":%d,\"score\":%.8f,\"xyxy\":[%.5f,%.5f,%.5f,%.5f]}",
                    box.category, box.score, box.xyxy[0], box.xyxy[1], box.xyxy[2], box.xyxy[3]);
    }
    std::printf("]}\n");
    destructor(instance);
    return 0;
}
