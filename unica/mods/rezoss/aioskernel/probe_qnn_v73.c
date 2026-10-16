/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Isolated Android shell probe; no APK installation or system writes.
 * Compile with Android NDK and the selected QAIRT SDK include/QNN directory.
 * Usage: probe WRAPPER BACKEND [MODEL_ASSET]
 * Excludes Samsung SSMT metadata from the QNN buffer length, as FileUtil does.
 * AIOS_PROBE_RAW=1 reproduces the old full-asset control.
 * This mirrors the exact AIOS 1.4.28 object offsets observed with REA.
 */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include "QnnInterface.h"
#include "samsung_model_payload.h"

static void *symbol(void *lib, const char *name) {
    void *p = dlsym(lib, name);
    if (!p) { fprintf(stderr, "%s: %s\n", name, dlerror()); exit(2); }
    return p;
}

int main(int argc, char **argv) {
    if (argc < 3 || argc > 4) return 2;
    setbuf(stdout, NULL);
    void *backend = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
    if (!backend) { fprintf(stderr, "backend: %s\n", dlerror()); return 2; }
    void *wrapper = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!wrapper) { fprintf(stderr, "wrapper: %s\n", dlerror()); return 2; }
    void (*init_logging)(void) = symbol(wrapper, "_ZN3qnn3log17initializeLoggingEv");
    void (*set_log_level)(QnnLog_Level_t) = symbol(wrapper, "_ZN3qnn3log11setLogLevelE14QnnLog_Level_t");
    init_logging();
    set_log_level(QNN_LOG_LEVEL_DEBUG);
    int (*init)(void *) = symbol(wrapper, "_ZN8snap_qnn14SnapQnnHandler17initializeBackendEv");
    unsigned char *handler = calloc(1, 0x500);
    if (!handler) return 2;
    memcpy(handler + 0x98, &backend, sizeof(backend));
    int status = init(handler);
    printf("Samsung initializeBackend=%d initialized=%u\n", status, handler[0xa8]);
    if (status || !handler[0xa8]) return 1;
    QNN_INTERFACE_VER_TYPE api = {0};
    _Static_assert(sizeof(api) >= 0x230, "SDK interface too small");
    memcpy(&api, handler + 0xf0, 0x230);
    Qnn_BackendHandle_t bh;
    Qnn_DeviceHandle_t dh;
    Qnn_LogHandle_t lh;
    memcpy(&bh, handler + 0xb0, sizeof(bh));
    memcpy(&dh, handler + 0xb8, sizeof(dh));
    memcpy(&lh, handler + 0xc0, sizeof(lh));
    printf("handles backend=%p device=%p logger=%p\n", bh, dh, lh);
    if (!bh || !dh || !api.contextCreate || !api.contextFree ||
        !api.deviceFree || !api.backendFree || !api.logFree) return 1;
    Qnn_ContextHandle_t context = NULL;
    Qnn_ErrorHandle_t err = api.contextCreate(bh, dh, NULL, &context);
    printf("contextCreate=%llu handle=%p\n", (unsigned long long)err, context);
    int failed = err || !context;
    if (!err && context) {
        err = api.contextFree(context, NULL);
        printf("contextFree=%llu\n", (unsigned long long)err);
        failed |= err != 0;
    }
    if (!failed && argc == 4) {
        FILE *f = fopen(argv[3], "rb");
        if (!f) return 2;
        long size = samsung_model_payload_size(f);
        if (size <= 0) return 2;
        void *data = mmap(NULL, (size_t)size, PROT_READ, MAP_PRIVATE, fileno(f), 0);
        if (data == MAP_FAILED) return 2;
        fclose(f);
        context = NULL;
        if (!api.contextCreateFromBinary) return 1;
        err = api.contextCreateFromBinary(bh, dh, NULL, data, (uint64_t)size, &context, NULL);
        printf("contextCreateFromBinary=%llu handle=%p\n", (unsigned long long)err, context);
        failed |= err != 0 || !context;
        if (!err && context) failed |= api.contextFree(context, NULL) != 0;
        munmap(data, (size_t)size);
    }
    err = api.deviceFree(dh);
    printf("deviceFree=%llu\n", (unsigned long long)err);
    failed |= err != 0;
    /* Exercise the separate CreateDevice implementation after freeing the first handle. */
    if (!err) {
        memset(handler + 0xb8, 0, sizeof(dh));
        void (*create_device)(void *) = symbol(wrapper, "_ZN8snap_qnn14SnapQnnHandler12CreateDeviceEv");
        create_device(handler);
        memcpy(&dh, handler + 0xb8, sizeof(dh));
        printf("Samsung CreateDevice handle=%p\n", dh);
        failed |= !dh;
        if (dh) failed |= api.deviceFree(dh) != 0;
    }
    err = api.backendFree(bh);
    printf("backendFree=%llu\n", (unsigned long long)err);
    failed |= err != 0;
    if (lh) failed |= api.logFree(lh) != 0;
    free(handler);
    puts(argc == 4 ? "Scope: model context loading, no inference" : "Scope: backend/device/context only, no model or inference");
    return failed ? 1 : 0;
}
