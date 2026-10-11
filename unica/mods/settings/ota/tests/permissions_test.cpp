// SPDX-License-Identifier: GPL-3.0-or-later
#include <unistd.h>
#include <cassert>
#include <cerrno>
// Host tests do not need root; verify the requested ownership separately.
static bool denyOwnership=false;
static int setOwnership(int,uid_t user,gid_t group){
 assert(user==0&&group==1000);
 if(denyOwnership){errno=EPERM;return -1;}return 0;
}
#define fchown setOwnership
#define main otaWorkerMain
#include "../native/bridge.cpp"
#undef main
#undef fchown
int main(int argc,char** argv){
 assert(argc==2);umask(077);std::string path=std::string(argv[1])+"/response";
 writeFile(path.c_str(),"123 ready\n");struct stat st{};
 assert(stat(path.c_str(),&st)==0&&(st.st_mode&0777)==0640);
 assert(chmod(path.c_str(),0600)==0);
 writeFile(path.c_str(),"124 ready\n");
 assert(stat(path.c_str(),&st)==0&&(st.st_mode&0777)==0640);
 denyOwnership=true;bool rejected=false;
 try{writeFile(path.c_str(),"125 ready\n");}catch(const std::runtime_error&){rejected=true;}
 assert(rejected);puts("PASS: response readable by system group under umask 077, old modes repaired, ownership failure rejected");
}
