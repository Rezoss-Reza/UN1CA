/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <dlfcn.h>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

// Private MCA ABI: only use with the independently hash-checked Samsung build.
template <typename T> T symbol(void *library, const char *name) {
    auto address = dlsym(library, name);
    if (!address) { std::fprintf(stderr, "%s: %s\n", name, dlerror()); std::exit(2); }
    return reinterpret_cast<T>(address);
}

int main(int argc, char **argv) {
    if (argc != 6) {
        std::fprintf(stderr, "usage: probe MCA-library DLC input.raw output-directory frames\n");
        return 2;
    }
    setvbuf(stdout, nullptr, _IONBF, 0);
    auto library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { std::fprintf(stderr, "dlopen: %s\n", dlerror()); return 2; }
    auto ctor = symbol<void (*)(void *, const std::string &)>(library,
        "_ZN18ModelDelegatorSNAPC1ERKNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE");
    auto dtor = symbol<void (*)(void *)>(library, "_ZN18ModelDelegatorSNAPD1Ev");
    auto init = symbol<int (*)(void *, int, int)>(library, "_ZN18ModelDelegatorSNAP4initE8CoreType11ExeDataType");
    auto dispose = symbol<void (*)(void *)>(library, "_ZN18ModelDelegatorSNAP7disposeEv");
    auto infer = symbol<int (*)(void *)>(library, "_ZN18ModelDelegatorSNAP9inferenceEv");
    auto inshape = symbol<int (*)(void *, long *, int)>(library, "_ZN18ModelDelegatorSNAP17getShapeInputNCHWEPli");
    auto outshape = symbol<int (*)(void *, long *, int)>(library, "_ZN18ModelDelegatorSNAP14getShapeOutputEPli");
    auto inbuf = symbol<float *(*)(void *, int)>(library, "_ZN18ModelDelegatorSNAP11getBufInputEi");
    auto outbuf = symbol<float *(*)(void *, int)>(library, "_ZN18ModelDelegatorSNAP12getBufOutputEi");
    auto set_inputs = symbol<void (*)(void *, std::vector<std::string>)>(library,
        "_ZN18ModelDelegatorSNAP13setInputNamesENSt3__16vectorINS0_12basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEENS5_IS7_EEEE");
    auto set_outputs = symbol<void (*)(void *, std::vector<std::string>)>(library,
        "_ZN18ModelDelegatorSNAP14setOutputNamesENSt3__16vectorINS0_12basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEENS5_IS7_EEEE");
    const std::string path(argv[2]);
    const bool reid = path.find("reid") != std::string::npos || path.find("Reid") != std::string::npos;
    const int frames = std::atoi(argv[5]);
    if (frames < 1 || frames > 120) return 2;
    std::vector<float> input(320 * 320 * 3);
    auto file = std::fopen(argv[3], "rb");
    if (!file || std::fread(input.data(), sizeof(float), input.size(), file) != input.size()) return 2;
    std::fclose(file);
    auto object = std::calloc(1, 0x350);
    if (!object) return 2;
    ctor(object, std::string(argv[2]));
    set_inputs(object, {"images"});
    set_outputs(object, reid ? std::vector<std::string>{"output_0_pose", "output_1_feature"}
                             : std::vector<std::string>{"output"});
    int status = init(object, 0, 0);
    std::printf("init=%d\n", status);
    if (status) { dtor(object); std::free(object); return 1; }
    long dims[4] = {};
    inshape(object, dims, 0);
    std::printf("input NCHW=[%ld,%ld,%ld,%ld]\n", dims[0], dims[1], dims[2], dims[3]);
    const long expected_input[] = {1, 3, 320, 320};
    if (std::memcmp(dims, expected_input, sizeof(dims))) return 1;
    auto buffer = inbuf(object, 0);
    if (!buffer) return 1;
    std::memcpy(buffer, input.data(), input.size() * sizeof(float));
    for (int frame = 0; frame < frames; ++frame) {
        if (infer(object)) { std::fprintf(stderr, "inference failed frame=%d\n", frame); return 1; }
    }
    for (int index = 0; index < (reid ? 2 : 1); ++index) {
        outshape(object, dims, index);
        std::printf("output %d=[%ld,%ld,%ld,%ld]\n", index, dims[0], dims[1], dims[2], dims[3]);
        const long locator[] = {1, 6300, 29, 1}, pose[] = {1, 174, 640, 1}, feature[] = {1, 128, 1, 1};
        const char *name = nullptr;
        if (!reid && !std::memcmp(dims, locator, sizeof(dims))) name = "output";
        if (reid && !std::memcmp(dims, pose, sizeof(dims))) name = "output_0_pose";
        if (reid && !std::memcmp(dims, feature, sizeof(dims))) name = "output_1_feature";
        if (!name) return 1;
        auto output = outbuf(object, index);
        if (!output) return 1;
        size_t count = 1;
        for (auto dimension : dims) count *= dimension;
        file = std::fopen((std::string(argv[4]) + "/" + name + ".raw").c_str(), "wb");
        if (!file || std::fwrite(output, sizeof(float), count, file) != count) return 1;
        std::fclose(file);
    }
    dispose(object);
    dtor(object);
    std::free(object);
    std::printf("PASS: %d real-input frames, dispose and destructor; Gallery untested\n", frames);
    // Do not claim process-shutdown stability: vendor standalone atexit is unsafe.
    std::_Exit(0);
}
