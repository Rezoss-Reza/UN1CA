// SPDX-License-Identifier: GPL-3.0-or-later
#include "horizon_stream.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
static const double pi=3.14159265358979323846;
static const float identity[9]={1,0,0,0,1,0,0,0,1};
int main(void){
 struct hl_stream stream;struct hl_sample a[3]={0};struct hl_result result;
 hl_stream_reset(&stream,2);
 for(unsigned i=0;i<3;i++){a[i].value[0]=9.81;a[i].timestamp_us=1000+i*1000;}
 assert(hl_stream_input(&stream,10,0,a,3)==HL_OK);
 assert(hl_stream_centered(&stream,10,0,2400,1352,pi,2,identity,&result)==HL_OK);
 assert(fabs(result.correction_rad)<1e-5);
 // Delayed output 10 must still use frame10, even after newer tilted frame11.
 for(unsigned i=0;i<3;i++){a[i].value[0]=8.496;a[i].value[1]=4.905;a[i].timestamp_us=301000+i*1000;}
 assert(hl_stream_input(&stream,11,0,a,3)==HL_OK);
 assert(hl_stream_centered(&stream,10,0,2400,1352,pi,2,identity,&result)==HL_OK);
 assert(fabs(result.correction_rad)<1e-5);
 assert(hl_stream_centered(&stream,11,0,2400,1352,pi,2,identity,&result)==HL_OK);
 assert(result.correction_rad<-.4&&result.correction_rad>-.55);
 // Centered ICA rotation should have no artificial half-image translation.
 assert(fabs(result.matrix[2])<1e-3&&fabs(result.matrix[5])<1e-3);
 // Fixed crop must be identical at level and tilted; no adaptive zoom breathing.
 assert(hl_stream_centered_fixed(&stream,10,0,2400,1352,pi,2,identity,&result)==HL_OK);
 assert(result.required_zoom==2&&fabs(result.matrix[0]-.5)<1e-6);
 assert(hl_stream_centered_fixed(&stream,11,0,2400,1352,pi,2,identity,&result)==HL_OK);
 assert(result.required_zoom==2);
 assert(fabs(hypot(result.matrix[0],result.matrix[3])-.5)<1e-6);
 assert(hl_stream_centered_fixed(&stream,11,0,2400,1352,pi,1,identity,&result)==HL_INSUFFICIENT_MARGIN);
 assert(hl_stream_centered(&stream,11,1,2400,1352,pi,2,identity,&result)==HL_INVALID);
 assert(hl_stream_centered(&stream,12,0,2400,1352,pi,2,identity,&result)==HL_INVALID);
 assert(hl_stream_centered(&stream,11,0,2400,1352,pi,1,identity,&result)==HL_INSUFFICIENT_MARGIN);
 // Translation and perspective need additional crop beyond rotation footprint.
 float translated[9]={1,0,12,0,1,8,0.000001f,-0.000002f,1};
 assert(hl_stream_centered(&stream,11,0,2400,1352,pi,2,translated,&result)==HL_OK);
 assert(result.required_zoom>1&&result.required_zoom<=2);
 for(unsigned corner=0;corner<4;corner++){
  double x=(corner&1)?1200:-1200,y=(corner&2)?676:-676;
  double d=result.matrix[6]*x+result.matrix[7]*y+result.matrix[8];
  assert(d>0);
  assert(fabs((result.matrix[0]*x+result.matrix[1]*y+result.matrix[2])/d)<=1200.01);
  assert(fabs((result.matrix[3]*x+result.matrix[4]*y+result.matrix[5])/d)<=676.01);
 }
 translated[2]=1500;
 assert(hl_stream_centered(&stream,11,0,2400,1352,pi,2,translated,&result)==HL_INSUFFICIENT_MARGIN);
 assert(hl_stream_input(&stream,11,0,a,3)==HL_INVALID);
 assert(hl_stream_centered(&stream,11,0,2400,1352,pi,2,identity,&result)==HL_INVALID);
 hl_stream_reset(&stream,1);assert(hl_stream_input(&stream,10,0,a,3)==HL_BYPASS);
 hl_stream_reset(&stream,2);a[0].value[0]=0;a[0].value[1]=9.81;
 a[1]=a[0];a[2]=a[0];for(unsigned i=0;i<3;i++)a[i].timestamp_us=1000+i*1000;
 assert(hl_stream_input(&stream,10,0,a,3)==HL_INVALID);
 puts("PASS delayed frame matching, sensor guard, landscape guard, centered ICA coordinates, crop refusal, stale sample rejection");
 return 0;
}
