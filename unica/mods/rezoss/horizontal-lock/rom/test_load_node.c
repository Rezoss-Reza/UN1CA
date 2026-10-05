// SPDX-License-Identifier: GPL-3.0-or-later
#define _GNU_SOURCE
#include <dlfcn.h>
#include <android/dlext.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <assert.h>
static unsigned calls;
static int init(void *a,void *b,void *c,unsigned n){assert(a&&b&&c&&n==4);calls++;return 37;}
static int algo(void *a,void *b,void *c){assert(a&&b&&c);calls++;return 29;}
static int meta(void *a){assert(a);calls++;return 23;}
extern int call_init(void *,void *,void *,unsigned,void *,void *);
extern int call_algo(void *,void *,void *,void *,void *,void *);
extern int call_meta(void *,void *,void *,void *);
__asm__(".text\n.global call_init\ncall_init:\nstp x19,x30,[sp,#-32]!\nstp x23,x20,[sp,#16]\nmov x23,x4\nmov x20,x5\nblr x20\nldp x23,x20,[sp,#16]\nldp x19,x30,[sp],#32\nret\n"
".global call_algo\ncall_algo:\nstp x19,x30,[sp,#-48]!\nstp x20,x26,[sp,#16]\nstp x22,x21,[sp,#32]\nmov x20,x3\nmov x26,x3\nmov x22,x3\nmov x19,x4\nmov x21,x5\nblr x21\nldp x22,x21,[sp,#32]\nldp x20,x26,[sp,#16]\nldp x19,x30,[sp],#48\nret\n"
".global call_meta\ncall_meta:\nstp x19,x30,[sp,#-32]!\nstp x22,x20,[sp,#16]\nmov x22,x1\nmov x19,x2\nmov x20,x3\nblr x20\nldp x22,x20,[sp,#16]\nldp x19,x30,[sp],#32\nret\n");
int main(int argc,char **argv){if(argc!=3)return 2;
 void *lib=dlopen("libdl_android.so",RTLD_NOW);if(!lib){puts(dlerror());return 1;}
 void *(*getns)(const char *)=dlsym(lib,"android_get_exported_namespace");
 void *(*create)(const char *,const char *,const char *,uint64_t,const char *,void *)=dlsym(lib,"android_create_namespace");
 int (*linkns)(void *,void *,const char *)=dlsym(lib,"android_link_namespaces");
 void *parent=getns("sphal");if(!parent){puts("no sphal namespace");return 1;}
 void *ns=create("unica_hl_test",NULL,"/data/local/tmp:/vendor/lib64:/vendor/lib64/camera/components",1,"/data/local/tmp:/vendor:/apex",parent);
 if(!ns||!linkns(ns,parent,argv[2])||!linkns(ns,getns("vndk"),argv[2])||!linkns(ns,NULL,argv[2])){puts(dlerror());return 1;}
 android_dlextinfo ext={0};ext.flags=ANDROID_DLEXT_USE_NAMESPACE;ext.library_namespace=ns;
 void *h=android_dlopen_ext(argv[1],RTLD_NOW|RTLD_LOCAL,&ext);if(!h){fprintf(stderr,"dlopen: %s\n",dlerror());return 1;}
 void (*entry)(void *)=dlsym(h,"ChiNodeEntry");if(!entry)return 3;unsigned char callbacks[512]={0};uint32_t size=sizeof(callbacks);memcpy(callbacks,&size,4);entry(callbacks);printf("PASS node loaded through vendor namespace and ChiNodeEntry returned\n");Dl_info d;assert(dladdr(entry,&d));uintptr_t base=(uintptr_t)d.dli_fbase;
 unsigned char *node=calloc(1,0x80000),in[512]={0},out[512]={0};assert(node);
 assert(call_init(node+0xc8,node+0x1180,node+0x1200,4,init,(void *)(base+0x15c000))==37);
 unsigned indices[]={1,2,4,5};for(unsigned i=0;i<4;i++)assert(call_algo(node,in,out,algo,node,(void *)(base+0x15c000+indices[i]*32))==29);
 assert(call_meta(in,meta,node,(void *)(base+0x15c000+3*32))==23);assert(calls==6);
 puts("PASS all six ELF trampolines preserve original call ABI and return values");
 free(node);dlclose(h);return 0;}
