// SPDX-License-Identifier: GPL-3.0-or-later
// Fixed OTA staging worker: no caller-selected paths, shell or partition writes.
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/file.h>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <cerrno>
static const char* source="/data/misc/unica_ota/request/update.zip";
static const char* staged="/data/misc/unica_ota/staged/update.zip";
void writeFile(const char* p,const std::string& s){int f=open(p,O_WRONLY|O_CREAT|O_TRUNC|O_NOFOLLOW|O_CLOEXEC,0600);if(f<0)throw std::runtime_error("Cannot write recovery request");if(fchown(f,0,1000)||fchmod(f,0640)){close(f);throw std::runtime_error("Cannot set recovery request permissions");}if(write(f,s.data(),s.size())!=(ssize_t)s.size()||fsync(f)){close(f);throw std::runtime_error("Cannot sync request");}close(f);}
int main(){
 int lock=open("/data/misc/unica_ota/staged/lock",O_CREAT|O_RDWR|O_NOFOLLOW,0600);if(lock<0||flock(lock,LOCK_EX|LOCK_NB))return 1;
 bool queued=false; std::string id;
 try{
  int request=open("/data/misc/unica_ota/request/id",O_RDONLY|O_NOFOLLOW); char token[32]{}; int count=request<0?-1:read(request,token,31); if(request>=0)close(request); if(count<1)throw std::runtime_error("Missing request ID"); id=std::string(token,count); if(id.size()>20||id.find_first_not_of("0123456789")!=std::string::npos)throw std::runtime_error("Invalid request ID");
  struct stat rootstat{},cachestat{}; if(stat("/",&rootstat)||stat("/cache",&cachestat)||rootstat.st_dev==cachestat.st_dev)throw std::runtime_error("Recovery cache is not mounted");
  if(access("/cache/recovery/openrecoveryscript",F_OK)==0||access("/cache/recovery/command",F_OK)==0||access("/cache/unica-ota/auto",F_OK)==0)throw std::runtime_error("Another recovery request exists");
  int in=open(source,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);struct stat st{};
  if(in<0||fstat(in,&st)||!S_ISREG(st.st_mode)||st.st_uid!=1000||st.st_size<4096||st.st_size>16LL*1024*1024*1024)throw std::runtime_error("Invalid staged package");
  struct statvfs space{};if(statvfs("/data",&space)||space.f_bavail*space.f_frsize<(unsigned long long)st.st_size+64*1024*1024)throw std::runtime_error("Insufficient staging space");
  int out=open("/data/misc/unica_ota/staged/update.zip.tmp",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(out<0)throw std::runtime_error("Staging file exists");
  char buffer[1024*1024];ssize_t n;while((n=read(in,buffer,sizeof(buffer)))>0){ssize_t pos=0;while(pos<n){ssize_t m=write(out,buffer+pos,n-pos);if(m<=0)throw std::runtime_error("Staging write failed");pos+=m;}}
  if(n<0||fsync(out))throw std::runtime_error("Staging read failed");close(in);close(out);
  if(rename("/data/misc/unica_ota/staged/update.zip.tmp",staged))throw std::runtime_error("Cannot publish staged ZIP");
  writeFile("/cache/unica-ota/auto","pending\n");
  writeFile("/cache/recovery/openrecoveryscript.unica.tmp","install /data/misc/unica_ota/staged/update.zip\n");
  // hard link provides no-clobber publication; never overwrite another request.
  if(link("/cache/recovery/openrecoveryscript.unica.tmp","/cache/recovery/openrecoveryscript"))throw std::runtime_error("Recovery request raced");
  queued=true;unlink("/cache/recovery/openrecoveryscript.unica.tmp");sync();
  writeFile("/data/misc/unica_ota/staged/response",id+" ready\n");return 0;
 }catch(const std::exception& e){if(!queued){unlink("/cache/unica-ota/auto");unlink("/cache/recovery/openrecoveryscript.unica.tmp");unlink("/data/misc/unica_ota/staged/update.zip.tmp");}try{writeFile("/data/misc/unica_ota/staged/response",id+" error: "+e.what()+"\n");}catch(...){}return 1;}
}
