/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later
 * FileUtil.adjustSizeIfNeeded equivalent for standalone diagnostic probes.
 * Does not modify files. AIOS_PROBE_RAW=1 retains the full asset as a control.
 */
#ifndef SAMSUNG_MODEL_PAYLOAD_H
#define SAMSUNG_MODEL_PAYLOAD_H
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static long samsung_model_payload_size(FILE *file) {
    if (fseek(file, 0, SEEK_END)) return -1;
    long total = ftell(file);
    if (total <= 0) return -1;
    long payload = total;
    if (total >= 8 && !getenv("AIOS_PROBE_RAW")) {
        unsigned char tail[8];
        if (fseek(file, -8, SEEK_END) || fread(tail, 1, 8, file) != 8) return -1;
        if (!memcmp(tail + 4, "SSMT", 4)) {
            uint32_t length = (uint32_t)tail[0] | ((uint32_t)tail[1] << 8) |
                              ((uint32_t)tail[2] << 16) | ((uint32_t)tail[3] << 24);
            if ((uint64_t)length + 8 >= (uint64_t)total) {
                fprintf(stderr, "invalid SSMT metadata length: %u\n", length);
                return -1;
            }
            payload = total - (long)length - 8;
        }
    }
    if (fseek(file, 0, SEEK_SET)) return -1;
    printf("model asset=%ld QNN payload=%ld excluded tail=%ld\n", total, payload, total - payload);
    return payload;
}
#endif
