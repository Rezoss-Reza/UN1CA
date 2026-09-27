// SPDX-License-Identifier: GPL-3.0-or-later
// Fixed-control init worker: no Binder, shell execution, networking or dynamic code.
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
using std::string; using std::vector;
static const string base="/data/misc/unica_battery/";
static string readFile(const string& path,size_t limit=8192) {
 int fd=open(path.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW); if(fd<0)throw std::runtime_error("Cannot read control");
 string out; char buf[4096]; ssize_t n;
 while((n=read(fd,buf,sizeof(buf)))>0){out.append(buf,n);if(out.size()>limit){close(fd);throw std::runtime_error("Input too large");}}
 int err=errno;close(fd);if(n<0)throw std::runtime_error(strerror(err));
 while(!out.empty()&&(out.back()=='\n'||out.back()=='\r'||out.back()==' '))out.pop_back();return out;
}
static bool number(const string& s) {return !s.empty()&&s.size()<=10&&s.find_first_not_of("0123456789")==string::npos;}
static bool uuid(const string& s) {if(s.size()!=36)return false;for(size_t i=0;i<s.size();i++)if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;return true;}
static vector<string> words(string s){std::istringstream in(s);vector<string> out;string w;while(in>>w)out.push_back(w);return out;}
static string quote(const string& s){string out="\"";for(unsigned char c:s){if(c=='"'||c=='\\')out+='\\';if(c>=32&&c<127)out+=c;}return out+'"';}
static string jsonArray(const vector<string>& a){string s="[";for(size_t i=0;i<a.size();i++){if(i)s+=',';s+=quote(a[i]);}return s+"]";}
static bool cpuId(const string& id){string prefix="cpu_policy";return id.compare(0,prefix.size(),prefix)==0&&number(id.substr(prefix.size()))&&id.size()<=prefix.size()+3;}
static string pathFor(const string& id){
 if(cpuId(id))return "/sys/devices/system/cpu/cpufreq/"+id.substr(4)+"/scaling_max_freq";
 if(id=="gpu")return "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq";
 if(id=="aspm")return "/sys/module/pcie_aspm/parameters/policy";
 if(id=="swappiness")return "/proc/sys/vm/swappiness";
 if(id=="wifi_pm"||id=="wifi_wakeup"){
  char driver[4096];const string pci="/sys/bus/pci/devices/0000:01:00.0";
  if(!realpath((pci+"/driver").c_str(),driver)||string(driver).find("/drivers/cnss_pci")==string::npos)throw std::runtime_error("Wi-Fi PCIe driver not supported");
  return pci+(id=="wifi_pm"?"/power/control":"/power/wakeup");
 }
 throw std::runtime_error("Unknown control");
}
static string get(const string& id){string v=readFile(pathFor(id));if(id=="aspm"){size_t a=v.find('['),b=v.find(']');if(a==string::npos||b==string::npos||b<=a)throw std::runtime_error("Missing ASPM state");return v.substr(a+1,b-a-1);}return v;}
static vector<string> choices(const string& id){
 if(id=="wifi_pm")return {"auto","on"};if(id=="wifi_wakeup")return {"enabled","disabled"};
 if(id=="swappiness"){vector<string> a;for(int i=0;i<=200;i++)a.push_back(std::to_string(i));return a;}
 if(id=="aspm"){string s=readFile(pathFor(id));s.erase(std::remove(s.begin(),s.end(),'['),s.end());s.erase(std::remove(s.begin(),s.end(),']'),s.end());vector<string> out;for(auto& v:words(s))if(v=="default"||v=="performance"||v=="powersave"||v=="powersupersave")out.push_back(v);return out;}
 string file=cpuId(id)?"/sys/devices/system/cpu/cpufreq/"+id.substr(4)+"/scaling_available_frequencies":"/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies";
 std::set<unsigned long long> set;for(auto& v:words(readFile(file)))if(number(v)&&std::stoull(v)>0)set.insert(std::stoull(v));
 // cpuinfo_max_freq may be a valid stock ceiling absent from the advertised table.
 if(cpuId(id)){string max=readFile("/sys/devices/system/cpu/cpufreq/"+id.substr(4)+"/cpuinfo_max_freq");if(number(max))set.insert(std::stoull(max));}
 vector<string> out;for(auto v:set)out.push_back(std::to_string(v));return out;
}
static string set(const string& id,const string& value){
 vector<string> allowed=choices(id);if(std::find(allowed.begin(),allowed.end(),value)==allowed.end())throw std::runtime_error("Unsupported value");
 string path=pathFor(id);int fd=open(path.c_str(),O_WRONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)throw std::runtime_error("Control is not writable");
 string data=value+"\n";ssize_t n=write(fd,data.data(),data.size());int err=errno;close(fd);if(n!=(ssize_t)data.size())throw std::runtime_error(strerror(err));
 string actual=get(id);if(actual!=value)throw std::runtime_error("Kernel readback differs from requested value");return actual;
}
static string list(){
 vector<string> ids={"wifi_pm","wifi_wakeup","aspm","swappiness","gpu"};
 DIR* dir=opendir("/sys/devices/system/cpu/cpufreq");if(dir){dirent* e;while((e=readdir(dir))){string id="cpu_"+string(e->d_name);if(cpuId(id))ids.push_back(id);}closedir(dir);}
 std::sort(ids.begin(),ids.end());string out="[";
 for(size_t i=0;i<ids.size();i++){if(i)out+=',';out+="{\"id\":"+quote(ids[i]);try{string v=get(ids[i]);auto a=choices(ids[i]);out+=",\"supported\":true,\"current\":"+quote(v)+",\"values\":"+jsonArray(a);}catch(const std::exception&){out+=",\"supported\":false,\"current\":\"Unavailable\",\"values\":[]";}out+='}';}return out+"]";
}
static void respond(const string& id,const string& result){
 string path=base+"responses/response.json",temp=path+".tmp";
 int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0600);if(fd<0)throw std::runtime_error("Cannot create response");
 string out="{\"id\":"+quote(id)+",\"result\":"+result+"}";size_t pos=0;while(pos<out.size()){ssize_t n=write(fd,out.data()+pos,out.size()-pos);if(n<=0){close(fd);throw std::runtime_error("Response write failed");}pos+=n;}
 if(fchmod(fd,0640)||fsync(fd)){close(fd);throw std::runtime_error("Response permissions failed");}close(fd);if(rename(temp.c_str(),path.c_str()))throw std::runtime_error("Response rename failed");
}
#ifndef UNICA_BATTERY_TEST
int main(){
 if(getuid()!=0)return 1;
 int fd=open((base+"lock").c_str(),O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);if(fd<0)return 1;if(flock(fd,LOCK_EX|LOCK_NB)){close(fd);return 0;}
 string id;
 try{
  vector<string> args=words(readFile(base+"requests/request",512));
  if(args.size()<2||args.size()>4||!uuid(args[0]))throw std::runtime_error("Invalid request");id=args[0];
  try{if(readFile(base+"responses/response.json",65536).find("\"id\":"+quote(id))!=string::npos){close(fd);return 0;}}catch(const std::exception&){}
  string result;
  if(args[1]=="list"&&args.size()==2)result="{\"ok\":true,\"controls\":"+list()+"}";
  else if(args[1]=="get"&&args.size()==3)result="{\"ok\":true,\"value\":"+quote(get(args[2]))+"}";
  else if(args[1]=="set"&&args.size()==4)result="{\"ok\":true,\"value\":"+quote(set(args[2],args[3]))+"}";
  else throw std::runtime_error("Invalid operation");
  respond(id,result);
 }catch(const std::exception& e){if(uuid(id))try{respond(id,"{\"ok\":false,\"error\":"+quote(e.what())+"}");}catch(const std::exception&){}close(fd);return 1;}
 close(fd);return 0;
}
#else
int main(){
 int checks=0;auto check=[&](bool b){if(!b)throw std::runtime_error("Native validation failed");checks++;};
 check(uuid("12345678-1234-1234-1234-123456789abc"));check(!uuid("../bad"));
 check(cpuId("cpu_policy7"));check(!cpuId("cpu_policy7/../../etc/passwd"));check(!cpuId("cpu_policy7;reboot"));check(!cpuId("cpu_policy1000"));
 check(pathFor("cpu_policy7")=="/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq");
 check(choices("swappiness").size()==201);check(choices("wifi_pm")==vector<string>({"auto","on"}));
 for(string id:{"/proc/sysrq-trigger","reboot","cpu_policy../7","gpu/../x"}){bool denied=false;try{pathFor(id);}catch(const std::exception&){denied=true;}check(denied);}
 check(!number("60;reboot"));check(!number("-1"));check(number("3360000"));
 printf("PASS %d native allowlist assertions\n",checks);
}
#endif
