// SPDX-License-Identifier: GPL-3.0-or-later
#include "horizon_core.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
static const double pi=3.14159265358979323846;
static const float identity[9]={1,0,0,0,1,0,0,0,1};
static void near(double a,double b){assert(fabs(a-b)<1e-4);}
static void map(const float m[9],double x,double y,double*ox,double*oy){double d=m[6]*x+m[7]*y+m[8];*ox=(m[0]*x+m[1]*y+m[2])/d;*oy=(m[3]*x+m[4]*y+m[5])/d;}
int main(void) {
    struct hl_state state={0};struct hl_result out;
    for(unsigned mode=0;mode<2;mode++) {
        assert(hl_start(&state,mode,0)==HL_BYPASS);
        assert(hl_transform(&state,1,pi,1920,1080,3,identity,&out)==HL_BYPASS);
        assert(!memcmp(out.matrix,identity,sizeof(identity)));
    }
    assert(hl_start(&state,2,0)==HL_OK);
    assert(hl_start(&state,2,pi/2)==HL_REFERENCE_LATCHED);
    near(state.reference_rad,0);
    assert(hl_transform(&state,0,pi,1920,1080,1,identity,&out)==HL_OK);
    assert(!memcmp(out.matrix,identity,sizeof(identity)));
    assert(hl_transform(&state,pi/2,pi,1920,1080,1,identity,&out)==HL_INSUFFICIENT_MARGIN);
    assert(!memcmp(out.matrix,identity,sizeof(identity)));
    near(out.required_zoom,1920.0/1080);
    // Independent expected points prove inverse-warp sign and center placement.
    assert(hl_transform(&state,pi/2,pi,100,100,1,identity,&out)==HL_OK);
    double x,y;map(out.matrix,100,50,&x,&y);near(x,50);near(y,100);
    map(out.matrix,50,0,&x,&y);near(x,100);near(y,50);
    // Existing translation must be applied after output coordinate rotation.
    float translated[9]={1,0,10,0,1,20,0,0,1};
    assert(hl_transform(&state,pi/2,pi,100,100,1,translated,&out)==HL_OK);
    map(out.matrix,100,50,&x,&y);near(x,60);near(y,120);
    // EIS already supplies the target roll: do not add another quarter-turn.
    float quarter_turn[9]={0,-1,100,1,0,0,0,0,1};
    assert(hl_transform(&state,pi/2,pi,100,100,1,quarter_turn,&out)==HL_OK);
    near(out.correction_rad,0);near(out.required_zoom,1);
    assert(!memcmp(out.matrix,quarter_turn,sizeof(quarter_turn)));
    float reflection[9]={-1,0,100,0,1,0,0,0,1};
    assert(hl_transform(&state,0,pi,100,100,2,reflection,&out)==HL_INVALID);
    assert(hl_transform(&state,pi,pi,1920,1080,1,identity,&out)==HL_OK);
    map(out.matrix,0,0,&x,&y);near(x,1920);near(y,1080);
    assert(hl_transform(&state,pi/2,pi/4,100,100,2,identity,&out)==HL_OK);
    near(out.correction_rad,pi/4);
    // Test every degree over a full turn: all corners fit the rotation domain.
    for(int degrees=-180;degrees<=180;degrees++) {
        assert(hl_transform(&state,degrees*pi/180,pi,1920,1080,3,identity,&out)==HL_OK);
        for(int corner=0;corner<4;corner++) {
            map(out.matrix,(corner&1)?1920:0,(corner&2)?1080:0,&x,&y);
            assert(x>=-1e-3&&x<=1920+1e-3&&y>=-1e-3&&y<=1080+1e-3);
        }
    }
    hl_stop(&state);assert(hl_start(&state,2,179*pi/180)==HL_OK);
    assert(hl_transform(&state,-179*pi/180,pi,100,100,2,identity,&out)==HL_OK);
    near(out.correction_rad,2*pi/180);
    assert(hl_transform(&state,NAN,pi,100,100,2,identity,&out)==HL_INVALID);
    assert(hl_transform(&state,0,pi,0,100,2,identity,&out)==HL_INVALID);
    float singular[9]={1,0,0,0,1,0,0,0,0};
    assert(hl_transform(&state,0,pi,100,100,2,singular,&out)==HL_INVALID);
    puts("PASS mode isolation, recording reference latch, warp direction/order, existing EIS rotation accounting, 180-degree geometry, crop refusal, angle wrap, invalid inputs");
    return 0;
}
