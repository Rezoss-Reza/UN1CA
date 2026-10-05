// SPDX-License-Identifier: GPL-3.0-or-later
#include "horizon_core.h"
#include <float.h>
#include <math.h>
#include <stddef.h>
#include <string.h>
static const double pi = 3.14159265358979323846;
static double wrap(double angle) { return remainder(angle, 2.0*pi); }
int hl_start(struct hl_state *state, unsigned mode, double reference_rad) {
    if (!state || !isfinite(reference_rad)) return HL_INVALID;
    if (mode != 2) { hl_stop(state); return HL_BYPASS; }
    if (state->active) return HL_REFERENCE_LATCHED;
    state->reference_rad=wrap(reference_rad);
    state->active=1;
    return HL_OK;
}
void hl_stop(struct hl_state *state) {
    if (state) { state->active=0; state->reference_rad=0; }
}
int hl_transform(const struct hl_state *state, double measured_rad,
                 double max_rad, unsigned width, unsigned height,
                 double available_zoom, const float existing[9],
                 struct hl_result *result) {
    if (!state || !existing || !result || !width || !height ||
        !isfinite(measured_rad) || !isfinite(state->reference_rad) ||
        !isfinite(max_rad) || max_rad<=0 || max_rad>pi ||
        !isfinite(available_zoom) || available_zoom<1) return HL_INVALID;
    for (unsigned i=0;i<9;i++) if (!isfinite(existing[i])) return HL_INVALID;
    struct hl_result candidate;
    memcpy(candidate.matrix,existing,sizeof(candidate.matrix));
    candidate.correction_rad=0; candidate.required_zoom=1;
    if (!state->active) { *result=candidate; return HL_BYPASS; }
    double angle=wrap(measured_rad-state->reference_rad);
    if (angle>max_rad) angle=max_rad;
    if (angle<-max_rad) angle=-max_rad;
    // Extract local rotation already supplied by EIS at the domain center.
    // Use the projective Jacobian, not just the upper-left matrix coefficients.
    double cx=width*0.5,cy=height*0.5;
    double d=existing[6]*cx+existing[7]*cy+existing[8];
    double nx=existing[0]*cx+existing[1]*cy+existing[2];
    double ny=existing[3]*cx+existing[4]*cy+existing[5];
    if (!isfinite(d)||fabs(d)<1e-12) return HL_INVALID;
    double j00=(existing[0]*d-existing[6]*nx)/(d*d);
    double j01=(existing[1]*d-existing[7]*nx)/(d*d);
    double j10=(existing[3]*d-existing[6]*ny)/(d*d);
    double j11=(existing[4]*d-existing[7]*ny)/(d*d);
    double determinant=j00*j11-j01*j10;
    if (!isfinite(determinant)||determinant<=1e-12) return HL_INVALID;
    double existing_angle=atan2(j10-j01,j00+j11);
    angle=wrap(angle-existing_angle);
    double c=cos(angle),s=sin(angle);
    double aspect=(double)width/height;
    double zoom=fmax(fabs(c)+fabs(s)/aspect,fabs(c)+fabs(s)*aspect);
    candidate.correction_rad=angle;
    candidate.required_zoom=zoom;
    if (zoom>available_zoom+1e-9) { *result=candidate; return HL_INSUFFICIENT_MARGIN; }
    // Inverse sampling rotates coordinates clockwise to counter-rotate pixels.
    // Scale around the center keeps the rotation footprint inside this domain.
    double a=c/zoom,b=s/zoom;
    double rotation[9]={a,-b,cx-a*cx+b*cy,
                        b,a,cy-b*cx-a*cy,0,0,1};
    double composed[9]={0};
    for (unsigned row=0;row<3;row++)
        for (unsigned col=0;col<3;col++)
            for (unsigned k=0;k<3;k++)
                composed[row*3+col]+=(double)existing[row*3+k]*rotation[k*3+col];
    if (!isfinite(composed[8]) || fabs(composed[8])<1e-12) return HL_INVALID;
    for (unsigned i=0;i<9;i++) {
        double value=composed[i]/composed[8];
        if (!isfinite(value) || fabs(value)>FLT_MAX) return HL_INVALID;
        candidate.matrix[i]=(float)value;
    }
    *result=candidate;
    return HL_OK;
}
