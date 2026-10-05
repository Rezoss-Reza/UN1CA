// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef UNICA_HORIZON_CORE_H
#define UNICA_HORIZON_CORE_H
#ifdef __cplusplus
extern "C" {
#endif
// Private candidate API. This is NOT the Qualcomm eis3_* ABI.
// One state per stream; caller serializes access and resets at recording end.
struct hl_state { unsigned active; double reference_rad; };
struct hl_result { float matrix[9]; double correction_rad, required_zoom; };
enum { HL_OK=0, HL_BYPASS=1, HL_REFERENCE_LATCHED=2,
       HL_INVALID=-1, HL_INSUFFICIENT_MARGIN=-2 };
int hl_start(struct hl_state *state, unsigned mode, double reference_rad);
void hl_stop(struct hl_state *state);
// Row-major matrix maps output pixels to input pixels in a common pixel domain.
// Positive angle is clockwise in x-right/y-down image coordinates.
// measured_rad MUST already be calibrated into this domain by the node adapter.
// width/height describe the centered rotation domain; zoom is relative to it.
// Existing EIS rotation is estimated from the center projective Jacobian.
// Reflections and degenerate Jacobians are rejected.
// Caller must additionally validate composed warp against real source crop/grid.
int hl_transform(const struct hl_state *state, double measured_rad,
                 double max_rad, unsigned width, unsigned height,
                 double available_zoom, const float existing[9],
                 struct hl_result *result);
#ifdef __cplusplus
}
#endif
#endif
