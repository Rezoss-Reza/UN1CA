// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the production transaction/ZIP/IPC code using regular temporary
// files. GPU_TEST removes all real Android partition paths and environment I/O.
#define GPU_TEST
#define main gpu_worker_main
#include "../native/worker.cpp"
#undef main
#include <filesystem>
#include <iostream>
static unsigned checks=0,serial=0;
static void check(bool b){require(b,"Transaction test assertion failed");++checks;}
static int request(const std::string& op,const std::string& payload=""){
 char id[40];snprintf(id,sizeof(id),"00000000-0000-0000-0000-%012u",++serial);
 atomic(IPC+"requests/request",std::string(id)+" "+op+" "+payload);return service();
}
static std::string profile(const std::string& h,uint32_t top=700000000){std::string s=h;for(int i=0;i<10;i++)s+=" "+std::to_string(i?STOCK_FREQ[i]:top)+" "+std::to_string(STOCK_LEVEL[i]);return s;}
int main(int argc,char** argv){try{
 require(argc==3,"transaction-test original.img temporary-directory");BASE=std::string(argv[2])+"/backup/";IPC=std::string(argv[2])+"/ipc/";
 std::filesystem::create_directories(BASE);std::filesystem::create_directories(IPC+"requests");std::filesystem::create_directories(IPC+"responses");
 auto b=read(argv[1],IMAGE_SIZE);auto h=sha(b);atomic(BASE+"partition.img",b);
 check(request("status")==0);check(sha(current())==h);
 check(request("apply",profile(h))!=0);check(sha(current())==h);
 check(request("backup",h)==0);check(sha(original())==h);check(exists(BASE+"rollback.zip"));
 check(request("apply",profile(h))!=0);check(sha(current())==h);
 check(request("exported",h+" "+std::string(64,'0'))!=0);
 auto zh=text(BASE+"rollback.sha256",64);check(request("exported",h+" "+zh)==0);
 check(request("apply",profile(std::string(64,'0')))!=0);check(sha(current())==h);
 check(request("apply",profile(h,900000000))!=0);check(sha(current())==h);
 check(request("apply",profile(h))==0);auto tuned=current();check(sha(tuned)!=h);check(table(tuned).frequencies[0]==700000000);check(sha(original())==h);check(!exists(BASE+"writing"));
 // Duplicate delivery of the identical UUID cannot replay the completed write.
 auto done=sha(tuned);check(service()==0);check(sha(current())==done);
 check(request("backup",done)!=0);check(sha(original())==h);
 check(request("restore",done)==0);check(sha(current())==h);
 // A write failure automatically attempts the exact original, never a preset.
 failWrites=1;check(request("apply",profile(h))!=0);check(sha(current())==h);check(!exists(BASE+"writing"));
 // Simulate both initial write and immediate rollback losing power/failing.
 failWrites=2;check(request("apply",profile(h))!=0);check(exists(BASE+"writing"));check(sha(current())!=h);
 check(request("status")==0);auto reply=text(IPC+"responses/response.json");check(reply.find("\"interrupted\":true")!=std::string::npos);check(reply.find("\"canRestore\":true")!=std::string::npos);
 auto partial=sha(current());check(request("apply",profile(partial))!=0);check(sha(current())==partial);
 check(request("restore",partial)==0);check(sha(current())==h);check(!exists(BASE+"writing"));
 // A new/externally modified image must not be overwritten by a stale backup.
 auto other=b;other[8192]^=1;atomic(BASE+"partition.img",other);auto foreign=sha(other);check(request("status")==0);check(text(IPC+"responses/response.json").find("\"canRestore\":false")!=std::string::npos);
 check(request("restore",foreign)!=0);check(request("apply",profile(foreign))!=0);check(sha(current())==foreign);check(sha(original())==h);
 // Backup corruption must be detected before touching the partition.
 atomic(BASE+"partition.img",b);auto broken=b;broken[9000]^=1;atomic(BASE+"original.img",broken);check(request("apply",profile(h))!=0);check(request("restore",h)!=0);check(sha(current())==h);atomic(BASE+"original.img",b);
 std::cout<<"PASS "<<checks<<" backup, export, stale-image, replay and interrupted-write assertions\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
