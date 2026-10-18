// SPDX-License-Identifier: GPL-3.0-or-later
#include "image.h"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <fstream>
#include <sstream>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <linux/fs.h>
#include <unistd.h>
#if !defined(GPU_RECOVERY) && !defined(GPU_TEST)
#include <sys/system_properties.h>
#endif
using namespace gpu;
extern "C" void* avb_memcpy(void* a,const void* b,size_t n){return memcpy(a,b,n);}
extern "C" void* avb_memset(void* a,int v,size_t n){return memset(a,v,n);}
namespace {
#ifdef GPU_TEST
std::string BASE,IPC;
int failWrites=0;
int failRenewPhase=0;
#else
const std::string BASE="/cache/unica-gpu/",IPC="/data/misc/unica_gpu/";
#endif
const char* BLOCK="/dev/block/sda28";
struct FD {int n;explicit FD(int fd):n(fd){require(n>=0,"Cannot open protected file or partition");}~FD(){close(n);}operator int()const{return n;}};
Bytes read(const std::string& path,size_t max){FD f(open(path.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW));Bytes b;std::array<uint8_t,65536> chunk;ssize_t n;while((n=::read(f,chunk.data(),chunk.size()))>0){require(b.size()+n<=max,"Input too large");b.insert(b.end(),chunk.begin(),chunk.begin()+n);}require(n==0,"Read failed");return b;}
std::string text(const std::string& p,size_t max=4096){auto b=read(p,max);return std::string(b.begin(),b.end());}
Bytes bytes(const std::string& s){return Bytes(s.begin(),s.end());}
bool exists(const std::string& p){struct stat s;return lstat(p.c_str(),&s)==0;}
void syncdir(const std::string& p){FD f(open(p.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW));require(fsync(f)==0,"Directory sync failed");}
void writeall(int fd,const uint8_t* p,size_t size){while(size){ssize_t n=::write(fd,p,size);if(n<0&&errno==EINTR)continue;require(n>0,"Write failed; retain rollback and restore from recovery");p+=n;size-=n;}}
void atomic(const std::string& path,const Bytes& b,mode_t mode=0640){
 std::string tmp=path+".tmp";{FD f(open(tmp.c_str(),O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,mode));require(fchmod(f,mode)==0,"Cannot protect file");writeall(f,b.data(),b.size());require(fsync(f)==0,"File sync failed");}
 require(rename(tmp.c_str(),path.c_str())==0,"Atomic rename failed");syncdir(path.substr(0,path.find_last_of('/')));
}
void atomic(const std::string& p,const std::string& s){atomic(p,bytes(s));}
void removefile(const std::string& p){if(unlink(p.c_str())!=0)require(errno==ENOENT,"Cannot clear transaction state");syncdir(BASE);}
std::string resolved(const char* p){char out[4096];require(realpath(p,out)!=nullptr,"Missing partition mapping");return out;}
void mapping(){require(resolved("/dev/block/bootdevice/by-name/vendor_boot")==BLOCK,"Unsupported vendor_boot partition mapping");}
Bytes current(){
#ifdef GPU_TEST
 return read(BASE+"partition.img",IMAGE_SIZE);
#else
 mapping();FD f(open(BLOCK,O_RDONLY|O_CLOEXEC|O_NOFOLLOW));struct stat s;require(fstat(f,&s)==0&&S_ISBLK(s.st_mode),"Not a block partition");uint64_t size=0;require(ioctl(f,BLKGETSIZE64,&size)==0&&size==IMAGE_SIZE,"Wrong partition size");return read(BLOCK,IMAGE_SIZE);
#endif
}
void flash(const Bytes& b){
#ifdef GPU_TEST
 if(failWrites>0){--failWrites;auto partial=current();auto t=table(b);std::copy(b.begin(),b.begin()+t.dtb+300,partial.begin());partial[t.dtb+200]^=1;atomic(BASE+"partition.img",partial);throw std::runtime_error("Injected interrupted write");}
 atomic(BASE+"partition.img",b);return;
#else
 mapping();require(b.size()==IMAGE_SIZE,"Wrong image size");FD f(open(BLOCK,O_WRONLY|O_CLOEXEC|O_NOFOLLOW));struct stat s;uint64_t size=0;require(fstat(f,&s)==0&&S_ISBLK(s.st_mode)&&ioctl(f,BLKGETSIZE64,&size)==0&&size==IMAGE_SIZE,"Wrong partition device");
 writeall(f,b.data(),b.size());require(fsync(f)==0,"Partition flush failed; restore rollback in recovery");require(sha(current())==sha(b),"Partition verification failed; restore rollback in recovery");
#endif
}
std::string quote(const std::string& s){std::string r="\"";for(unsigned char c:s){if(c=='"'||c=='\\'){r+='\\';r+=c;}else if(c>=32&&c<127)r+=c;else r+=' ';}return r+'"';}
bool hashString(const std::string& s){return s.size()==64&&s.find_first_not_of("0123456789abcdef")==std::string::npos;}
void stock(const Bytes& b){require(stockTables(b),"Rollback baseline must have the original GPU table");}
void recover(){
 const std::string dir="/tmp/unica-gpu-rollback/";auto b=read(dir+"original.img",IMAGE_SIZE);auto expected=text(dir+"original.sha256",64);require(hashString(expected)&&sha(b)==expected,"Rollback image checksum mismatch");stock(b);
 // A recovery restore is an explicit user action, never an init/boot action.
 auto live=current();require(std::equal(b.begin(),b.begin()+4096,live.begin()),"Recovery image belongs to a different vendor_boot layout; restore matching firmware manually");
 flash(b);puts("Original vendor_boot restored and verified. Reboot manually.");
}
#ifndef GPU_RECOVERY
void verifyEnvironment(){
#ifndef GPU_TEST
 char prop[PROP_VALUE_MAX]={};__system_property_get("ro.product.vendor.device",prop);require(std::string(prop)=="dm3q","GPU trial supports dm3q only");
 require(resolved("/dev/block/bootdevice/by-name/vbmeta")=="/dev/block/sde20","Unsupported vbmeta mapping");
 FD fd(open("/dev/block/sde20",O_RDONLY|O_CLOEXEC|O_NOFOLLOW));Bytes header(256);require(pread(fd,header.data(),header.size(),0)==256,"Cannot read raw vbmeta");
 require(std::memcmp(header.data(),"AVB0",4)==0&&(be(header,120)&2),"Raw vbmeta verification must already be disabled; this feature never changes it");
 auto module=read("/vendor_dlkm/lib/modules/msm_kgsl.ko",8*1024*1024);require(sha(module)=="16cb910e20983d9ff90c8eb11eb075310af50411d45853e7925282ac240f3ecc","Unsupported GPU driver; firmware update requires compatibility review");
#endif
}
// Standard ZIP32, STORE entries. Recovery unzip verifies each CRC before use.
void zip16(Bytes& b,uint16_t v){b.push_back(v&255);b.push_back(v>>8);}
void zip32(Bytes& b,uint32_t v){zip16(b,v&65535);zip16(b,v>>16);}
void rollbackZip(const Bytes& image,const std::string& directory=BASE){
 const std::string script=R"(#!/sbin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
OUTFD="$2"
ui_print() { echo "ui_print $*" > "/proc/self/fd/$OUTFD"; echo ui_print > "/proc/self/fd/$OUTFD"; }
ui_print 'UN1CA GPU rollback: restore the exact pre-trial vendor_boot.'
mkdir -p /tmp/unica-gpu-rollback || exit 1
unzip -o "$3" original.img original.sha256 restore -d /tmp/unica-gpu-rollback || exit 1
chmod 0700 /tmp/unica-gpu-rollback/restore || exit 1
/tmp/unica-gpu-rollback/restore || { ui_print 'Restore failed. Stay in recovery and restore matching firmware.'; exit 1; }
ui_print 'Original image restored and verified. Reboot manually.'
exit 0
)";
 std::vector<std::pair<std::string,Bytes>> entries;
 entries.emplace_back("META-INF/com/google/android/update-binary",bytes(script));entries.emplace_back("META-INF/com/google/android/updater-script",bytes("# UN1CA GPU rollback\n"));
 entries.emplace_back("original.sha256",bytes(sha(image)));
#ifdef GPU_TEST
 entries.emplace_back("restore",bytes("TEST RECOVERY EXECUTABLE"));
#else
 entries.emplace_back("restore",read("/system/etc/unica/gpu/restore",8*1024*1024));
#endif
 entries.emplace_back("original.img",image);
 const std::string dst=directory+"rollback.zip",tmp=dst+".tmp";Bytes central;uint32_t pos=0;uint16_t count=0;AvbSHA256Ctx expected;avb_sha256_init(&expected);
 auto emit=[&](int fd,const Bytes& b){writeall(fd,b.data(),b.size());avb_sha256_update(&expected,b.data(),b.size());};
 {FD f(open(tmp.c_str(),O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0640));require(fchmod(f,0640)==0,"Cannot protect rollback ZIP");
 for(auto& e:entries){Bytes h;auto name=bytes(e.first);uint32_t crc=crc32(e.second),size=e.second.size();zip32(h,0x04034b50);zip16(h,20);zip16(h,0);zip16(h,0);zip16(h,0);zip16(h,0);zip32(h,crc);zip32(h,size);zip32(h,size);zip16(h,name.size());zip16(h,0);h.insert(h.end(),name.begin(),name.end());emit(f,h);emit(f,e.second);
  zip32(central,0x02014b50);zip16(central,20);zip16(central,20);for(int i=0;i<4;i++)zip16(central,0);zip32(central,crc);zip32(central,size);zip32(central,size);zip16(central,name.size());for(int i=0;i<4;i++)zip16(central,0);zip32(central,0);zip32(central,pos);central.insert(central.end(),name.begin(),name.end());pos+=h.size()+size;count++;
 }
 emit(f,central);Bytes end;zip32(end,0x06054b50);zip16(end,0);zip16(end,0);zip16(end,count);zip16(end,count);zip32(end,central.size());zip32(end,pos);zip16(end,0);emit(f,end);require(fsync(f)==0,"Rollback ZIP sync failed");}
 require(rename(tmp.c_str(),dst.c_str())==0,"Cannot commit rollback ZIP");syncdir(directory);auto z=read(dst,IMAGE_SIZE+10*1024*1024);auto d=digest(z);require(std::equal(d.begin(),d.end(),avb_sha256_final(&expected)),"Rollback ZIP readback failed");atomic(directory+"rollback.sha256",sha(z));
}
void backup(const Bytes& live,const std::string& directory=BASE){
 struct statvfs fs;require(statvfs(BASE.c_str(),&fs)==0,"Backup storage unavailable");
 require(uint64_t(fs.f_bavail)*fs.f_frsize>uint64_t(IMAGE_SIZE)*3+32*1024*1024,"At least 320 MiB free on /cache is required");
 if(directory==BASE)require(!exists(BASE+"writing")&&!exists(BASE+"renewing"),"Finish the interrupted operation before creating a backup");
 if(!exists(directory+"original.img")){stock(live);atomic(directory+"original.img",live);}
 auto original=read(directory+"original.img",IMAGE_SIZE);stock(original);require(sha(original)==sha(live),"Saved backup belongs to another image. Use Create backup for current ROM");
 if(exists(directory+"original.sha256"))require(text(directory+"original.sha256",64)==sha(original),"Original backup checksum mismatch");
 atomic(directory+"original.sha256",sha(original));rollbackZip(original,directory);
 require(sha(read(directory+"original.img",IMAGE_SIZE))==sha(original),"Backup readback failed");
}
Bytes original(){auto b=read(BASE+"original.img",IMAGE_SIZE);require(text(BASE+"original.sha256",64)==sha(b),"Original backup checksum mismatch");stock(b);return b;}
bool restorable(const Bytes& live,const Bytes& b){
 auto hash=sha(live);if(hash==sha(b))return true;
 if(exists(BASE+"applied.sha256")&&hash==text(BASE+"applied.sha256",64))return true;
 // A failed/power-interrupted write can have a partial hash. Only permit manual
 // restore if its transaction journal and the original image header still match.
 if(exists(BASE+"writing")){std::istringstream in(text(BASE+"writing"));std::string from,to;in>>from>>to;return hashString(from)&&hashString(to)&&exists(BASE+"candidate.img")&&to==sha(read(BASE+"candidate.img",IMAGE_SIZE))&&std::equal(b.begin(),b.begin()+4096,live.begin());}return false;
}
void directory(const std::string& p){
 if(mkdir(p.c_str(),0750)!=0)require(errno==EEXIST,"Cannot create protected backup directory");
 struct stat s;require(lstat(p.c_str(),&s)==0&&S_ISDIR(s.st_mode),"Backup directory is not a real directory");
 syncdir(p.substr(0,p.find_last_of('/',p.size()-2)));
}
struct Renewal {std::string oldHash,newHash,archive;};
Renewal renewal(){
 std::istringstream in(text(BASE+"renewing"));Renewal r;std::string extra;in>>r.oldHash>>r.newHash>>r.archive;
 require(hashString(r.oldHash)&&hashString(r.newHash)&&r.oldHash!=r.newHash&&r.archive.size()==129&&r.archive.substr(0,64)==r.oldHash&&r.archive[64]=='-'&&hashString(r.archive.substr(65))&&!(in>>extra),"Invalid backup refresh journal; preserve files for recovery");return r;
}
void verifyArchived(const std::string& dir,const Renewal& r){
 auto b=read(dir+"original.img",IMAGE_SIZE);stock(b);require(sha(b)==r.oldHash&&text(dir+"original.sha256",64)==r.oldHash,"Archived original checksum mismatch");
 auto zh=r.archive.substr(65);require(text(dir+"rollback.sha256",64)==zh&&sha(read(dir+"rollback.zip",IMAGE_SIZE+10*1024*1024))==zh,"Archived rollback ZIP checksum mismatch");
}
void renewPoint(int phase){
#ifdef GPU_TEST
 if(failRenewPhase==phase){failRenewPhase=0;throw std::runtime_error("Injected interrupted backup refresh");}
#else
 (void)phase;
#endif
}
void renewBackup(const Bytes& live,const std::string& expectedOld){
 require(!exists(BASE+"writing"),"Restore the interrupted partition write before refreshing backups");stock(live);const auto liveHash=sha(live);Renewal r;
 if(exists(BASE+"renewing")){
  r=renewal();require(r.oldHash==expectedOld&&r.newHash==liveHash,"Image changed during backup refresh; keep archived files and restore matching firmware manually");
 }else{
  auto old=original();require(sha(old)==expectedOld&&!restorable(live,old),"Backup already matches this image, or the confirmed old backup changed");
  auto zh=text(BASE+"rollback.sha256",64);require(hashString(zh)&&sha(read(BASE+"rollback.zip",IMAGE_SIZE+10*1024*1024))==zh,"Old rollback ZIP is damaged; preserve it before manual recovery");
  r={expectedOld,liveHash,expectedOld+"-"+zh};directory(BASE+"archives/");directory(BASE+"archives/"+r.archive+"/");
  const auto archive=BASE+"archives/"+r.archive+"/";
  // Hard links retain immutable original inodes without doubling cache usage.
  // Active files are replaced by atomic rename, never modified in place.
  for(const auto& name:{"original.img","original.sha256","rollback.zip","rollback.sha256"})
   if(link((BASE+name).c_str(),(archive+name).c_str())!=0)require(errno==EEXIST,"Cannot archive old backup; no active backup replaced");
  syncdir(archive);verifyArchived(archive,r);
  atomic(BASE+"renewing",r.oldHash+" "+r.newHash+" "+r.archive);renewPoint(1);
 }
 verifyArchived(BASE+"archives/"+r.archive+"/",r);directory(BASE+"pending/");directory(BASE+"pending/"+r.newHash+"/");
 const auto staged=BASE+"pending/"+r.newHash+"/";backup(live,staged);renewPoint(2);
 require(sha(current())==r.newHash,"Partition changed during backup refresh; archived original retained, no partition writes made");
 // The journal blocks Apply/Restore until all fresh files are durable. Retrying
 // reconstructs the same staging set, including after a partially committed set.
 removefile(BASE+"exported");removefile(BASE+"applied.sha256");
 int phase=0;for(const auto& name:{"original.img","original.sha256","rollback.zip","rollback.sha256"}){
  require(rename((staged+name).c_str(),(BASE+name).c_str())==0,"Backup activation interrupted; resume backup refresh");syncdir(BASE);syncdir(staged);if(++phase==1)renewPoint(3);
 }
 renewPoint(4);removefile(BASE+"candidate.img");removefile(BASE+"renewing");
}
std::string status(const Bytes& live){
 Table t;try{t=table(live);}catch(...){auto b=original();require(exists(BASE+"writing")&&restorable(live,b),"Unsupported image; recovery restoration may be required");t=table(b);}
 std::string r="{\"ok\":true,\"currentHash\":"+quote(sha(live))+",\"frequencies\":[";
 for(int i=0;i<10;i++){if(i)r+=',';r+=std::to_string(t.frequencies[i]);}r+="],\"levels\":[";for(int i=0;i<10;i++){if(i)r+=',';r+=std::to_string(t.levels[i]);}r+="]";
 r+=",\"tables\":[";for(int id=0;id<2;id++){if(id)r+=',';auto& bin=t.bins[id];r+="{\"id\":"+std::to_string(id)+",\"initial_level\":"+std::to_string(bin.initial)+",\"minimum_level\":"+std::to_string(bin.minimum)+",\"power_levels\":[";
 for(size_t i=0;i<bin.rows.size();i++){if(i)r+=',';auto& row=bin.rows[i];r+="{\"index\":"+std::to_string(i)+",\"frequency_hz\":"+std::to_string(row.frequency)+",\"rpmh_level\":"+std::to_string(row.voltage)+",\"bus_frequency\":"+std::to_string(row.bus)+",\"bus_minimum\":"+std::to_string(row.minimum)+",\"bus_maximum\":"+std::to_string(row.maximum)+",\"acd_level\":"+(row.hasAcd?std::to_string(row.acd):"null")+"}";}r+="]}";}r+="]";
 bool renewing=exists(BASE+"renewing"),interrupted=exists(BASE+"writing"),has=!renewing&&exists(BASE+"original.sha256")&&exists(BASE+"original.img"),can=false,exported=false,isOriginal=false,stale=false,canRenew=false;
 std::string zipHash,baselineHash,backupState="missing",blockReason;
 if(renewing){auto pending=renewal();baselineHash=pending.oldHash;stale=true;canRenew=sha(live)==pending.newHash&&!interrupted&&stockTables(live);backupState="refreshing";}
 else if(has){auto b=original();baselineHash=sha(b);can=restorable(live,b);isOriginal=sha(live)==baselineHash;stale=!can;if(exists(BASE+"rollback.sha256"))zipHash=text(BASE+"rollback.sha256",64);exported=hashString(zipHash)&&exists(BASE+"rollback.zip")&&exists(BASE+"exported")&&text(BASE+"exported",64)==zipHash;canRenew=stale&&!interrupted&&hashString(zipHash)&&exists(BASE+"rollback.zip")&&stockTables(live);backupState=stale?"stale":"ready";}
 bool complete=has&&hashString(zipHash)&&exists(BASE+"rollback.zip");
 if(interrupted)blockReason="Interrupted partition write: restore the original before rebooting";
 else if(renewing)blockReason=canRenew?"Backup refresh interrupted. Resume creating the backup for this ROM":"Image changed during backup refresh. Preserve archived backups and use matching firmware";
 else if(stale)blockReason=canRenew?"Saved rollback belongs to a different image. Create backup for current ROM":"Current image is not a clean supported baseline, or old rollback is incomplete. Backup refresh is blocked";
 else if(!complete)blockReason="Create and verify the original backup first";
 else if(!exported)blockReason="Save and verify the rollback ZIP for this image before Apply";
 r+=",\"backup\":"+std::string(complete?"true":"false")+",\"original\":"+(isOriginal?"true":"false")+",\"canRestore\":"+(can&&!renewing?"true":"false")+",\"exported\":"+(exported&&!stale&&!renewing?"true":"false")+",\"rollbackHash\":"+quote(zipHash)+",\"interrupted\":"+(interrupted?"true":"false")+",\"backupState\":"+quote(backupState)+",\"baselineHash\":"+quote(baselineHash)+",\"staleBackup\":"+(stale?"true":"false")+",\"canRenew\":"+(canRenew?"true":"false")+",\"renewing\":"+(renewing?"true":"false")+",\"blockReason\":"+quote(blockReason)+"}";return r;
}
void transact(const Bytes& live,const Bytes& target){
 atomic(BASE+"candidate.img",target);require(sha(read(BASE+"candidate.img",IMAGE_SIZE))==sha(target),"Candidate readback failed");
 // Recheck immediately before starting a durable partition transaction.
 require(sha(current())==sha(live),"Partition changed during preparation; nothing written");
 atomic(BASE+"writing",sha(live)+" "+sha(target));atomic(BASE+"applied.sha256",sha(target));
 try{flash(target);removefile(BASE+"writing");}
 catch(...){try{auto b=original();flash(b);removefile(BASE+"writing");}catch(...){}throw;}
 removefile(BASE+"candidate.img");
}
void respond(const std::string& id,const std::string& result){atomic(IPC+"responses/response.json","{\"id\":"+quote(id)+",\"result\":"+result+"}");}
int service(){
 FD lock(open((BASE+"lock").c_str(),O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600));if(flock(lock,LOCK_EX|LOCK_NB)!=0)return 0;
 std::string id;try{
  std::istringstream in(text(IPC+"requests/request",8192));std::vector<std::string> args;std::string a;while(in>>a)args.push_back(a);
  require(args.size()>=2,"Incomplete request");id=args[0];require(id.size()==36&&id.find_first_not_of("0123456789abcdef-")==std::string::npos,"Invalid request identifier");
  // Retriggering a timed-out request must never repeat a partition write.
  if(exists(IPC+"responses/response.json")&&text(IPC+"responses/response.json").find("\"id\":"+quote(id))!=std::string::npos)return 0;
  verifyEnvironment();auto live=current();const std::string op=args[1];
  if(op=="status"){require(args.size()==2,"Bad status request");respond(id,status(live));return 0;}
  require(args.size()>=3&&hashString(args[2])&&sha(live)==args[2],"Device image changed. Refresh before continuing");
  if(op=="backup"){require(args.size()==3,"Bad backup request");backup(live);}
  else if(op=="renew"){require(args.size()==4&&hashString(args[3]),"Bad backup refresh request");renewBackup(live,args[3]);}
  else if(op=="exported"){require(!exists(BASE+"renewing"),"Finish backup refresh before export");auto b=original();require(restorable(live,b)&&!exists(BASE+"writing"),"Export a fresh backup for the current image first");require(args.size()==4&&hashString(args[3]),"Bad export acknowledgement");require(args[3]==text(BASE+"rollback.sha256",64)&&sha(read(BASE+"rollback.zip",IMAGE_SIZE+10*1024*1024))==args[3],"Rollback ZIP checksum mismatch");atomic(BASE+"exported",args[3]);}
  else if(op=="apply"){
   require(!exists(BASE+"renewing"),"Finish backup refresh and export the new rollback ZIP before Apply");require(args.size()==23,"Expected ten frequency/level pairs");auto b=original();require(restorable(live,b)&&!exists(BASE+"writing"),"Restore interrupted or unrelated image before applying");
   require(exists(BASE+"exported")&&text(BASE+"exported",64)==text(BASE+"rollback.sha256",64)&&sha(read(BASE+"rollback.zip",IMAGE_SIZE+10*1024*1024))==text(BASE+"rollback.sha256",64),"Save and verify the rollback ZIP first");
   Values f{},l{};for(int i=0;i<10;i++){for(int j=0;j<2;j++){auto s=args[3+i*2+j];require(!s.empty()&&s.size()<=10&&s.find_first_not_of("0123456789")==std::string::npos,"Bad numeric GPU value");auto value=std::stoull(s);require(value<=UINT32_MAX,"GPU value overflows u32");(j?l:f)[i]=uint32_t(value);}}
   auto t=table(live);require(f!=t.frequencies||l!=t.levels,"No GPU changes selected");auto target=edit(live,f,l);transact(live,target);
  }else if(op=="apply2"){
   require(!exists(BASE+"renewing")&&!exists(BASE+"writing"),"Finish recovery or backup refresh before Apply");auto baseline=original();require(restorable(live,baseline),"Backup does not match current image");
   require(exists(BASE+"exported")&&text(BASE+"exported",64)==text(BASE+"rollback.sha256",64)&&sha(read(BASE+"rollback.zip",IMAGE_SIZE+10*1024*1024))==text(BASE+"rollback.sha256",64),"Save and verify the rollback ZIP first");
   size_t cursor=3;auto number=[&](){require(cursor<args.size(),"Incomplete GPU table request");auto value=args[cursor++];require(!value.empty()&&value.size()<=10&&value.find_first_not_of("0123456789")==std::string::npos,"Bad GPU cell");uint64_t n=std::stoull(value);require(n<=UINT32_MAX,"GPU cell overflows u32");return uint32_t(n);};GpuBins bins;
   for(auto& bin:bins){auto count=number();require(count>=2&&count<=15,"Expected 2–15 power levels");bin.initial=number();bin.minimum=number();for(uint32_t i=0;i<count;i++){GpuRow row;row.frequency=number();row.voltage=number();row.bus=number();row.minimum=number();row.maximum=number();auto acd=number();require(acd<=1,"Bad ACD presence flag");row.hasAcd=acd;row.acd=number();require(row.hasAcd||row.acd==0,"Absent ACD must have value zero");bin.rows.push_back(row);}}
   require(cursor==args.size(),"Unexpected GPU request fields");auto target=editBins(live,bins);require(sha(target)!=sha(live),"No GPU changes selected");transact(live,target);
  }else if(op=="restore"){require(!exists(BASE+"renewing"),"Finish backup refresh before Restore");require(args.size()==3,"Bad restore request");auto b=original();require(restorable(live,b),"Backup does not match this image; restore matching firmware manually");if(sha(live)!=sha(b))transact(live,b);else if(exists(BASE+"writing"))removefile(BASE+"writing");}
  else throw std::runtime_error("Unknown GPU operation");
  respond(id,status(current()));return 0;
 }catch(const std::exception& e){if(id.size()==36)try{respond(id,"{\"error\":"+quote(e.what())+"}");}catch(...){}return 1;}
}
#endif
}
int main(){if(getuid()!=0)return 1;try{
#ifdef GPU_RECOVERY
 recover();return 0;
#else
 return service();
#endif
}catch(const std::exception& e){fprintf(stderr,"UN1CA GPU: %s\n",e.what());return 1;}}
