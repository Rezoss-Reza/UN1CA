// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef UNICA_HORIZON_STREAM_H
#define UNICA_HORIZON_STREAM_H
#include "horizon_core.h"
#include <stdint.h>
#define HL_HISTORY 256
struct hl_sample { double value[6]; uint64_t timestamp_us; };
struct hl_frame { uint32_t frame, sensor; uint64_t timestamp_us; double roll; unsigned valid; };
struct hl_stream {
    unsigned mode, started; struct hl_state reference;
    uint64_t last_timestamp; double filtered_x, filtered_y;
    struct hl_frame frames[HL_HISTORY];
};
void hl_stream_reset(struct hl_stream *s, unsigned mode);
// Samples are raw device accelerometer coordinates in m/s^2. This experimental
// rear-camera mapping is fixed to the calibrated landscape test, USB on right.
// No front-camera, other mount, acceleration-heavy scene or sky support claimed.
int hl_stream_input(struct hl_stream *s, uint32_t frame, uint32_t sensor,
                    const struct hl_sample *samples, unsigned count);
int hl_stream_centered(struct hl_stream *s, uint32_t frame, uint32_t sensor,
                       unsigned width, unsigned height, double max_roll,
                       double available_zoom, const float centered_matrix[9],
                       struct hl_result *result);
// Fixed additional crop relative to stock EIS, to avoid per-frame zoom pumping.
int hl_stream_centered_fixed(struct hl_stream *s, uint32_t frame, uint32_t sensor,
                            unsigned width, unsigned height, double max_roll,
                            double fixed_zoom, const float centered_matrix[9],
                            struct hl_result *result);
#endif
