// Copyright (c) 2026 Rezoss
// SPDX-License-Identifier: GPL-3.0-or-later
#include "vendor/property_virtualizer.hpp"
#include <sys/system_properties.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <cassert>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>
struct prop_info { uint32_t serial; char value[92]; char name[128]; };
static char *area;
static std::vector<prop_info *> properties;
static bool forceVerificationFailure;
extern "C" int __android_log_print(int,const char *,const char *,...) { return 0; }
extern "C" const prop_info *__system_property_find(const char *name) {
    for(auto *p:properties) if(!strcmp(name,p->name)) return p; return nullptr;
}
extern "C" void __system_property_read_callback(const prop_info *p,
    void (*callback)(void *,const char *,const char *,uint32_t),void *cookie) {
    const char *value=(p->serial&(1U<<16))?area+3500:p->value;
    callback(cookie,p->name,value,p->serial);
}
extern "C" int __system_property_foreach(void (*callback)(const prop_info *,void *),void *cookie) {
    for(auto *p:properties) callback(p,cookie); return 0;
}
extern "C" int __system_property_get(const char *name,char *value) {
    auto *p=__system_property_find(name); if(!p) { *value=0; return 0; }
    if(forceVerificationFailure) { strcpy(value,"wrong-value"); return strlen(value); }
    strcpy(value,p->value); return strlen(value);
}
static int fdCount() { int n=0; for(auto &p:std::filesystem::directory_iterator("/proc/self/fd")) { (void)p; ++n; } return n; }
int main(int argc,char **argv) {
    assert(argc==3); std::string mode=argv[1];
    std::string file=std::string(argv[2])+"/identity_area";
    int fd=open(file.c_str(),O_RDWR|O_CREAT|O_TRUNC,0600); assert(fd>=0);
    assert(ftruncate(fd,4096)==0);
    area=static_cast<char *>(mmap(nullptr,4096,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0)); assert(area!=MAP_FAILED);
    auto *header=reinterpret_cast<uint32_t *>(area);
    header[0]=4096; header[2]=mode=="invalid"?0:0x504f5250;
    auto add=[&](const char *key,const char *value) {
        auto *p=reinterpret_cast<prop_info *>(area+128+properties.size()*256);
        strcpy(p->name,key); strcpy(p->value,value); p->serial=(strlen(value)<<24)|6;
        properties.push_back(p); return p;
    };
    auto *model=add("ro.product.model","SM-S918B");
    auto *api=add("ro.product.first_api_level","33");
    auto *board=add(mode=="oversize"?"ro.unrelated.board":"ro.board.first_api_level","33");
    auto *unrelated=add("ro.unrelated.value","untouched");
    auto *fingerprint=add("ro.build.fingerprint","long-value-indirection");
    std::string longValue(140,'S'); strcpy(area+3500,longValue.c_str());
    fingerprint->serial=(1U<<16)|6;
    assert(msync(area,4096,MS_SYNC)==0);
    assert(mprotect(area,4096,PROT_READ)==0);
    std::vector<char> original(4096); assert(pread(fd,original.data(),4096,0)==4096);
    pif::Config cfg;
    cfg.propMap["MODEL"]=mode=="oversize"?std::string(93,'X'):"Pixel 6a";
    if(mode!="oversize") { cfg.deviceInitialSdkInt="32"; cfg.propMap["FINGERPRINT"]="google/bluejay_beta/bluejay:CANARY/BUILD/123:user/release-keys"; }
    forceVerificationFailure=mode=="rollback";
    int before=fdCount(); bool ok=pif::property_virtualizer::install(cfg); assert(fdCount()==before);
    assert(ok==(mode=="success"));
    std::vector<char> after(4096); assert(pread(fd,after.data(),4096,0)==4096); assert(after==original);
    assert(!strcmp(unrelated->value,"untouched"));
    if(ok) {
        assert(!strcmp(model->value,"Pixel 6a")); assert(!strcmp(api->value,"32"));
        assert(!strcmp(board->value,"32"));
        assert(!strcmp(fingerprint->value,cfg.propMap["FINGERPRINT"].c_str()));
        assert(!(fingerprint->serial&(1U<<16))); assert(area[3500]==0);
        assert((model->serial&0xffffff)==6); // dirty/counter bits restored
        // Forked reader verifies file-backed global state is still original.
        auto *global=static_cast<char *>(mmap(nullptr,4096,PROT_READ,MAP_SHARED,fd,0)); assert(global!=MAP_FAILED);
        assert(!memcmp(global,original.data(),4096)); munmap(global,4096);
    } else assert(!memcmp(area,original.data(),4096));
    munmap(area,4096); close(fd); unlink(file.c_str());
}
