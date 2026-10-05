// SPDX-License-Identifier: GPL-3.0-or-later
#include "horizon_stream.h"
#include <math.h>
#include <string.h>
static const double pi=3.14159265358979323846;
void hl_stream_reset(struct hl_stream*s,unsigned mode){if(s){memset(s,0,sizeof(*s));s->mode=mode;}}
int hl_stream_input(struct hl_stream*s,uint32_t frame,uint32_t sensor,
                    const struct hl_sample*samples,unsigned count){
    if(!s||!samples||!count||count>2048||sensor>3)return HL_INVALID;
    if(s->mode!=2)return HL_BYPASS;
    // Invalidate this slot even if delivery is unusable; do not use stale samples.
    struct hl_frame*slot=&s->frames[frame%HL_HISTORY];slot->valid=0;
    double x=0,y=0,z=0;
    for(unsigned i=0;i<count;i++){
        if(!samples[i].timestamp_us||(i&&samples[i].timestamp_us<=samples[i-1].timestamp_us))return HL_INVALID;
        for(unsigned j=0;j<3;j++)if(!isfinite(samples[i].value[j]))return HL_INVALID;
        x+=samples[i].value[0];y+=samples[i].value[1];z+=samples[i].value[2];
    }
    x/=count;y/=count;z/=count;double norm=sqrt(x*x+y*y+z*z);
    if(norm<7.5||norm>12.0||hypot(x,y)<6.0)return HL_INVALID;
    uint64_t timestamp=samples[count/2].timestamp_us;
    if(s->last_timestamp&&timestamp<=s->last_timestamp)return HL_INVALID;
    if(!s->started){
        // Fixed cardinal gravity reference, not the initial arbitrary tilt.
        double reference=nearbyint(atan2(x,y)/(pi/2))*(pi/2);
        // This experiment is calibrated only for USB-right landscape (+X gravity).
        if(x<=6||fabs(y)>fabs(x)*0.35)return HL_INVALID;
        if(hl_start(&s->reference,2,reference)!=HL_OK)return HL_INVALID;
        s->filtered_x=x;s->filtered_y=y;s->started=1;
    }else{
        // Time-based low-pass of acceleration; gyro fusion remains future work.
        double dt=(timestamp-s->last_timestamp)*1e-6;
        if(dt>0.5)return HL_INVALID;
        double alpha=1-exp(-dt/0.12);
        s->filtered_x+=alpha*(x-s->filtered_x);s->filtered_y+=alpha*(y-s->filtered_y);
    }
    s->last_timestamp=timestamp;
    *slot=(struct hl_frame){frame,sensor,timestamp,atan2(s->filtered_x,s->filtered_y),1};
    return HL_OK;
}
static void multiply(const double a[9],const double b[9],double out[9]){
    for(unsigned r=0;r<3;r++)for(unsigned c=0;c<3;c++){
        out[r*3+c]=0;for(unsigned k=0;k<3;k++)out[r*3+c]+=a[r*3+k]*b[k*3+c];
    }
}
int hl_stream_centered(struct hl_stream*s,uint32_t frame,uint32_t sensor,
                       unsigned width,unsigned height,double max_roll,
                       double available_zoom,const float centered[9],struct hl_result*out){
    if(!s||!centered||!out)return HL_INVALID;
    if(s->mode!=2)return HL_BYPASS;
    struct hl_frame*f=&s->frames[frame%HL_HISTORY];
    if(!f->valid||f->frame!=frame||f->sensor!=sensor)return HL_INVALID;
    double cx=width*0.5,cy=height*0.5;
    double plus[9]={1,0,cx,0,1,cy,0,0,1},minus[9]={1,0,-cx,0,1,-cy,0,0,1};
    double h[9],tmp[9],pixel[9];float pixel_f[9];
    for(unsigned i=0;i<9;i++)h[i]=centered[i];
    multiply(h,minus,tmp);multiply(plus,tmp,pixel);
    for(unsigned i=0;i<9;i++)pixel_f[i]=(float)pixel[i];
    struct hl_result candidate;
    int result=hl_transform(&s->reference,f->roll,max_roll,width,height,available_zoom,pixel_f,&candidate);
    if(result!=HL_OK)return result;
    for(unsigned i=0;i<9;i++)h[i]=candidate.matrix[i];
    multiply(h,plus,tmp);multiply(minus,tmp,pixel);
    if(!isfinite(pixel[8])||fabs(pixel[8])<1e-9)return HL_INVALID;
    for(unsigned i=0;i<9;i++)candidate.matrix[i]=(float)(pixel[i]/pixel[8]);
    // Shrink output coordinates until the complete warp fits, within the budget.
    // Center translation is retained. A center outside the domain cannot be fixed.
    double center_d=candidate.matrix[8];
    if(center_d<=1e-6||fabs(candidate.matrix[2]/center_d)>cx||
       fabs(candidate.matrix[5]/center_d)>cy)return HL_INSUFFICIENT_MARGIN;
    double maximum=available_zoom/candidate.required_zoom;
    if(!isfinite(maximum)||maximum<1)return HL_INSUFFICIENT_MARGIN;
    double low=1,high=maximum;
    for(unsigned iteration=0;iteration<32;iteration++){
        double scale=iteration==0?1:(iteration==1?maximum:(low+high)*0.5);
        int fits=1;
        for(unsigned corner=0;corner<4;corner++){
            double x=((corner&1)?cx:-cx)/scale,y=((corner&2)?cy:-cy)/scale;
            double d=candidate.matrix[6]*x+candidate.matrix[7]*y+candidate.matrix[8];
            if(!isfinite(d)||d<=1e-6){fits=0;break;}
            double ox=(candidate.matrix[0]*x+candidate.matrix[1]*y+candidate.matrix[2])/d;
            double oy=(candidate.matrix[3]*x+candidate.matrix[4]*y+candidate.matrix[5])/d;
            if(!isfinite(ox)||!isfinite(oy)||fabs(ox)>cx+0.001||fabs(oy)>cy+0.001){fits=0;break;}
        }
        if(iteration==0&&fits){high=1;break;}
        if(iteration==1&&!fits)return HL_INSUFFICIENT_MARGIN;
        if(fits)high=scale;else low=scale;
    }
    for(unsigned row=0;row<3;row++)for(unsigned column=0;column<2;column++)
        candidate.matrix[row*3+column]/=high;
    candidate.required_zoom*=high;
    *out=candidate;return HL_OK;
}

int hl_stream_centered_fixed(struct hl_stream*s,uint32_t frame,uint32_t sensor,
                            unsigned width,unsigned height,double max_roll,
                            double fixed_zoom,const float centered[9],struct hl_result*out){
    if(!out||!isfinite(fixed_zoom)||fixed_zoom<1)return HL_INVALID;
    struct hl_result candidate;
    int status=hl_stream_centered(s,frame,sensor,width,height,max_roll,fixed_zoom,centered,&candidate);
    if(status!=HL_OK)return status;
    // Right-multiply by centered uniform shrink; retain translation/perspective.
    double shrink=candidate.required_zoom/fixed_zoom;
    if(!isfinite(shrink)||shrink<=0||shrink>1.000001)return HL_INVALID;
    for(unsigned row=0;row<3;row++)for(unsigned column=0;column<2;column++)
        candidate.matrix[row*3+column]*=shrink;
    candidate.required_zoom=fixed_zoom;
    *out=candidate;return HL_OK;
}
