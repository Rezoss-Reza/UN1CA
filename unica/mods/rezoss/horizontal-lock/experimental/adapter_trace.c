// SPDX-License-Identifier: GPL-3.0-or-later
// Exact-firmware experimental probe; never installed by the ROM build.
#define _GNU_SOURCE
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "../native/horizon_stream.h"
struct dbg {uint32_t info,pad;struct{uint64_t addr;uint32_t ctrl,pad;}r[16];};
struct regs {uint64_t x[31],sp,pc,pstate;};
struct task {int tid,stopped,configured,step,signal;struct dbg original;uint64_t node,common,sensors,input,output,metadata;unsigned count,kind;};
struct output_record {uint64_t node;uint32_t frame,sensor,width,height;float matrix[9];unsigned valid;};
static struct output_record outputs[HL_HISTORY];
static struct hl_stream stream;static uint64_t stream_node;static int apply_correction;
static unsigned corrections;
static uint32_t original_code[8];static unsigned steps;static int enable180;static struct task tasks[2048];static int nt,memfd,provider;static uint64_t bp[8];static volatile sig_atomic_t end;
static void handler(int s){(void)s;end=1;}
static struct task *find(int tid){for(int i=0;i<nt;i++)if(tasks[i].tid==tid)return &tasks[i];return NULL;}
static struct task *add(int tid){struct task*t=find(tid);if(t)return t;if(nt==2048)return NULL;t=&tasks[nt++];t->tid=tid;return t;}
static int readmem(uint64_t a,void *v,size_t n){return pread(memfd,v,n,a&0x00ffffffffffffffULL)==(ssize_t)n?0:-1;}
static uint32_t u32(uint64_t a){uint32_t v=0;readmem(a,&v,4);return v;}
static unsigned u8(uint64_t a){unsigned char v=0;readmem(a,&v,1);return v;}
static double flt(uint64_t a){float v=0;readmem(a,&v,4);return isfinite(v)?v:0;}
static int patchcode(int enable){uint32_t trap=0xd4200000;for(int i=0;i<8;i++){uint32_t v=enable?trap:original_code[i];if(pwrite(memfd,&v,4,bp[i])!=4)return -1;uint32_t ck;if(readmem(bp[i],&ck,4)||ck!=v)return -1;}return 0;}
static int configure(struct task*t,int child){(void)child;t->configured=1;return 0;}
static void capture(struct task*t,const char*stage,int result){struct timespec tm;clock_gettime(CLOCK_REALTIME,&tm);printf("{\"event\":\"eis3_init\",\"stage\":\"%s\",\"time\":%lld.%03ld,\"tid\":%d,\"mode\":%u,\"count\":%u,\"result\":%d,\"acc_enabled\":%u,\"gravity_enabled\":%u,\"acc_registered\":%u,\"mag_registered\":%u,\"gravity_registered\":%u,\"sensors\":[",stage,(long long)tm.tv_sec,tm.tv_nsec/1000000,t->tid,u32(t->node+0x7da18),t->count,result,u8(t->common+0x2b),u8(t->common+0x33),u32(t->node+0xf10),u32(t->node+0xf14),u32(t->node+0xf18));for(unsigned i=0;i<t->count;i++){uint64_t s=t->sensors+i*0xe830;printf("%s{\"index\":%u,\"horizon_enable\":%u,\"max_roll_deg\":%.9g,\"sky_start\":%.9g,\"sky_stop\":%.9g,\"roll_start\":%.9g,\"roll_stop\":%.9g}",i?",":"",i,u32(s+0xe61c),flt(s+0xe620),flt(s+0x128),flt(s+0x12c),flt(s+0x130),flt(s+0x134));}puts("]}");fflush(stdout);}

static uint64_t u64(uint64_t a){uint64_t v=0;readmem(a,&v,8);return v;}
static void hexbytes(uint64_t addr,unsigned n){unsigned char b[176];if(n>sizeof(b)||readmem(addr,b,n)){printf("null");return;}putchar('"');for(unsigned i=0;i<n;i++)printf("%02x",b[i]);putchar('"');}
static void sample(uint64_t ptr,unsigned count){
 printf("{\"count\":%u,\"samples\":[",count);
 if(ptr&&count&&count<=2048){for(unsigned j=0;j<(count>1?2u:1u);j++){
 uint64_t a=ptr+(j?count-1:0)*56;unsigned char b[56];
 if(readmem(a,b,sizeof(b))){printf("%snull",j?",":"");continue;}
 printf("%s{\"values\":[",j?",":"");for(int i=0;i<6;i++){double d;memcpy(&d,b+i*8,8);if(isfinite(d))printf("%s%.12g",i?",":"",d);else printf("%snull",i?",":"");}
 uint64_t ts;memcpy(&ts,b+48,8);printf("],\"timestamp_us\":%llu}",(unsigned long long)ts);
 }}
 printf("]}");
}

static void adapter_input(struct task*t){
 if(t->node!=stream_node||u32(t->node+0x7da18)!=2||u32(t->input+4)!=0)return;
 uint64_t ptr=u64(t->input+0x40);unsigned count=u32(t->input+0x48);
 struct hl_sample samples[2048];int result=HL_INVALID;
 if(ptr&&count&&count<=2048&&!readmem(ptr,samples,count*sizeof(samples[0])))
 result=hl_stream_input(&stream,u32(t->input),u32(t->input+4),samples,count);
 printf("{\"event\":\"adapter_input\",\"frame\":%u,\"sensor\":%u,\"result\":%d,\"reference\":%.9g}\n",u32(t->input),u32(t->input+4),result,stream.reference.reference_rad);fflush(stdout);
}
static void adapter_output(struct task*t){
 uint32_t frame=u32(t->output);struct output_record*o=&outputs[frame%HL_HISTORY];o->valid=0;
 if(t->node!=stream_node||u32(t->node+0x7da18)!=2||!u8(t->output+8)||!u8(t->output+0x30)||u8(t->output+0x54)||u8(t->output+0x44)!=1||u32(t->output+0x48)!=1||u32(t->output+0x3c))return;
 uint64_t ptr=u64(t->output+0x4c);
 if(!ptr||readmem(ptr,o->matrix,sizeof(o->matrix)))return;
 o->node=t->node;o->frame=frame;o->sensor=u32(t->input+4);o->width=u32(t->output+0x34);o->height=u32(t->output+0x38);o->valid=1;
}
static void capture_frame(struct task*t,const char*stage,int result){
 if(t->kind==1&&!strcmp(stage,"before"))adapter_input(t);
 if(t->kind==2&&!strcmp(stage,"after")&&result==0)adapter_output(t);
 printf("{\"event\":\"eis3_frame\",\"tid\":%d,\"mode\":%u,\"call\":\"%s\",\"stage\":\"%s\",\"result\":%d",t->tid,u32(t->node+0x7da18),t->kind==1?"distortion":"stabilize",stage,result);
 printf(",\"node\":\"%llx\",\"input_frame\":%u,\"input_sensor\":%u,\"output_frame\":%u",(unsigned long long)t->node,u32(t->input),u32(t->input+4),u32(t->output));
 if(t->kind==1&&!strcmp(stage,"before")){
 printf(",\"gyro\":");sample(u64(t->input+0x20),u32(t->input+0x28));
 printf(",\"accel\":");sample(u64(t->input+0x40),u32(t->input+0x48));
 printf(",\"gravity\":");sample(u64(t->input+0xa0),u32(t->input+0xa8));
 }
 if(!strcmp(stage,"after")){
 printf(",\"has_output\":%u,\"matrix_enable\":%u,\"output_prefix_hex\":",u8(t->output+8),u8(t->output+0x30));hexbytes(t->output,176);
 if(t->kind==2){
 printf(",\"grid_enable\":%u,\"alignment_enable\":%u,\"matrix_domain\":[%u,%u],\"matrix_rows\":%u,\"first_matrix\":",u8(t->output+0x54),u8(t->output+0xa4),u32(t->output+0x34),u32(t->output+0x38),u8(t->output+0x44));
 float matrix[9];uint64_t ptr=u64(t->output+0x4c);
 if(u8(t->output+8)&&u8(t->output+0x30)&&u8(t->output+0x44)&&ptr&&!readmem(ptr,matrix,sizeof(matrix))){
 putchar('[');for(unsigned i=0;i<9;i++)if(isfinite(matrix[i]))printf("%s%.9g",i?",":"",matrix[i]);else printf("%snull",i?",":"");putchar(']');
 }else printf("null");
 }

 }puts("}");fflush(stdout);
}


static void adapter_publish(struct task*t,uint64_t info){
 if(t->node!=stream_node||u32(t->node+0x7da18)!=2||u32(info)!=40||u32(info+0x14)!=3)return;
 uint64_t items=u64(info+0x20);if(!items||u32(items)!=48||u32(items+0x18)!=0x168)return;
 uint64_t request=u64(items+8),data=u64(items+0x10);if(request>UINT32_MAX||!data||u32(data)!=1||u32(data+8)!=1||u8(data+0xc)!=1)return;
 struct output_record*o=&outputs[request%HL_HISTORY];
 if(!o->valid||o->node!=t->node||o->frame!=request)return;
 float old[9];if(readmem(data+0x10,old,sizeof(old))||memcmp(old,o->matrix,sizeof(old)))return;
 if(u32(data+0x15c)!=o->width||u32(data+0x160)!=o->height||o->width!=2400||o->height!=1352)return;
 struct hl_result result;int status=hl_stream_centered_fixed(&stream,o->frame,o->sensor,o->width,o->height,0.7853981633974483,2.0,old,&result);
 int wrote=0;
 if(status==HL_OK&&apply_correction){
 uint64_t addr=(data+0x10)&0x00ffffffffffffffULL;
 if(pwrite(memfd,result.matrix,sizeof(result.matrix),addr)==sizeof(result.matrix)){
 float check[9];if(!readmem(addr,check,sizeof(check))&&!memcmp(check,result.matrix,sizeof(check))){wrote=1;corrections++;}
 }
 if(!wrote){pwrite(memfd,old,sizeof(old),addr);end=1;}
 }
 printf("{\"event\":\"adapter_publish\",\"request\":%llu,\"sensor\":%u,\"matched_matrix\":true,\"status\":%d,\"applied\":%s",(unsigned long long)request,o->sensor,status,wrote?"true":"false");
 if(status==HL_OK)printf(",\"correction_deg\":%.9g,\"zoom\":%.9g",result.correction_rad*57.29577951308232,result.required_zoom);
 puts("}");fflush(stdout);
}
static void capture_metadata(struct task*t,const char*stage,int result){
 if(!strcmp(stage,"before"))adapter_publish(t,t->metadata);
 uint64_t info=t->metadata;unsigned count=u32(info+0x14);uint64_t tags=u64(info+0x18),items=u64(info+0x20);
 printf("{\"event\":\"ica_publish\",\"stage\":\"%s\",\"result\":%d,\"node\":\"%llx\",\"mode\":%u,\"info_size\":%u,\"count\":%u,\"items\":[",stage,result,(unsigned long long)t->node,u32(t->node+0x7da18),u32(info),count);
 if(count&&count<=8&&tags&&items){for(unsigned i=0;i<count;i++){
 uint64_t item=items+48*i, data=u64(item+0x10);unsigned length=u32(item+0x18);
 printf("%s{\"tag\":%u,\"item_size\":%u,\"request\":%llu,\"length\":%u,\"data_prefix_hex\":",i?",":"",u32(tags+4*i),u32(item),(unsigned long long)u64(item+8),length);hexbytes(data,length<176?length:176);
 if(i==0&&length==0x168){printf(",\"perspective_enable\":%u,\"domain\":[%u,%u],\"matrix\":[",u32(data),u32(data+0x15c),u32(data+0x160));for(unsigned k=0;k<9;k++)printf("%s%.9g",k?",":"",flt(data+0x10+4*k));putchar(']');}
 putchar('}');
 }}
 puts("]}");fflush(stdout);
}
static int seize(int tid){struct task*t=add(tid);if(!t)return -1;if(ptrace(PTRACE_SEIZE,tid,0,(void*)PTRACE_O_TRACECLONE)){t->tid=0;return -1;}if(ptrace(PTRACE_INTERRUPT,tid,0,0)){return -1;}int st=0,r;do{r=waitpid(tid,&st,__WALL);}while(r<0&&errno==EINTR);if(r<0||!WIFSTOPPED(st)){t->tid=0;return -1;}t->stopped=1;if(configure(t,0)){perror("configure");return -1;}return 0;}
static uint64_t base_for(int pid){char p[128],l[1024],perm[8];snprintf(p,sizeof(p),"/proc/%d/maps",pid);FILE*f=fopen(p,"r");if(!f)return 0;unsigned long s,off;uint64_t b=0;while(fgets(l,sizeof(l),f))if(strstr(l,"/vendor/lib64/camera/components/com.qti.node.eisv3.so")&&sscanf(l,"%lx-%*lx %7s %lx",&s,perm,&off)==3&&off==0)b=s;fclose(f);return b;}
static int cleanup(){int count=0,fail=0;if(patchcode(0))fail++;printf("{\"event\":\"code_restored\",\"verified\":%s}\n",fail?"false":"true");for(int i=0;i<nt;i++){struct task*t=&tasks[i];if(!t->tid)continue;if(!t->stopped&&ptrace(PTRACE_INTERRUPT,t->tid,0,0)){if(errno==ESRCH)t->tid=0;else fail++;}}for(int i=0;i<nt;i++){struct task*t=&tasks[i];if(!t->tid)continue;if(!t->stopped){int st,r;do{r=waitpid(t->tid,&st,__WALL);}while(r<0&&errno==EINTR);if(r<0||!WIFSTOPPED(st))continue;t->stopped=1;}if(ptrace(PTRACE_DETACH,t->tid,0,(void*)(long)t->signal)){if(errno!=ESRCH)fail++;}else count++;}printf("{\"event\":\"detached\",\"threads\":%d,\"failures\":%d}\n",count,fail);fflush(stdout);return fail;}
int main(int argc,char**argv){if(argc!=2&&argc!=7)return 2;apply_correction=getenv("HORIZON_APPLY")&&!strcmp(getenv("HORIZON_APPLY"),"1");enable180=getenv("HORIZON_ENABLE_180")&& !strcmp(getenv("HORIZON_ENABLE_180"),"1");provider=atoi(argv[1]);int test=argc==7&&!strcmp(argv[2],"--test");uint64_t base=test?0:base_for(provider);if(!test&&!base)return 1;char p[128];snprintf(p,sizeof(p),"/proc/%d/mem",provider);memfd=open(p,O_RDWR);if(memfd<0){perror("mem");return 1;}uint64_t offsets[4]={0x4c580,0x44c94,0x44e50,0x49684};
 for(int i=0;i<4;i++){bp[2*i]=test?strtoull(argv[3+i],NULL,16):base+offsets[i];bp[2*i+1]=bp[2*i]+4;}
 const uint32_t calls[4]={0xd63f02e0,0xd63f0280,0xd63f0340,0xd63f02c0};
 const uint32_t returns[4]={0x340002e0,0x2a0003fc,0x34001140,0xf940bb36};
 for(int i=0;i<8;i++)if(readmem(bp[i],&original_code[i],4)||(!(i&1)&&original_code[i]!=calls[i/2])||(!test&&(i&1)&&original_code[i]!=returns[i/2])){fprintf(stderr,"Instruction mismatch at %d\n",i);return 1;}
 struct sigaction sa={0};sa.sa_handler=handler;sigemptyset(&sa.sa_mask);sigaction(SIGINT,&sa,NULL);sigaction(SIGTERM,&sa,NULL);sigaction(SIGALRM,&sa,NULL);int seconds=180;const char*duration=getenv("HORIZON_TRACE_SECONDS");if(duration&&atoi(duration)>0&&atoi(duration)<=180)seconds=atoi(duration);alarm(seconds);
 snprintf(p,sizeof(p),"/proc/%d/task",provider);DIR*d=opendir(p);if(!d)return 1;struct dirent*e;while((e=readdir(d))&&!end){int tid=atoi(e->d_name);if(tid>0&&seize(tid)&&errno!=ESRCH){end=1;break;}}closedir(d);
 // Keep every attached thread stopped until the entire thread group is seized.
 // Re-enumerate to catch children created before their parent was stopped.
 int added=1;
 while(!end&&added){added=0;d=opendir(p);if(!d){end=1;break;}
 while((e=readdir(d))&&!end){int tid=atoi(e->d_name);if(tid>0&&!find(tid)){if(seize(tid)&&errno!=ESRCH){end=1;break;}added=1;}}
 closedir(d);}
 if(!end&&patchcode(1))end=1;
 if(!end)for(int i=0;i<nt;i++){struct task*t=&tasks[i];if(t->tid&&t->stopped){if(ptrace(PTRACE_CONT,t->tid,0,0)){end=1;break;}t->stopped=0;}}
 printf("{\"event\":\"armed\",\"pid\":%d,\"threads\":%d,\"seconds\":%d,\"tracer_pid\":%d,\"method\":\"adapter_trace\"}\n",provider,nt,seconds,getpid());fflush(stdout);
 while(!end){int st,tid=waitpid(-1,&st,__WALL);if(tid<0){if(errno==EINTR)continue;break;}struct task*t=find(tid);if(!t)t=add(tid);if(!t){end=1;break;}if(WIFEXITED(st)||WIFSIGNALED(st)){t->tid=0;continue;}if(!WIFSTOPPED(st))continue;t->stopped=1;int sig=WSTOPSIG(st),event=(unsigned)st>>16;
 if(event==PTRACE_EVENT_CLONE){unsigned long child=0;ptrace(PTRACE_GETEVENTMSG,tid,0,&child);add(child);ptrace(PTRACE_CONT,tid,0,0);t->stopped=0;continue;}
 if(!t->configured){if(configure(t,1)){end=1;break;}ptrace(PTRACE_CONT,tid,0,0);t->stopped=0;continue;}
 if(t->step&&sig==SIGTRAP&&!event){t->step=0;if(steps)steps--;if(!steps&&patchcode(1)){end=1;break;}ptrace(PTRACE_CONT,tid,0,0);t->stopped=0;continue;}
 struct regs r;struct iovec v={&r,sizeof(r)};if(sig==SIGTRAP&&!event&&!ptrace(PTRACE_GETREGSET,tid,(void*)1,&v)){int hit=-1;for(int i=0;i<8;i++)if(r.pc==bp[i])hit=i;if(hit<0)goto deliver_signal;
 if(hit>=6){
 if(hit==6){t->node=r.x[19];t->metadata=r.x[0];capture_metadata(t,"before",-1);}else capture_metadata(t,"after",(int)r.x[0]);
 }else if(hit>=2){
 if(!(hit&1)){t->node=r.x[19];t->input=r.x[1];t->output=r.x[2];t->kind=hit/2;capture_frame(t,"before",-1);}
 else capture_frame(t,"after",(int)r.x[0]);
 }else if(r.pc==bp[0]){t->node=r.x[0]-0xc8;t->common=r.x[1];t->sensors=r.x[2];t->count=(unsigned)r.x[3];if(t->common==t->node+0x1180&&t->sensors==t->node+0x1200&&t->count>0&&t->count<=4){stream_node=t->node;hl_stream_reset(&stream,u32(t->node+0x7da18));memset(outputs,0,sizeof(outputs));capture(t,"stock_tuned",-1);if(enable180&&u32(t->node+0x7da18)==2){if(!u8(t->common+0x2b)){puts("{\"event\":\"acc_disabled_refusing_override\"}");end=1;break;}uint32_t old[4][2],values[2];float cap=180.0f;values[0]=1;memcpy(&values[1],&cap,4);unsigned i;for(i=0;i<t->count;i++){uint64_t addr=(t->sensors+i*0xe830+0xe61c)&0x00ffffffffffffffULL;if(readmem(addr,old[i],8))break;if(pwrite(memfd,values,8,addr)!=8)break;}if(i!=t->count){for(unsigned j=0;j<i;j++)pwrite(memfd,old[j],8,(t->sensors+j*0xe830+0xe61c)&0x00ffffffffffffffULL);puts("{\"event\":\"override_write_failed\"}");end=1;break;}capture(t,"before",-1);}else capture(t,"before",-1);}else{puts("{\"event\":\"layout_mismatch\"}");t->count=0;}}
 else if(t->count)capture(t,"after",(int)r.x[0]);
 if(patchcode(0)||ptrace(PTRACE_SINGLESTEP,tid,0,0)){end=1;break;}steps++;t->step=1;t->stopped=0;continue;}
 deliver_signal:;int deliver=event?0:sig;t->signal=deliver;if(ptrace(PTRACE_CONT,tid,0,(void*)(long)deliver)){end=1;break;}t->signal=0;t->stopped=0;
 }
 int failed=cleanup();close(memfd);return failed?1:0;}
