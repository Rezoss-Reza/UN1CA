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
 require(argc==3,"transaction-test original.img temporary-directory");BASE=std::string(argv[2])+"/backup/";IPC=std::string(argv[2])+"/ipc/";ARCHIVES=IPC+"archives/";
 std::filesystem::create_directories(BASE);std::filesystem::create_directories(IPC+"requests");std::filesystem::create_directories(IPC+"responses");
 auto b=read(argv[1],IMAGE_SIZE);auto h=sha(b);atomic(BASE+"partition.img",b);
 check(request("status")==0);check(sha(current())==h);
 check(request("apply",profile(h))!=0);check(sha(current())==h);
 check(request("backup",h)==0);check(sha(original())==h);check(exists(BASE+"rollback.zip"));
 check(request("apply",profile(h))!=0);check(sha(current())==h);
 check(request("exported",h+" "+std::string(64,'0'))!=0);
 auto zh=text(BASE+"rollback.sha256",64);check(request("exported",h+" "+zh)==0);
 check(request("apply",profile(std::string(64,'0')))!=0);check(sha(current())==h);
 check(request("apply",profile(h,0))!=0);check(sha(current())==h);
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
 // Version-2 requests edit both bins and retain the same journal/rollback gates.
 auto bins=table(b).bins;bins[0].rows[0].frequency=730999999;bins[0].rows[0].voltage=1;bins[0].rows.pop_back();bins[1].rows[8].voltage=56;
 auto wire=[&](const std::string& hash){std::string value=hash;for(auto& bin:bins){value+=" "+std::to_string(bin.rows.size())+" "+std::to_string(bin.initial)+" "+std::to_string(bin.minimum);for(auto& row:bin.rows)value+=" "+std::to_string(row.frequency)+" "+std::to_string(row.voltage)+" "+std::to_string(row.bus)+" "+std::to_string(row.minimum)+" "+std::to_string(row.maximum)+" "+std::to_string(row.hasAcd?1:0)+" "+std::to_string(row.hasAcd?row.acd:0);}return value;};
 check(request("apply2",wire(std::string(64,'0')))!=0);check(sha(current())==h);
 check(request("apply2",wire(h)+" 0")!=0);check(sha(current())==h);
 bins[0].rows.back().maximum=10;check(request("apply2",wire(h))!=0);check(sha(current())==h);bins[0].rows.back().maximum=3;
 check(request("apply2",wire(h))==0);auto v2=current();check(table(v2).bins[0].rows.size()==9);check(table(v2).bins[0].rows[0].voltage==1);check(table(v2).bins[1].rows[8].voltage==56);check(request("restore",sha(v2))==0);check(sha(current())==h);
 failWrites=2;check(request("apply2",wire(h))!=0);check(exists(BASE+"writing"));check(request("apply2",wire(sha(current())))!=0);check(request("restore",sha(current()))==0);check(sha(current())==h);
 // A new/externally modified image must not be overwritten by a stale backup.
 auto other=b;other[8192]^=1;atomic(BASE+"partition.img",other);auto foreign=sha(other);check(request("status")==0);check(text(IPC+"responses/response.json").find("\"canRestore\":false")!=std::string::npos);
 check(request("restore",foreign)!=0);check(request("apply",profile(foreign))!=0);check(sha(current())==foreign);check(sha(original())==h);
 // Backup corruption must be detected before touching the partition.
 atomic(BASE+"partition.img",b);auto broken=b;broken[9000]^=1;atomic(BASE+"original.img",broken);check(request("apply",profile(h))!=0);check(request("restore",h)!=0);check(sha(current())==h);atomic(BASE+"original.img",b);

 // A valid current set needs no rewrite, even with almost no available cache.
 testCacheFree=0;check(request("backup",h)==0);check(sha(original())==h);testCacheFree=-1;
 // Refresh is an archive-only transaction: the live partition is never flashed.
 for(int phase=1;phase<=4;phase++){
  ARCHIVES=IPC+"archives-renew-"+std::to_string(phase)+"/";BASE=std::string(argv[2])+"/renew-"+std::to_string(phase)+"/";std::filesystem::create_directories(BASE);
  atomic(BASE+"partition.img",b);check(request("backup",h)==0);auto oldZip=text(BASE+"rollback.sha256",64);
  check(request("exported",h+" "+oldZip)==0);atomic(BASE+"partition.img",other);
  check(request("status")==0);reply=text(IPC+"responses/response.json");check(reply.find("\"canRenew\":true")!=std::string::npos);check(reply.find("\"exported\":false")!=std::string::npos);
  check(request("renew",foreign+" "+std::string(64,'0'))!=0);check(sha(original())==h);
  failRenewPhase=phase;check(request("renew",foreign+" "+h)!=0);check(exists(BASE+"renewing"));check(sha(current())==foreign);
  auto archive=(phase==1?BASE+"archives/":ARCHIVES)+h+"-"+oldZip+"/";check(sha(read(archive+"original.img",IMAGE_SIZE))==h);check(sha(read(archive+"rollback.zip",IMAGE_SIZE+10*1024*1024))==oldZip);
  check(request("status")==0);reply=text(IPC+"responses/response.json");check(reply.find("\"renewing\":true")!=std::string::npos);check(reply.find("\"canRenew\":true")!=std::string::npos);check(reply.find("\"canRestore\":false")!=std::string::npos);
  check(request("apply",profile(foreign))!=0);check(request("restore",foreign)!=0);check(request("exported",foreign+" "+oldZip)!=0);
  // A second ROM change cannot silently adopt a different image mid-refresh.
  auto third=other;third[8193]^=1;atomic(BASE+"partition.img",third);check(request("renew",sha(third)+" "+h)!=0);check(exists(BASE+"renewing"));atomic(BASE+"partition.img",other);
  if(phase>=2)testCacheFree=0;
  check(request("renew",foreign+" "+h)==0);testCacheFree=-1;check(!exists(BASE+"renewing"));check(sha(original())==foreign);check(sha(current())==foreign);check(!exists(BASE+"exported"));check(!exists(BASE+"applied.sha256"));
  archive=ARCHIVES+h+"-"+oldZip+"/";check(sha(read(archive+"original.img",IMAGE_SIZE))==h);check(sha(read(archive+"rollback.zip",IMAGE_SIZE+10*1024*1024))==oldZip);check(!exists(BASE+"archives/"+h+"-"+oldZip+"/original.img"));
  check(request("apply",profile(foreign))!=0);auto freshZip=text(BASE+"rollback.sha256",64);check(freshZip!=oldZip);check(request("exported",foreign+" "+oldZip)!=0);check(request("exported",foreign+" "+freshZip)==0);
  check(request("apply",profile(foreign))==0);auto freshTuned=sha(current());check(request("restore",freshTuned)==0);check(sha(current())==foreign);
  // Unknown tuned values cannot become a clean rollback baseline.
  auto unknown=edit(other,STOCK_FREQ,STOCK_LEVEL);Values uf=STOCK_FREQ;uf[0]=710000000;unknown=edit(unknown,uf,STOCK_LEVEL);unknown[8194]^=1;atomic(BASE+"partition.img",unknown);
  check(request("renew",sha(unknown)+" "+foreign)!=0);check(sha(original())==foreign);
  atomic(BASE+"partition.img",b);atomic(BASE+"writing",foreign+" "+h);check(request("renew",h+" "+foreign)!=0);check(sha(original())==foreign);removefile(BASE+"writing");
 }
 // Fault injection spans copy, verification, cache-archive unlink and active unlink.
 for(int fault=1;fault<=8;fault++){
  BASE=std::string(argv[2])+"/move-"+std::to_string(fault)+"/";ARCHIVES=IPC+"archives-move-"+std::to_string(fault)+"/";std::filesystem::create_directories(BASE);
  atomic(BASE+"partition.img",b);check(request("backup",h)==0);auto oldZip=text(BASE+"rollback.sha256",64);auto oldID=h+"-"+oldZip;
  check(request("exported",h+" "+oldZip)==0);atomic(BASE+"partition.img",other);
  if(fault<=4)failArchivePhase=fault;
  if(fault==5)testDataFree=0;
  if(fault==6)testCacheFree=156*1024*1024;
  // Faults 7/8 corrupt a partial/complete /data destination before retry.
  if(fault==7||fault==8)failArchivePhase=fault==7?1:2;
  check(request("renew",foreign+" "+h)!=0);check(sha(current())==foreign);check(exists(BASE+"renewing"));
  if(fault==1||fault==2||fault==5||fault>=7){check(sha(original())==h);check(sha(read(BASE+"rollback.zip",IMAGE_SIZE+10*1024*1024))==oldZip);}
  else {verifyArchived(ARCHIVES+oldID+"/",Renewal{h,foreign,oldID});check(true);}
  check(request("status")==0);check(text(IPC+"responses/response.json").find("\"renewing\":true")!=std::string::npos);
  check(request("apply",profile(foreign))!=0);check(request("restore",foreign)!=0);check(request("exported",foreign+" "+oldZip)!=0);
  if(fault>=7){auto damaged=b;damaged[9000]^=1;atomic(ARCHIVES+oldID+"/original.img",damaged);check(request("renew",foreign+" "+h)!=0);check(sha(original())==h);check(exists(BASE+"archives/"+oldID+"/original.img"));atomic(ARCHIVES+oldID+"/original.img",b);}
  testDataFree=-1;testCacheFree=-1;
  check(request("renew",foreign+" "+h)==0);check(sha(current())==foreign);check(sha(original())==foreign);check(!exists(BASE+"renewing"));check(!exists(BASE+"exported"));
  verifyArchived(ARCHIVES+oldID+"/",Renewal{h,foreign,oldID});check(true);check(!exists(BASE+"archives/"+oldID+"/original.img"));check(!exists(BASE+"archives/"+oldID+"/rollback.zip"));
  check(request("apply",profile(foreign))!=0);
 }
 // Upgrade an existing cache archive while keeping the current active pair.
 BASE=std::string(argv[2])+"/legacy/";ARCHIVES=IPC+"archives-legacy/";std::filesystem::create_directories(BASE);atomic(BASE+"partition.img",b);check(request("backup",h)==0);
 auto legacyZip=text(BASE+"rollback.sha256",64),legacyID=h+"-"+legacyZip;directory(BASE+"archives/");directory(BASE+"archives/"+legacyID+"/");
 for(const auto& name:{"original.img","original.sha256","rollback.zip","rollback.sha256"})check(link((BASE+name).c_str(),(BASE+"archives/"+legacyID+"/"+name).c_str())==0);
 atomic(BASE+"archives/keep.txt","unrelated file");atomic(BASE+"writing",h+" "+h);check(request("backup",h)!=0);check(!exists(ARCHIVES+legacyID+"/original.img"));removefile(BASE+"writing");
 testCacheFree=0;check(request("backup",h)==0);testCacheFree=-1;verifyArchived(ARCHIVES+legacyID+"/",Renewal{h,"",legacyID});check(true);
 check(!exists(BASE+"archives/"+legacyID+"/original.img"));check(sha(original())==h);check(text(BASE+"archives/keep.txt")=="unrelated file");
 std::cout<<"PASS "<<checks<<" backup, export, stale-image, replay, interrupted-write archive migration, storage exhaustion, corruption and interrupted-refresh assertions\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
