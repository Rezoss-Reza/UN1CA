/* SPDX-License-Identifier: GPL-3.0-or-later */
// Isolated probe; the caller must verify the pinned auto-reframing library hash.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <string>
#include <vector>

struct PreprocessInfo {
    float scale[2];
    int padding[2];
    int bounds[4];
};
static_assert(sizeof(PreprocessInfo) == 32);

int main(int argc, char** argv) {
    if (argc != 7 && argc != 11) {
        std::fprintf(stderr, "usage: preprocess_probe library rgb.raw width height stride output.raw [x1 y1 x2 y2]\n");
        return 2;
    }
    const int width = std::atoi(argv[3]), height = std::atoi(argv[4]);
    const int stride = std::atoi(argv[5]);
    if (width < 1 || height < 1 || stride < width || stride > 8192 || height > 8192) return 2;
    std::vector<unsigned char> padded(static_cast<size_t>(stride) * height * 3);
    auto file = std::fopen(argv[2], "rb");
    if (!file) return 2;
    const auto size = std::fread(padded.data(), 1, padded.size(), file);
    const bool exact = size == padded.size() && std::fgetc(file) == EOF;
    std::fclose(file);
    if (!exact) return 2;
    // Samsung's raw preprocessing entrypoint has no row-stride argument.
    std::vector<unsigned char> packed(static_cast<size_t>(width) * height * 3);
    for (int row = 0; row < height; ++row)
        std::memcpy(packed.data() + static_cast<size_t>(row) * width * 3,
                    padded.data() + static_cast<size_t>(row) * stride * 3, width * 3);
    auto library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    using Preprocess = int (*)(PreprocessInfo*, float*, const long*, const unsigned char*, const long*, int);
    auto preprocess = reinterpret_cast<Preprocess>(dlsym(library,
        "_ZN2sr5image25preprocess_letterbox_flagEPNS0_14PreprocessInfoEPfPKlPKhS5_i"));
    if (!preprocess) return 2;
    const long target[] = {320, 320, 3}, source[] = {height, width, 3};
    std::vector<float> tensor(320 * 320 * 3);
    PreprocessInfo info = {};
    int status;
    if (argc == 11) {
        using Crop = int (*)(PreprocessInfo*, float*, const long*, const unsigned char*, const long*, const float*, int);
        auto crop = reinterpret_cast<Crop>(dlsym(library,
            "_ZN2sr5image30preprocess_crop_letterbox_flagEPNS0_14PreprocessInfoEPfPKlPKhS5_PKfi"));
        if (!crop) return 2;
        float box[4];
        for (int i = 0; i < 4; ++i) box[i] = std::strtof(argv[7 + i], nullptr);
        if (!(box[2] > box[0] && box[3] > box[1])) return 2;
        // PoseEstimator expands the box by source width/height /64 before
        // calling this function; the crop entrypoint applies its own margin.
        box[0] -= width / 64.f; box[2] += width / 64.f;
        box[1] -= height / 64.f; box[3] += height / 64.f;
        status = crop(&info, tensor.data(), target, packed.data(), source, box, 1);
    } else {
        status = preprocess(&info, tensor.data(), target, packed.data(), source, 1);
    }
    file = std::fopen(argv[6], "wb");
    if (!file) return 2;
    const bool written = std::fwrite(tensor.data(), sizeof(float), tensor.size(), file) == tensor.size();
    std::fclose(file);
    if (!written) return 2;
    file = std::fopen((std::string(argv[6]) + ".geometry").c_str(), "wb");
    if (!file) return 2;
    const bool geometry_written = std::fwrite(&info, sizeof(info), 1, file) == 1;
    std::fclose(file);
    if (!geometry_written) return 2;
    std::printf("{\"status\":%d,\"scale\":[%.9g,%.9g],\"padding\":[%d,%d],"
                "\"bounds\":[%d,%d,%d,%d],\"stride_repacked\":%s}\n",
                status, info.scale[0], info.scale[1], info.padding[0], info.padding[1],
                info.bounds[0], info.bounds[1], info.bounds[2], info.bounds[3],
                stride == width ? "false" : "true");
    return 0;
}
