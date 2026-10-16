/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Isolated V73 MatMul test, using one recovered Samsung contextual-reply matrix.
 * Usage: probe WEIGHTS_U16 WEIGHT_SCALE OUTPUT_U16
 * Requires matching QAIRT HTP/Prepare/System and V73 stub/skel in library paths.
 * This does not execute the Samsung decoder or produce language-model replies.
 */
#include <dlfcn.h>
#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "QnnInterface.h"
#include "QnnOpDef.h"
#include "HTP/QnnHtpDevice.h"

#define K 3072
#define N 32
static void logger(const char *fmt, QnnLog_Level_t level, uint64_t time, va_list args) {
    (void)level; (void)time;
    vfprintf(stderr, fmt, args); fputc('\n', stderr);
}
static Qnn_Tensor_t tensor(const char *name, Qnn_TensorType_t type, uint32_t *dims,
                           uint16_t *data, uint32_t count, float scale) {
    Qnn_Tensor_t t = QNN_TENSOR_INIT;
    t.version = QNN_TENSOR_VERSION_1;
    t.v1 = (Qnn_TensorV1_t)QNN_TENSOR_V1_INIT;
    t.v1.name = name; t.v1.type = type;
    t.v1.dataType = QNN_DATATYPE_UFIXED_POINT_16;
    t.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_DENSE;
    t.v1.rank = 2; t.v1.dimensions = dims;
    t.v1.quantizeParams.encodingDefinition = QNN_DEFINITION_DEFINED;
    t.v1.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
    t.v1.quantizeParams.scaleOffsetEncoding.scale = scale;
    t.v1.quantizeParams.scaleOffsetEncoding.offset = -32768;
    t.v1.memType = QNN_TENSORMEMTYPE_RAW;
    if (type == QNN_TENSOR_TYPE_STATIC) {
        t.v1.clientBuf.data = data; t.v1.clientBuf.dataSize = count * sizeof(uint16_t);
    }
    return t;
}
int main(int argc, char **argv) {
    if (argc != 4) return 2;
    setbuf(stdout, NULL);
    char *end = NULL; float scale = strtof(argv[2], &end);
    if (!end || *end || !isfinite(scale) || scale <= 0) return 2;
    uint16_t *weight = calloc(K * N, sizeof(uint16_t));
    if (!weight) return 2;
    FILE *file = fopen(argv[1], "rb");
    if (!file) { free(weight); return 2; }
    size_t read_count = fread(weight, sizeof(uint16_t), K * N, file);
    int extra = fgetc(file); fclose(file);
    if (read_count != K * N || extra != EOF) { free(weight); return 2; }
    uint16_t input[K], output[N] = {0};
    for (int k = 0; k < K; ++k) input[k] = 32768 + ((k * 17) % 257) - 128;
    /* The online-prepare SDK crashes while unloading this library during
     * backendFree on the test phone. Pin it for this short-lived probe process.
     * This is a diagnostic workaround, not an application lifecycle solution. */
    void *prepare = dlopen("libQnnHtpPrepare.so", RTLD_NOW | RTLD_LOCAL | RTLD_NODELETE);
    if (!prepare) { fprintf(stderr, "prepare: %s\n", dlerror()); free(weight); return 2; }
    void *lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "%s\n", dlerror()); free(weight); return 2; }
    Qnn_ErrorHandle_t (*get)(const QnnInterface_t ***, uint32_t *) = dlsym(lib, "QnnInterface_getProviders");
    const QnnInterface_t **providers = NULL; uint32_t count = 0;
    if (!get || get(&providers, &count) || count != 1) { free(weight); dlclose(lib); return 2; }
    printf("API=%u.%u.%u\n", providers[0]->apiVersion.coreApiVersion.major,
           providers[0]->apiVersion.coreApiVersion.minor, providers[0]->apiVersion.coreApiVersion.patch);
    const QNN_INTERFACE_VER_TYPE *api = &providers[0]->QNN_INTERFACE_VER_NAME;
    Qnn_LogHandle_t log = NULL; Qnn_BackendHandle_t backend = NULL;
    Qnn_DeviceHandle_t device = NULL; Qnn_ContextHandle_t context = NULL;
    Qnn_GraphHandle_t graph = NULL; int failed = 1;
#define CALL(expr) do { Qnn_ErrorHandle_t e = (expr); printf(#expr " = %llu\n", (unsigned long long)e); if (e) goto cleanup; } while (0)
    CALL(api->logCreate(logger, QNN_LOG_LEVEL_INFO, &log));
    CALL(api->backendCreate(log, NULL, &backend));
    QnnHtpDevice_CustomConfig_t arch = {.option=QNN_HTP_DEVICE_CONFIG_OPTION_ARCH,
        .arch={.deviceId=0, .arch=QNN_HTP_DEVICE_ARCH_V73}};
    QnnHtpDevice_CustomConfig_t soc = {.option=QNN_HTP_DEVICE_CONFIG_OPTION_SOC, .socModel=43};
    QnnDevice_Config_t dc1 = QNN_DEVICE_CONFIG_INIT, dc2 = QNN_DEVICE_CONFIG_INIT;
    dc1.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; dc1.customConfig = &arch;
    dc2.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; dc2.customConfig = &soc;
    const QnnDevice_Config_t *configs[] = {&dc1, &dc2, NULL};
    CALL(api->deviceCreate(log, configs, &device));
    CALL(api->contextCreate(backend, device, NULL, &context));
    CALL(api->graphCreate(context, "recovered_lora_A", NULL, &graph));
    uint32_t xd[] = {1, K}, wd[] = {K, N}, yd[] = {1, N};
    Qnn_Tensor_t ins[2] = {
        tensor("synthetic_hidden", QNN_TENSOR_TYPE_APP_WRITE, xd, input, K, 1.0f/1024),
        tensor("q_lora_A_weights_d_1", QNN_TENSOR_TYPE_STATIC, wd, weight, K*N, scale)};
    Qnn_Tensor_t out = tensor("projection", QNN_TENSOR_TYPE_APP_READ, yd, output, N, 1.0f/4096);
    CALL(api->tensorCreateGraphTensor(graph, &ins[0]));
    CALL(api->tensorCreateGraphTensor(graph, &ins[1]));
    CALL(api->tensorCreateGraphTensor(graph, &out));
    Qnn_OpConfig_t op = QNN_OPCONFIG_INIT;
    op.v1.name = "reconstructed_lora_projection";
    op.v1.packageName = QNN_OP_PACKAGE_NAME_QTI_AISW;
    op.v1.typeName = QNN_OP_MAT_MUL;
    op.v1.numOfInputs = 2; op.v1.inputTensors = ins;
    op.v1.numOfOutputs = 1; op.v1.outputTensors = &out;
    CALL(api->graphAddNode(graph, op));
    CALL(api->graphFinalize(graph, NULL, NULL));
    ins[0].v1.clientBuf.data = input; ins[0].v1.clientBuf.dataSize = sizeof(input);
    out.v1.clientBuf.data = output; out.v1.clientBuf.dataSize = sizeof(output);
    CALL(api->graphExecute(graph, ins, 1, &out, 1, NULL, NULL));
    file = fopen(argv[3], "wb");
    if (!file) goto cleanup;
    size_t written = fwrite(output, sizeof(uint16_t), N, file);
    int close_status = fclose(file);
    if (written != N || close_status) goto cleanup;
    double max_error = 0; int max_lsb = 0, saturation = 0;
    for (int n = 0; n < N; ++n) {
        int64_t acc = 0;
        for (int k = 0; k < K; ++k)
            acc += ((int32_t)input[k]-32768) * (int64_t)((int32_t)weight[k*N+n]-32768);
        double reference = (double)acc * (1.0/1024) * scale;
        long ideal = lrint(reference * 4096) + 32768;
        if (ideal < 0 || ideal > 65535) saturation++;
        if (ideal < 0) ideal = 0;
        if (ideal > 65535) ideal = 65535;
        int lsb = abs((int)output[n] - (int)ideal);
        if (lsb > max_lsb) max_lsb = lsb;
        double value = ((int32_t)output[n]-32768) / 4096.0;
        double error = fabs(reference-value); if (error > max_error) max_error=error;
        printf("y[%d] q=%u expected_q=%ld value=%.9g reference=%.9g error=%.9g\n",
               n, output[n], ideal, value, reference, error);
    }
    printf("max_abs_error=%.9g max_quantized_lsb=%d saturated_reference=%d\n", max_error, max_lsb, saturation);
    failed = saturation || max_lsb > 2;
    printf("RESULT=%s (one recovered matrix, synthetic activation, not full Gauss inference)\n", failed ? "FAIL" : "PASS");
cleanup:
    if (context) api->contextFree(context, NULL);
    if (device) api->deviceFree(device);
    if (backend) api->backendFree(backend);
    if (log) api->logFree(log);
    free(weight); dlclose(lib); dlclose(prepare); return failed;
}
