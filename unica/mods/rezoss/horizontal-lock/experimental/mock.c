#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <string.h>
static volatile sig_atomic_t go;
static void start(int sig){(void)sig;go=1;}
extern char init_site[],dist_site[],stab_site[];
extern int wrap_init(void*,void*,void*,unsigned,void*,void*);
extern int wrap_dist(void*,void*,void*,void*,void*);
extern int wrap_stab(void*,void*,void*,void*,void*);
__asm__(".text\n.align 2\n.global wrap_init\nwrap_init:\nstp x19,x30,[sp,#-32]!\nstp x23,x20,[sp,#16]\nmov x23,x4\nmov x19,x5\n.global init_site\ninit_site: blr x23\nnop\nldp x23,x20,[sp,#16]\nldp x19,x30,[sp],#32\nret\n"
".global wrap_dist\nwrap_dist:\nstp x19,x30,[sp,#-32]!\nstp x20,x26,[sp,#16]\nmov x20,x3\nmov x19,x4\n.global dist_site\ndist_site: blr x20\nnop\nldp x20,x26,[sp,#16]\nldp x19,x30,[sp],#32\nret\n"
".global wrap_stab\nwrap_stab:\nstp x19,x30,[sp,#-32]!\nstp x20,x26,[sp,#16]\nmov x26,x3\nmov x19,x4\n.global stab_site\nstab_site: blr x26\nnop\nldp x20,x26,[sp,#16]\nldp x19,x30,[sp],#32\nret\n");
static int init(void*a,void*b,void*c,unsigned n){(void)a;(void)b;(void)c;(void)n;return 0;}
static int process(void*h,void*input,unsigned char*out){(void)h;(void)input;out[8]=1;out[0x30]=1;out[0x54]=1;out[0xa4]=1;out[0x44]=1;uint32_t domain[2]={2400,1352};memcpy(out+0x34,domain,8);static float matrix[9]={1,2,3,4,5,6,7,8,9};void*ptr=matrix;memcpy(out+0x4c,&ptr,8);return 0;}
int main(int argc,char**argv){unsigned mode=argc>1?atoi(argv[1]):2;unsigned char*node=calloc(1,0x80000),input[0x180]={0},output[176]={0};double samples[14]={1,2,3,4,5,6,0,7,8,9,10,11,12,0};uint64_t ts=1000;memcpy((char*)samples+48,&ts,8);ts=2000;memcpy((char*)samples+104,&ts,8);
*(uint32_t*)(node+0x7da18)=mode;node[0x11ab]=1;*(uint32_t*)(node+0xf10)=1;
for(int i=0;i<4;i++){unsigned char*s=node+0x1200+i*0xe830;*(uint32_t*)(s+0xe61c)=i&1;*(float*)(s+0xe620)=i*10.f;}
int offsets[]={0x20,0x40,0xa0};for(int i=0;i<3;i++){void*ptr=samples;memcpy(input+offsets[i],&ptr,8);uint32_t count=2;memcpy(input+offsets[i]+8,&count,4);}
printf("pid=%d init=%p dist=%p stab=%p\n",getpid(),init_site,dist_site,stab_site);fflush(stdout);signal(SIGUSR1,start);while(!go)pause();
for(int i=0;i<8;i++){wrap_init(node+0xc8,node+0x1180,node+0x1200,4,init,node);wrap_dist(node,input,output,process,node);wrap_stab(node,input,output,process,node);usleep(100000);}sleep(30);return 0;}
