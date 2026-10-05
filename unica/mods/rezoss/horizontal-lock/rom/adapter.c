// SPDX-License-Identifier: GPL-3.0-or-later
// Exact S918BXXUAZZI8 bridge. Original algorithms/tuning and return values retained.
#include "../native/horizon_stream.h"
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <android/log.h>
#define EXPORT __attribute__((visibility("default")))
#define NODE_LIMIT 8
#define CONFIG "/vendor/etc/camera/unica_horizon_lock.conf"
#define DISABLE "/data/vendor/camera/unica_horizon_lock.disable"
typedef int (*init_fn)(void *,void *,void *,unsigned);
typedef int (*algo_fn)(void *,void *,void *);
typedef int (*meta_fn)(void *);
struct output {uint32_t frame,sensor,width,height;float matrix[9];unsigned valid;};
struct context {void *node;struct hl_stream stream;struct output output[HL_HISTORY];unsigned writes,skipped;uint64_t age;int enabled,fixed;};
static struct context contexts[NODE_LIMIT];
static uint64_t age;
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static uint32_t u32(const void *a){uint32_t v;memcpy(&v,a,4);return v;}
static uint64_t u64(const void *a){uint64_t v;memcpy(&v,a,8);return v;}
static unsigned byte(const void *a){return *(const unsigned char *)a;}
static struct context *find(void *node){for(unsigned i=0;i<NODE_LIMIT;i++)if(contexts[i].node==node){contexts[i].age=++age;return &contexts[i];}return NULL;}
static void config(int *enabled,int *fixed){
 *enabled=0;*fixed=0;
#ifdef HL_TEST
 *enabled=1;
#else
 FILE *f=fopen(CONFIG,"r");
 if(f){char line[96];while(fgets(line,sizeof(line),f)){int value;
  if(sscanf(line,"enabled=%d",&value)==1)*enabled=value==1;
  if(sscanf(line,"fixed_crop=%d",&value)==1)*fixed=value==1;
 }fclose(f);}
 if(access(DISABLE,F_OK)==0)*enabled=0;
#endif
}
static void input(struct context *c,const unsigned char *in){
 if(!c||!c->enabled)return;
 unsigned mode=u32((unsigned char *)c->node+0x7da18);
 if(mode!=c->stream.mode){hl_stream_reset(&c->stream,mode);memset(c->output,0,sizeof(c->output));}
 if(mode!=2||u32(in+4)!=0)return;
 unsigned count=u32(in+0x48);const struct hl_sample *samples=(const void *)(uintptr_t)u64(in+0x40);
 if(count&&count<=2048&&samples){
  uint64_t timestamp=samples[count/2].timestamp_us;
  // EIS can keep its context across recordings. Reacquire after delivery gaps.
  if(c->stream.last_timestamp&&timestamp>c->stream.last_timestamp&&
     timestamp-c->stream.last_timestamp>500000){
   unsigned mode=c->stream.mode;hl_stream_reset(&c->stream,mode);
   memset(c->output,0,sizeof(c->output));
  }
  hl_stream_input(&c->stream,u32(in),0,samples,count);
 }
}
static void output(struct context *c,const unsigned char *in,const unsigned char *out){
 if(!c||!c->enabled||c->stream.mode!=2||u32((unsigned char *)c->node+0x7da18)!=2)return;
 uint32_t frame=u32(out);struct output *o=&c->output[frame%HL_HISTORY];o->valid=0;
 if(!byte(out+8)||!byte(out+0x30)||byte(out+0x54)||byte(out+0x44)!=1||u32(out+0x48)!=1||u32(out+0x3c)||u32(in+4)!=0)return;
 const void *matrix=(const void *)(uintptr_t)u64(out+0x4c);if(!matrix)return;
 o->frame=frame;o->sensor=0;o->width=u32(out+0x34);o->height=u32(out+0x38);memcpy(o->matrix,matrix,36);o->valid=1;
}
EXPORT int unica_hl_init(void *handle,void *common,void *sensors,unsigned count,init_fn original,void *node){
 int result=original(handle,common,sensors,count);
 if(!node||common!=(unsigned char *)node+0x1180||sensors!=(unsigned char *)node+0x1200||!count||count>4)return result;
 pthread_mutex_lock(&lock);struct context *c=find(node);
 if(!c)for(unsigned i=0;i<NODE_LIMIT;i++)if(!contexts[i].node){c=&contexts[i];break;}
 if(!c){c=&contexts[0];for(unsigned i=1;i<NODE_LIMIT;i++)if(contexts[i].age<c->age)c=&contexts[i];}
 if(c){memset(c,0,sizeof(*c));c->node=node;c->age=++age;hl_stream_reset(&c->stream,u32((unsigned char *)node+0x7da18));config(&c->enabled,&c->fixed);if(result)c->enabled=0;
 __android_log_print(ANDROID_LOG_INFO,"UnicaHorizon","init mode=%u enabled=%d fixed_crop=%d result=%d",c->stream.mode,c->enabled,c->fixed,result);}
 pthread_mutex_unlock(&lock);return result;
}
EXPORT int unica_hl_dist(void *handle,void *in,void *out,uintptr_t unused,algo_fn original,void *node){
 (void)unused;pthread_mutex_lock(&lock);input(find(node),in);pthread_mutex_unlock(&lock);return original(handle,in,out);
}
EXPORT int unica_hl_stab(void *handle,void *in,void *out,uintptr_t unused,algo_fn original,void *node){
 (void)unused;int result=original(handle,in,out);if(!result){pthread_mutex_lock(&lock);output(find(node),in,out);pthread_mutex_unlock(&lock);}return result;
}
EXPORT int unica_hl_drain_dist(void *handle,void *in,void *out,uintptr_t unused,algo_fn original,void *node){
 (void)unused;(void)node;return original(handle,in,out);
}
EXPORT int unica_hl_drain_stab(void *handle,void *in,void *out,uintptr_t unused,algo_fn original,void *node){
 return unica_hl_stab(handle,in,out,unused,original,node);
}
static void publish(struct context *c,unsigned char *info){
 if(!c||!c->enabled||c->stream.mode!=2||u32((unsigned char *)c->node+0x7da18)!=2||u32(info)!=40||u32(info+0x14)!=3)return;
 unsigned char *items=(void *)(uintptr_t)u64(info+0x20);if(!items||u32(items)!=48||u32(items+0x18)!=360)return;
 uint64_t request=u64(items+8);unsigned char *data=(void *)(uintptr_t)u64(items+0x10);
 if(request>UINT32_MAX||!data||u32(data)!=1||u32(data+8)!=1||byte(data+0xc)!=1)return;
 struct output *o=&c->output[request%HL_HISTORY];float old[9];memcpy(old,data+0x10,36);
 if(!o->valid||o->frame!=request||o->width!=2400||o->height!=1352||u32(data+0x15c)!=o->width||u32(data+0x160)!=o->height||memcmp(old,o->matrix,36)){c->skipped++;return;}
 struct hl_result result;int status;
 if(c->fixed)status=hl_stream_centered_fixed(&c->stream,o->frame,o->sensor,o->width,o->height,0.7853981633974483,2,old,&result);
 else status=hl_stream_centered(&c->stream,o->frame,o->sensor,o->width,o->height,0.7853981633974483,2,old,&result);
 if(status!=HL_OK){c->skipped++;return;}
 memcpy(data+0x10,result.matrix,36);c->writes++;
 if(c->writes==1||c->writes%300==0)__android_log_print(ANDROID_LOG_INFO,"UnicaHorizon","request=%u writes=%u skipped=%u correction_deg=%.3f zoom=%.4f",o->frame,c->writes,c->skipped,result.correction_rad*57.29577951308232,result.required_zoom);
}
EXPORT int unica_hl_publish(void *info,uintptr_t unused1,uintptr_t unused2,uintptr_t unused3,meta_fn original,void *node){
 (void)unused1;(void)unused2;(void)unused3;
 pthread_mutex_lock(&lock);publish(find(node),info);pthread_mutex_unlock(&lock);return original(info);
}
