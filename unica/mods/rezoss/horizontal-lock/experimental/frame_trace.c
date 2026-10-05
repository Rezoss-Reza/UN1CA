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
struct dbg {uint32_t info,pad;struct{uint64_t addr;uint32_t ctrl,pad;}r[16];};
struct regs {uint64_t x[31],sp,pc,pstate;};
struct task {int tid,stopped,configured,step,signal;struct dbg original;uint64_t node,common,sensors,input,output;unsigned count,kind;};
static uint32_t original_code[6];static unsigned steps;static int enable180;static struct task tasks[2048];static int nt,memfd,provider;static uint64_t bp[6];static volatile sig_atomic_t end;
static void handler(int s){(void)s;end=1;}
static struct task *find(int tid){for(int i=0;i<nt;i++)if(tasks[i].tid==tid)return &tasks[i];return NULL;}
static struct task *add(int tid){struct task*t=find(tid);if(t)return t;if(nt==2048)return NULL;t=&tasks[nt++];t->tid=tid;return t;}
static int readmem(uint64_t a,void *v,size_t n){return pread(memfd,v,n,a&0x00ffffffffffffffULL)==(ssize_t)n?0:-1;}
static uint32_t u32(uint64_t a){uint32_t v=0;readmem(a,&v,4);return v;}
static unsigned u8(uint64_t a){unsigned char v=0;readmem(a,&v,1);return v;}
static double flt(uint64_t a){float v=0;readmem(a,&v,4);return isfinite(v)?v:0;}
static int patchcode(int enable){uint32_t trap=0xd4200000;for(int i=0;i<6;i++){uint32_t v=enable?trap:original_code[i];if(pwrite(memfd,&v,4,bp[i])!=4)return -1;uint32_t ck;if(readmem(bp[i],&ck,4)||ck!=v)return -1;}return 0;}
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
static void capture_frame(struct task*t,const char*stage,int result){
 printf("{\"event\":\"eis3_frame\",\"tid\":%d,\"mode\":%u,\"call\":\"%s\",\"stage\":\"%s\",\"result\":%d",t->tid,u32(t->node+0x7da18),t->kind==1?"distortion":"stabilize",stage,result);
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
static int seize(int tid){struct task*t=add(tid);if(!t)return -1;if(ptrace(PTRACE_SEIZE,tid,0,(void*)PTRACE_O_TRACECLONE)){t->tid=0;return -1;}if(ptrace(PTRACE_INTERRUPT,tid,0,0)){return -1;}int st=0,r;do{r=waitpid(tid,&st,__WALL);}while(r<0&&errno==EINTR);if(r<0||!WIFSTOPPED(st)){t->tid=0;return -1;}t->stopped=1;if(configure(t,0)){perror("configure");return -1;}ptrace(PTRACE_CONT,tid,0,0);t->stopped=0;return 0;}
static uint64_t base_for(int pid){char p[128],l[1024],perm[8];snprintf(p,sizeof(p),"/proc/%d/maps",pid);FILE*f=fopen(p,"r");if(!f)return 0;unsigned long s,off;uint64_t b=0;while(fgets(l,sizeof(l),f))if(strstr(l,"/vendor/lib64/camera/components/com.qti.node.eisv3.so")&&sscanf(l,"%lx-%*lx %7s %lx",&s,perm,&off)==3&&off==0)b=s;fclose(f);return b;}
static int cleanup(){int count=0,fail=0;if(patchcode(0))fail++;printf("{\"event\":\"code_restored\",\"verified\":%s}\n",fail?"false":"true");for(int i=0;i<nt;i++){struct task*t=&tasks[i];if(!t->tid)continue;if(!t->stopped&&ptrace(PTRACE_INTERRUPT,t->tid,0,0)){if(errno==ESRCH)t->tid=0;else fail++;}}for(int i=0;i<nt;i++){struct task*t=&tasks[i];if(!t->tid)continue;if(!t->stopped){int st,r;do{r=waitpid(t->tid,&st,__WALL);}while(r<0&&errno==EINTR);if(r<0||!WIFSTOPPED(st))continue;t->stopped=1;}if(ptrace(PTRACE_DETACH,t->tid,0,(void*)(long)t->signal)){if(errno!=ESRCH)fail++;}else count++;}printf("{\"event\":\"detached\",\"threads\":%d,\"failures\":%d}\n",count,fail);fflush(stdout);return fail;}
int main(int argc,char**argv){if(argc!=2&&argc!=6)return 2;enable180=getenv("HORIZON_ENABLE_180")&& !strcmp(getenv("HORIZON_ENABLE_180"),"1");provider=atoi(argv[1]);int test=argc==6&&!strcmp(argv[2],"--test");uint64_t base=test?0:base_for(provider);if(!test&&!base)return 1;char p[128];snprintf(p,sizeof(p),"/proc/%d/mem",provider);memfd=open(p,O_RDWR);if(memfd<0){perror("mem");return 1;}uint64_t offsets[3]={0x4c580,0x44c94,0x44e50};
 for(int i=0;i<3;i++){bp[2*i]=test?strtoull(argv[3+i],NULL,16):base+offsets[i];bp[2*i+1]=bp[2*i]+4;}
 const uint32_t calls[3]={0xd63f02e0,0xd63f0280,0xd63f0340};
 const uint32_t returns[3]={0x340002e0,0x2a0003fc,0x34001140};
 for(int i=0;i<6;i++)if(readmem(bp[i],&original_code[i],4)||(!(i&1)&&original_code[i]!=calls[i/2])||(!test&&(i&1)&&original_code[i]!=returns[i/2])){fprintf(stderr,"Instruction mismatch at %d\n",i);return 1;}
 struct sigaction sa={0};sa.sa_handler=handler;sigemptyset(&sa.sa_mask);sigaction(SIGINT,&sa,NULL);sigaction(SIGTERM,&sa,NULL);sigaction(SIGALRM,&sa,NULL);int seconds=180;const char*duration=getenv("HORIZON_TRACE_SECONDS");if(duration&&atoi(duration)>0&&atoi(duration)<=180)seconds=atoi(duration);alarm(seconds);
 snprintf(p,sizeof(p),"/proc/%d/task",provider);DIR*d=opendir(p);if(!d)return 1;struct dirent*e;while((e=readdir(d))&&!end){int tid=atoi(e->d_name);if(tid>0&&seize(tid)&&errno!=ESRCH){end=1;break;}}closedir(d);if(!end&&patchcode(1))end=1;printf("{\"event\":\"armed\",\"pid\":%d,\"threads\":%d,\"seconds\":%d,\"tracer_pid\":%d,\"method\":\"frame_trace\"}\n",provider,nt,seconds,getpid());fflush(stdout);
 while(!end){int st,tid=waitpid(-1,&st,__WALL);if(tid<0){if(errno==EINTR)continue;break;}struct task*t=find(tid);if(!t)t=add(tid);if(!t){end=1;break;}if(WIFEXITED(st)||WIFSIGNALED(st)){t->tid=0;continue;}if(!WIFSTOPPED(st))continue;t->stopped=1;int sig=WSTOPSIG(st),event=(unsigned)st>>16;
 if(event==PTRACE_EVENT_CLONE){unsigned long child=0;ptrace(PTRACE_GETEVENTMSG,tid,0,&child);add(child);ptrace(PTRACE_CONT,tid,0,0);t->stopped=0;continue;}
 if(!t->configured){if(configure(t,1)){end=1;break;}ptrace(PTRACE_CONT,tid,0,0);t->stopped=0;continue;}
 if(t->step&&sig==SIGTRAP&&!event){t->step=0;if(steps)steps--;if(!steps&&patchcode(1)){end=1;break;}ptrace(PTRACE_CONT,tid,0,0);t->stopped=0;continue;}
 struct regs r;struct iovec v={&r,sizeof(r)};if(sig==SIGTRAP&&!event&&!ptrace(PTRACE_GETREGSET,tid,(void*)1,&v)){int hit=-1;for(int i=0;i<6;i++)if(r.pc==bp[i])hit=i;if(hit<0)goto deliver_signal;
 if(hit>=2){
 if(!(hit&1)){t->node=r.x[19];t->input=r.x[1];t->output=r.x[2];t->kind=hit/2;capture_frame(t,"before",-1);}
 else capture_frame(t,"after",(int)r.x[0]);
 }else if(r.pc==bp[0]){t->node=r.x[0]-0xc8;t->common=r.x[1];t->sensors=r.x[2];t->count=(unsigned)r.x[3];if(t->common==t->node+0x1180&&t->sensors==t->node+0x1200&&t->count>0&&t->count<=4){capture(t,"stock_tuned",-1);if(enable180&&u32(t->node+0x7da18)==2){if(!u8(t->common+0x2b)){puts("{\"event\":\"acc_disabled_refusing_override\"}");end=1;break;}uint32_t old[4][2],values[2];float cap=180.0f;values[0]=1;memcpy(&values[1],&cap,4);unsigned i;for(i=0;i<t->count;i++){uint64_t addr=(t->sensors+i*0xe830+0xe61c)&0x00ffffffffffffffULL;if(readmem(addr,old[i],8))break;if(pwrite(memfd,values,8,addr)!=8)break;}if(i!=t->count){for(unsigned j=0;j<i;j++)pwrite(memfd,old[j],8,(t->sensors+j*0xe830+0xe61c)&0x00ffffffffffffffULL);puts("{\"event\":\"override_write_failed\"}");end=1;break;}capture(t,"before",-1);}else capture(t,"before",-1);}else{puts("{\"event\":\"layout_mismatch\"}");t->count=0;}}
 else if(t->count)capture(t,"after",(int)r.x[0]);
 if(patchcode(0)||ptrace(PTRACE_SINGLESTEP,tid,0,0)){end=1;break;}steps++;t->step=1;t->stopped=0;continue;}
 deliver_signal:;int deliver=event?0:sig;t->signal=deliver;if(ptrace(PTRACE_CONT,tid,0,(void*)(long)deliver)){end=1;break;}t->signal=0;t->stopped=0;
 }
 int failed=cleanup();close(memfd);return failed?1:0;}
