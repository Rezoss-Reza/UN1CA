#include <dlfcn.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <stdexcept>
#include <string>
#include "SNPE/SNPE.h"
#include "SNPE/SNPEBuilder.h"
#include "SNPE/SNPEUtil.h"

template <typename T> T load(void* library, const char* name) {
    auto address = dlsym(library, name);
    if (!address) throw std::runtime_error(std::string("Missing symbol: ") + name);
    return reinterpret_cast<T>(address);
}

#define API(name) auto name = load<decltype(&::name)>(library, #name)

int main(int argc, char** argv) {
    if (argc < 6) { std::fprintf(stderr, "Usage: probe DLC INPUT_RAW OUTPUT_DIR cpu|dsp OUTPUT...\n"); return 2; }
    try {
        void* library = dlopen("/vendor/lib64/libSNPE.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        API(Snpe_DlContainer_Open);
        API(Snpe_SNPEBuilder_Create);
        API(Snpe_SNPEBuilder_SetRuntimeProcessorOrder);
        API(Snpe_SNPEBuilder_SetOutputTensors);
        API(Snpe_SNPEBuilder_SetPlatformConfig);
        API(Snpe_SNPEBuilder_Build);
        API(Snpe_RuntimeList_Create);
        API(Snpe_RuntimeList_Add);
        API(Snpe_PlatformConfig_Create);
        API(Snpe_PlatformConfig_SetPlatformOptions);
        API(Snpe_StringList_Create);
        API(Snpe_StringList_Append);
        API(Snpe_SNPE_GetInputDimensions);
        API(Snpe_Util_CreateITensor);
        API(Snpe_Util_GetLastError);
        API(Snpe_ITensor_GetData);
        API(Snpe_ITensor_GetSize);
        API(Snpe_ITensor_GetShape);
        API(Snpe_TensorShape_Rank);
        API(Snpe_TensorShape_GetDimensions);
        API(Snpe_TensorMap_Create);
        API(Snpe_TensorMap_Add);
        API(Snpe_TensorMap_GetTensor_Ref);
        API(Snpe_SNPE_ExecuteITensors);
        auto container = Snpe_DlContainer_Open(argv[1]);
        if (!container) throw std::runtime_error("Container open failed");
        auto builder = Snpe_SNPEBuilder_Create(container);
        auto runtimes = Snpe_RuntimeList_Create();
        bool dsp = std::string(argv[4]) == "dsp";
        if (!dsp && std::string(argv[4]) != "cpu") throw std::runtime_error("Invalid runtime");
        if (Snpe_RuntimeList_Add(runtimes, dsp ? SNPE_RUNTIME_DSP : SNPE_RUNTIME_CPU) != 0 ||
            Snpe_SNPEBuilder_SetRuntimeProcessorOrder(builder, runtimes) != 0)
            throw std::runtime_error("Runtime selection failed");
        auto names = Snpe_StringList_Create();
        for (int index = 5; index < argc; ++index) Snpe_StringList_Append(names, argv[index]);
        if (Snpe_SNPEBuilder_SetOutputTensors(builder, names) != 0) throw std::runtime_error("Output selection failed");
        auto platform = Snpe_PlatformConfig_Create();
        if (!Snpe_PlatformConfig_SetPlatformOptions(platform, "unsignedPD:ON"))
            throw std::runtime_error("Platform options failed");
        if (Snpe_SNPEBuilder_SetPlatformConfig(builder, platform) != 0) throw std::runtime_error("Platform config failed");
        auto network = Snpe_SNPEBuilder_Build(builder);
        if (!network) throw std::runtime_error(std::string("Build failed: ") + Snpe_Util_GetLastError());
        auto shape = Snpe_SNPE_GetInputDimensions(network, "images");
        auto input = Snpe_Util_CreateITensor(shape);
        if (!input || Snpe_ITensor_GetSize(input) != 320 * 320 * 3) throw std::runtime_error("Input shape mismatch");
        std::ifstream stream(argv[2], std::ios::binary);
        const size_t bytes = Snpe_ITensor_GetSize(input) * sizeof(float);
        if (!stream.read(static_cast<char*>(Snpe_ITensor_GetData(input)), bytes) || stream.peek() != EOF)
            throw std::runtime_error("Input size mismatch");
        auto inputs = Snpe_TensorMap_Create(), outputs = Snpe_TensorMap_Create();
        Snpe_TensorMap_Add(inputs, "images", input);
        if (Snpe_SNPE_ExecuteITensors(network, inputs, outputs) != 0)
            throw std::runtime_error(std::string("Execute failed: ") + Snpe_Util_GetLastError());
        for (int index = 5; index < argc; ++index) {
            auto output = Snpe_TensorMap_GetTensor_Ref(outputs, argv[index]);
            if (!output) throw std::runtime_error("Missing output");
            auto dims = Snpe_ITensor_GetShape(output);
            std::printf("%s shape", argv[index]);
            for (size_t j = 0; j < Snpe_TensorShape_Rank(dims); ++j)
                std::printf(" %zu", Snpe_TensorShape_GetDimensions(dims)[j]);
            std::printf("\n");
            std::string filename(argv[index]);
            std::replace(filename.begin(), filename.end(), '/', '_');
            std::ofstream file(std::string(argv[3]) + "/" + filename + ".raw", std::ios::binary);
            file.write(static_cast<char*>(Snpe_ITensor_GetData(output)), Snpe_ITensor_GetSize(output) * sizeof(float));
            if (!file) throw std::runtime_error("Cannot write output");
        }
        // This short-lived diagnostic process owns all API handles until exit.
        std::printf("Inference passed, requested runtime=%s, no CPU fallback in runtime list\n", argv[4]);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "%s\n", error.what());
        std::fflush(nullptr);
        std::_Exit(1);
    }
    // Stock vendor SNPE crashes in its atexit cleanup in this standalone loader.
    // Keep that diagnostic limitation separate from session/inference results.
    std::fflush(nullptr);
    std::_Exit(0);
}
