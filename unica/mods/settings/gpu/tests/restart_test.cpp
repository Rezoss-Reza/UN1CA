// SPDX-License-Identifier: GPL-3.0-or-later
// Confirmed obsolete-refresh recovery must preserve every managed source file.
#define GPU_TEST
#define main gpu_worker_main
#include "../native/worker.cpp"
#undef main
#include <filesystem>
#include <iostream>
static unsigned checks=0,serial=0;
static void check(bool value,int line=__builtin_LINE()){require(value,"Restart assertion failed at line "+std::to_string(line));++checks;}
static int request(const std::string& op,const std::string& payload=""){
 char id[40];snprintf(id,sizeof(id),"20000000-0000-0000-0000-%012u",++serial);atomic(IPC+"requests/request",std::string(id)+" "+op+" "+payload);return service();
}
int main(int argc,char** argv){try{
 require(argc==3,"restart-test image temporary-directory");const auto work=std::string(argv[2])+"/";
 BASE=work+"fixture/";IPC=work+"ipc/";ARCHIVES=IPC+"archives/";std::filesystem::create_directories(BASE);std::filesystem::create_directories(IPC+"requests");std::filesystem::create_directories(IPC+"responses");
 auto b=read(argv[1],IMAGE_SIZE);auto h=sha(b);atomic(BASE+"partition.img",b);backup(b);const auto fixture=BASE;auto zip=text(BASE+"rollback.sha256",64),id=h+"-"+zip;
 auto prior=b;prior[8192]^=1;auto ph=sha(prior);auto now=prior;now[8193]^=1;auto nh=sha(now);
 for(int fault=1;fault<=10;fault++){
  BASE=work+"case-"+std::to_string(fault)+"/";ARCHIVES=IPC+"archive-"+std::to_string(fault)+"/";std::filesystem::create_directories(BASE);
  for(const auto& name:{"original.img","original.sha256","rollback.zip","rollback.sha256"})check(link((fixture+name).c_str(),(BASE+name).c_str())==0);
  directory(BASE+"archives/");directory(BASE+"archives/"+id+"/");for(const auto& name:{"original.img","original.sha256","rollback.zip","rollback.sha256"})check(link((BASE+name).c_str(),(BASE+"archives/"+id+"/"+name).c_str())==0);
  directory(BASE+"pending/");directory(BASE+"pending/"+ph+"/");atomic(BASE+"pending/"+ph+"/original.img",prior);atomic(BASE+"pending/"+ph+"/rollback.zip.tmp","partial ZIP");
  auto journal=h+" "+ph+" "+id;atomic(BASE+"renewing",journal);atomic(BASE+"exported",zip);atomic(BASE+"applied.sha256",ph);atomic(BASE+"keep.txt","unrelated");
  // Also support returning to the original ROM while an obsolete refresh exists.
  auto live=fault==10?b:now;auto liveHash=sha(live);atomic(BASE+"partition.img",live);auto payload=liveHash+" "+h+" "+ph;
  check(request("status")==0);check(text(IPC+"responses/response.json").find("\"canRestart\":true")!=std::string::npos);
  check(request("restart",liveHash+" "+h+" "+h)!=0);check(text(BASE+"renewing")==journal);
  atomic(BASE+"writing",h+" "+liveHash);check(request("restart",payload)!=0);check(!exists(BASE+"restarting"));removefile(BASE+"writing");
  if(fault<=7)failRestartPhase=fault;
  if(fault==8){copyArchive(BASE+"archives/"+id+"/",Renewal{h,ph,id});testDataFree=0;}
  if(fault==9)failRestartPhase=2;
  if(fault<=9){
   check(request("restart",payload)!=0);check(sha(current())==liveHash);check(request("status")==0);check(text(IPC+"responses/response.json").find("\"canRestart\":true")!=std::string::npos);
   check(request("restore",liveHash)!=0);check(request("exported",liveHash+" "+zip)!=0);
   if(fault==8){check(text(BASE+"renewing")==journal);check(sha(original())==h);testDataFree=-1;}
   else if(fault>=2){auto r=restartState();auto receipt=readReceipt(r);check(!receipt.empty());check(text(ARCHIVES+"refreshes/"+r.receipt+"/files/renewing")==journal);
    if(fault==9){auto file=ARCHIVES+"refreshes/"+r.receipt+"/files/pending/"+ph+"/original.img";auto damaged=prior;damaged[9000]^=1;atomic(file,damaged);check(request("restart",payload)!=0);check(sha(original())==h);atomic(file,prior);}
   }
  }
  if(fault>=5&&fault<=7)testCacheFree=0;
  check(request("restart",payload)==0);testCacheFree=-1;check(sha(current())==liveHash);check(sha(original())==liveHash);check(!exists(BASE+"renewing"));check(!exists(BASE+"restarting"));check(!exists(BASE+"exported"));check(!exists(BASE+"applied.sha256"));check(text(BASE+"keep.txt")=="unrelated");
  auto receiptID=sha(bytes(journal))+"-"+liveHash;auto root=ARCHIVES+"refreshes/"+receiptID+"/files/";check(text(root+"renewing")==journal);check(sha(read(root+"pending/"+ph+"/original.img",IMAGE_SIZE))==ph);check(text(root+"pending/"+ph+"/rollback.zip.tmp")=="partial ZIP");verifyArchived(ARCHIVES+id+"/",Renewal{h,liveHash,id});++checks;
  check(!exists(BASE+"pending/"+ph+"/original.img"));check(!exists(BASE+"archives/"+id+"/original.img"));check(request("exported",liveHash+" "+std::string(64,'0'))!=0);
  auto fresh=text(BASE+"rollback.sha256",64);check(request("exported",liveHash+" "+fresh)==0);check(request("status")==0);check(text(IPC+"responses/response.json").find("\"exported\":true")!=std::string::npos);
  std::cout<<"PASS restart scenario "<<fault<<std::endl;
 }
 std::cout<<"PASS "<<checks<<" obsolete-refresh, journal, staging, full-storage, checksum and export assertions\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
