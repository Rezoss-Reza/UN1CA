// SPDX-License-Identifier: GPL-3.0-or-later
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <string.h>
#include <assert.h>
#include <math.h>
#include <pthread.h>
typedef int (*init_fn)(void*,void*,void*,unsigned);
typedef int (*algo_fn)(void*,void*,void*);
typedef int (*meta_fn)(void*);
extern int unica_hl_init(void*,void*,void*,unsigned,init_fn,void*);
extern int unica_hl_dist(void*,void*,void*,uintptr_t,algo_fn,void*);
extern int unica_hl_stab(void*,void*,void*,uintptr_t,algo_fn,void*);
extern int unica_hl_drain_dist(void*,void*,void*,uintptr_t,algo_fn,void*);
extern int unica_hl_drain_stab(void*,void*,void*,uintptr_t,algo_fn,void*);
extern int unica_hl_publish(void*,uintptr_t,uintptr_t,uintptr_t,meta_fn,void*);
static int wrap_init(void*a,void*b,void*c,unsigned n,init_fn f,void*node){return unica_hl_init(a,b,c,n,f,node);}
static int wrap_meta(void*a,meta_fn f,void*node){return unica_hl_publish(a,0,0,0,f,node);}
static int init(void*a,void*b,void*c,unsigned n){(void)a;(void)b;(void)c;(void)n;return 0;}
static int process(void*h,void*input,void*output_ptr){unsigned char*out=output_ptr;(void)h;uint32_t frame;memcpy(&frame,input,4);uint32_t output_frame=frame>30?frame-30:0;memcpy(out,&output_frame,4);out[8]=frame>30;out[0x30]=1;out[0x54]=0;out[0xa4]=0;out[0x44]=1;uint32_t columns=1;memcpy(out+0x48,&columns,4);uint32_t domain[2]={2400,1352};memcpy(out+0x34,domain,8);static float matrix[9]={1,0,0,0,1,0,0,0,1};void*ptr=matrix;memcpy(out+0x4c,&ptr,8);return 0;}
static void *child_init(void *node){unsigned char *n=node;wrap_init(n+0xc8,n+0x1180,n+0x1200,4,init,n);return NULL;}
static int publish(void*info){(void)info;return 0;}
int main(int argc,char**argv){unsigned mode=argc>1?atoi(argv[1]):2;unsigned char*node=calloc(1,0x80000),input[0x180]={0},output[176]={0};double samples[14]={1,2,3,4,5,6,0,7,8,9,10,11,12,0};uint64_t ts=1000;memcpy((char*)samples+48,&ts,8);ts=2000;memcpy((char*)samples+104,&ts,8);
*(uint32_t*)(node+0x7da18)=mode;node[0x11ab]=1;*(uint32_t*)(node+0xf10)=1;
for(int i=0;i<4;i++){unsigned char*s=node+0x1200+i*0xe830;*(uint32_t*)(s+0xe61c)=i&1;*(float*)(s+0xe620)=i*10.f;}
int offsets[]={0x20,0x40,0xa0};for(int i=0;i<3;i++){void*ptr=samples;memcpy(input+offsets[i],&ptr,8);uint32_t count=2;memcpy(input+offsets[i]+8,&count,4);}
unsigned char info[40]={0},items[144]={0},payload[0x168]={0},dummy[16]={0};
uint32_t tags[3]={123,124,125},size=40,count=3,length=0x168;void*ptr=tags;
memcpy(info,&size,4);memcpy(info+0x14,&count,4);memcpy(info+0x18,&ptr,8);ptr=items;memcpy(info+0x20,&ptr,8);
size=48;for(unsigned i=0;i<3;i++){memcpy(items+48*i,&size,4);ptr=i?dummy:payload;memcpy(items+48*i+0x10,&ptr,8);uint32_t len=i?16:length;memcpy(items+48*i+0x18,&len,4);}
uint32_t domain[2]={2400,1352};memcpy(payload+0x15c,domain,8);size=1;memcpy(payload,&size,4);memcpy(payload+8,&size,4);payload[0xc]=1;
float identity[9]={1,0,0,0,1,0,0,0,1};
pthread_t child;assert(!pthread_create(&child,NULL,child_init,node));assert(!pthread_join(child,NULL));
wrap_init(node+0xc8,node+0x1180,node+0x1200,4,init,node);
unsigned changed=0;
for(uint32_t frame=1;frame<=200;frame++){
 memcpy(input,&frame,4);
 double radians=frame%100>5?0.35:0.0;
 for(unsigned j=0;j<2;j++){double values[6]={9.81*cos(radians),9.81*sin(radians),0,0,0,0};memcpy((char*)samples+j*56,values,48);uint64_t timestamp=1000000+(uint64_t)frame*33333+j*1000;memcpy((char*)samples+j*56+48,&timestamp,8);}
 if(frame<=70||(frame>=101&&frame<=170)){unica_hl_dist(node,input,output,0,process,node);unica_hl_stab(node,input,output,0,process,node);}
 else {unica_hl_drain_dist(node,input,output,0,process,node);unica_hl_drain_stab(node,input,output,0,process,node);}
 if(frame>30){uint64_t request=frame-30;for(unsigned i=0;i<3;i++)memcpy(items+i*48+8,&request,8);
 memcpy(payload+0x10,identity,36);wrap_meta(info,publish,node);
 float matrix[9];memcpy(matrix,payload+0x10,36);
 if(memcmp(matrix,identity,36)){changed++;assert(mode==2);assert(fabs(matrix[2])<0.01&&fabs(matrix[5])<0.01);assert(matrix[1]>=0);}
 }
 usleep(3000);
}
// Live mode changes must bypass even if the initialization cache still says 2.
 *(uint32_t *)(node+0x7da18)=1;memcpy(payload+0x10,identity,36);wrap_meta(info,publish,node);
 assert(!memcmp(payload+0x10,identity,36));
 if(mode==2)assert(changed>=120);else assert(!changed);
 printf("PASS mode=%u corrected=%u, two recordings, delayed publication, gap reset and 30-frame drain\n",mode,changed);free(node);return 0;}
