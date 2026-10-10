// SPDX-License-Identifier: GPL-3.0-or-later
#include <linux/input.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <dirent.h>
#include <chrono>
#include <vector>
#include <string>
#include <sys/ioctl.h>
int main() {
 std::vector<pollfd> fds; DIR* dir=opendir("/dev/input"); if(!dir)return 3;
 while(auto* e=readdir(dir)) {
  if(std::string(e->d_name).find("event")!=0)continue;
  int fd=open((std::string("/dev/input/")+e->d_name).c_str(),O_RDONLY|O_NONBLOCK|O_CLOEXEC); if(fd<0)continue;
  unsigned long keys[(KEY_MAX+8*sizeof(unsigned long))/(8*sizeof(unsigned long))]{};
  if(ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(keys)),keys)<0 || !(keys[KEY_VOLUMEDOWN/(8*sizeof(unsigned long))]&(1UL<<(KEY_VOLUMEDOWN%(8*sizeof(unsigned long)))))){close(fd);continue;}
  unsigned long held[sizeof(keys)/sizeof(keys[0])]{};
  if(ioctl(fd,EVIOCGKEY(sizeof(held)),held)==0 && (held[KEY_VOLUMEDOWN/(8*sizeof(unsigned long))]&(1UL<<(KEY_VOLUMEDOWN%(8*sizeof(unsigned long)))))){closedir(dir);return 2;}
  fds.push_back({fd,POLLIN,0});
 }
 closedir(dir); if(fds.empty())return 3;
 auto end=std::chrono::steady_clock::now()+std::chrono::seconds(5);
 while(std::chrono::steady_clock::now()<end) {
  int left=std::chrono::duration_cast<std::chrono::milliseconds>(end-std::chrono::steady_clock::now()).count();
  if(poll(fds.data(),fds.size(),left)<0)return 3;
  for(auto& f:fds)if(f.revents&POLLIN){input_event e;while(read(f.fd,&e,sizeof(e))==sizeof(e))if(e.type==EV_KEY&&e.code==KEY_VOLUMEDOWN&&e.value!=0)return 2;}
 }
 return 0;
}
