/* SPDX-License-Identifier: GPL-3.0-or-later */
// Caller must verify MCA SHA-256 bedfc253...526fabd7 before invoking this ABI.
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <media/NdkMediaFormat.h>
#include <stdexcept>

template <typename T> T symbol(void* library, const char* name) {
    void* address = dlsym(library, name);
    if (!address) throw std::runtime_error(name);
    return reinterpret_cast<T>(address);
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    try {
        void* library = dlopen("/system/lib64/libmediacontextanalyzer.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        auto construct = symbol<void (*)(void*, void*, const char*, int)>(library,
                "_ZN20MediaContextAnalyzer12VideoDecoderC1EPNS_13SharedContextEPKci");
        auto initialize = symbol<int (*)(void*)>(library,
                "_ZN20MediaContextAnalyzer12VideoDecoder11initDecoderEv");
        auto start = symbol<bool (*)(void*)>(library,
                "_ZN20MediaContextAnalyzer12VideoDecoder12startDecoderEv");
        auto decode = symbol<int (*)(void*, unsigned char**, size_t*, long*)>(library,
                "_ZN20MediaContextAnalyzer12VideoDecoder14decodeOneFrameEPPhPmPl");
        auto format = symbol<AMediaFormat* (*)(void*)>(library,
                "_ZN20MediaContextAnalyzer12VideoDecoder15getOutputFormatEv");
        auto release = symbol<void (*)(void*)>(library,
                "_ZN20MediaContextAnalyzer12VideoDecoder20releaseDecodedBufferEv");
        auto destroy = symbol<void (*)(void*)>(library,
                "_ZN20MediaContextAnalyzer12VideoDecoderD1Ev");
        // Pinned constructors access through offset 0xc3. doDecode()/run(),
        // which require SharedContext workers, are deliberately never called.
        alignas(16) std::array<unsigned char, 256> decoder{};
        construct(decoder.data(), nullptr, argv[1], std::strlen(argv[1]));
        const int initialized = initialize(decoder.data());
        std::printf("initialize=%d\n", initialized);
        if (initialized != 0 || !start(decoder.data())) {
            destroy(decoder.data());
            throw std::runtime_error("MCA decoder initialization failed");
        }
        bool captured = false;
        for (int iteration = 0; iteration < 500; ++iteration) {
            unsigned char* buffer = nullptr;
            size_t size = 0;
            long timestamp = 0;
            const int result = decode(decoder.data(), &buffer, &size, &timestamp);
            if (result == 1) {
                AMediaFormat* output = format(decoder.data());
                if (!output) throw std::runtime_error("No output format");
                std::printf("MCA_OUTPUT %s\n", AMediaFormat_toString(output));
                captured = true;
            }
            if (result == 0 && buffer) {
                std::printf("MCA_FRAME pts=%ld bytes=%zu\n", timestamp, size);
                release(decoder.data());
                break;
            }
            if (result == 2 || result < 0) break;
        }
        destroy(decoder.data());
        if (!captured) throw std::runtime_error("No MCA format transition captured");
    } catch (const std::exception& error) {
        std::fprintf(stderr, "%s\n", error.what());
        std::fflush(nullptr);
        std::_Exit(1);
    }
    std::fflush(nullptr);
    std::_Exit(0);
}
