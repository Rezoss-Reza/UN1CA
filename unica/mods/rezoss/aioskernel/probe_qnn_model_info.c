/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Android NDK + QAIRT include/QNN. Usage: probe SYSTEM_LIBRARY MODEL_ASSET
 * CPU-side metadata parsing only. No backend, DSP execution or file mutation.
 */
#include <dlfcn.h>
#include <sys/mman.h>
#include "System/QnnSystemInterface.h"
#include "samsung_model_payload.h"

static void print_tensor(const char *direction, const Qnn_Tensor_t *tensor) {
    const char *name;
    uint32_t rank;
    const uint32_t *dimensions;
    Qnn_DataType_t type;
    Qnn_QuantizeParams_t quant;
    if (tensor->version == QNN_TENSOR_VERSION_1) {
        name = tensor->v1.name; rank = tensor->v1.rank;
        dimensions = tensor->v1.dimensions; type = tensor->v1.dataType;
        quant = tensor->v1.quantizeParams;
    } else if (tensor->version == QNN_TENSOR_VERSION_2) {
        name = tensor->v2.name; rank = tensor->v2.rank;
        dimensions = tensor->v2.dimensions; type = tensor->v2.dataType;
        quant = tensor->v2.quantizeParams;
    } else {
        printf("%s unknown tensor version=%u\n", direction, tensor->version);
        return;
    }
    printf("%s name=%s datatype=0x%x dimensions=", direction, name ? name : "(null)", type);
    if (dimensions && rank <= 32) {
        for (uint32_t i = 0; i < rank; ++i) printf("%s%u", i ? "x" : "", dimensions[i]);
    } else printf("unavailable");
    putchar('\n');
    printf("quantization encoding=%u", quant.quantizationEncoding);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET)
        printf(" scale=%.9g offset=%d", quant.scaleOffsetEncoding.scale, quant.scaleOffsetEncoding.offset);
    putchar('\n');
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    setbuf(stdout, NULL);
    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    Qnn_ErrorHandle_t (*providers)(const QnnSystemInterface_t ***, uint32_t *) =
        dlsym(lib, "QnnSystemInterface_getProviders");
    if (!providers) return 2;
    const QnnSystemInterface_t **list = NULL;
    uint32_t count = 0;
    Qnn_ErrorHandle_t err = providers(&list, &count);
    if (err || !list || !count || !list[0]) return 1;
    const QnnSystemInterface_t *provider = list[0];
    printf("system API=%u.%u.%u\n", provider->systemApiVersion.major,
           provider->systemApiVersion.minor, provider->systemApiVersion.patch);
    if (provider->systemApiVersion.major != 1 || provider->systemApiVersion.minor < 5) return 1;
    const QNN_SYSTEM_INTERFACE_VER_TYPE *api = &provider->QNN_SYSTEM_INTERFACE_VER_NAME;
    if (!api->systemContextCreate || !api->systemContextGetBinaryInfo || !api->systemContextFree) return 1;
    FILE *file = fopen(argv[2], "rb");
    if (!file) return 2;
    long size = samsung_model_payload_size(file);
    if (size <= 0) { fclose(file); return 2; }
    void *data = mmap(NULL, (size_t)size, PROT_READ, MAP_PRIVATE, fileno(file), 0);
    fclose(file);
    if (data == MAP_FAILED) return 2;
    QnnSystemContext_Handle_t handle = NULL;
    err = api->systemContextCreate(&handle);
    if (err || !handle) { munmap(data, (size_t)size); return 1; }
    const QnnSystemContext_BinaryInfo_t *info = NULL;
    Qnn_ContextBinarySize_t info_size = 0;
    err = api->systemContextGetBinaryInfo(handle, data, (uint64_t)size, &info, &info_size);
    printf("systemGetBinaryInfo=%llu info=%p\n", (unsigned long long)err, info);
    int failed = err != 0 || !info;
    if (!failed) {
        printf("metadata version=%u\n", info->version);
        if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
            const QnnSystemContext_BinaryInfoV3_t *b = &info->contextBinaryInfoV3;
            printf("core=%u.%u.%u backend=%u.%u.%u blob=%u.%u.%u graphs=%u\n",
                   b->coreApiVersion.major, b->coreApiVersion.minor, b->coreApiVersion.patch,
                   b->backendApiVersion.major, b->backendApiVersion.minor, b->backendApiVersion.patch,
                   b->contextBlobVersion.major, b->contextBlobVersion.minor, b->contextBlobVersion.patch,
                   b->numGraphs);
            printf("SoC model=%u build=%s\n", b->socModel, b->buildId ? b->buildId : "(null)");
            if (b->numGraphs <= 1024 && b->graphs) {
                for (uint32_t i = 0; i < b->numGraphs; ++i) {
                    const QnnSystemContext_GraphInfo_t *graph = &b->graphs[i];
                    if (graph->version != QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
                        printf("graph[%u] metadata version=%u (not projected)\n", i, graph->version);
                        continue;
                    }
                    const QnnSystemContext_GraphInfoV3_t *g = &graph->graphInfoV3;
                    printf("graph[%u]=%s inputs=%u outputs=%u\n", i,
                           g->graphName ? g->graphName : "(null)", g->numGraphInputs, g->numGraphOutputs);
                    if (g->numGraphInputs <= 1024 && g->graphInputs)
                        for (uint32_t j = 0; j < g->numGraphInputs; ++j) print_tensor("input", &g->graphInputs[j]);
                    if (g->numGraphOutputs <= 1024 && g->graphOutputs)
                        for (uint32_t j = 0; j < g->numGraphOutputs; ++j) print_tensor("output", &g->graphOutputs[j]);
                }
            }
        }
    }
    failed |= api->systemContextFree(handle) != 0;
    munmap(data, (size_t)size);
    dlclose(lib);
    return failed ? 1 : 0;
}
