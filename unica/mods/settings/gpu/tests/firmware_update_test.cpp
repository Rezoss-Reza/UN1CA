// SPDX-License-Identifier: GPL-3.0-or-later
// Two real vendor releases, relocated DTB fixtures and independent driver metadata.
#define GPU_TEST
#define main gpu_worker_main
#include "../native/worker.cpp"
#undef main
#include <filesystem>
#include <iostream>
static unsigned checks=0,serial=0;
static void check(bool value,int line=__builtin_LINE()){require(value,"Update assertion failed at line "+std::to_string(line));++checks;}
template<class F> void denied(F f,int line=__builtin_LINE()){bool no=false;try{f();}catch(const std::exception&){no=true;}check(no,line);}
static int request(const std::string& op,const std::string& payload=""){
 char id[40];snprintf(id,sizeof(id),"30000000-0000-0000-0000-%012u",++serial);atomic(IPC+"requests/request",std::string(id)+" "+op+" "+payload);return service();
}
static std::string wire(const std::string& hash,const GpuBins& bins){
 std::string s=hash;for(const auto& bin:bins){s+=" "+std::to_string(bin.rows.size())+" "+std::to_string(bin.initial)+" "+std::to_string(bin.minimum);
 for(const auto& r:bin.rows)s+=" "+std::to_string(r.frequency)+" "+std::to_string(r.voltage)+" "+std::to_string(r.bus)+" "+std::to_string(r.minimum)+" "+std::to_string(r.maximum)+" "+std::to_string(r.hasAcd?1:0)+" "+std::to_string(r.hasAcd?r.acd:0); }return s;
}
int main(int argc,char** argv){try{
 require(argc==5,"update-test old.img new.img driver.ko temporary-directory");BASE=std::string(argv[4])+"/backup/";IPC=std::string(argv[4])+"/ipc/";ARCHIVES=IPC+"archives/";
 std::filesystem::create_directories(BASE);std::filesystem::create_directories(IPC+"requests");std::filesystem::create_directories(IPC+"responses");
 auto old=read(argv[1],IMAGE_SIZE),fresh=read(argv[2],IMAGE_SIZE),driver=read(argv[3],8*1024*1024);auto oldHash=sha(old),newHash=sha(fresh);auto ot=table(old),nt=table(fresh);
 check(stockTables(old)&&stockTables(fresh));verifyDriver(driver);check(true);
 // Whole-module hashes, trailing signing bytes and unrelated metadata can change.
 auto module=driver;module.insert(module.end(),{1,2,3,4});check(sha(module)!=sha(driver));verifyDriver(module);check(true);
 const std::string metadata="vermagic=";auto m=std::search(module.begin(),module.end(),metadata.begin(),metadata.end());check(m!=module.end());*(m+metadata.size())^=1;verifyDriver(module);check(true);
 // The executable parser and its property references are not waived.
 auto h=elfRead<Elf64_Ehdr>(driver,0);bool changed=false;
 for(unsigned i=0;i<h.e_shnum;i++){auto sec=elfRead<Elf64_Shdr>(driver,h.e_shoff+i*sizeof(Elf64_Shdr));if(sec.sh_type!=SHT_SYMTAB)continue;auto strings=elfRead<Elf64_Shdr>(driver,h.e_shoff+sec.sh_link*sizeof(Elf64_Shdr));
  for(size_t j=0;j<sec.sh_size;j+=sizeof(Elf64_Sym)){auto f=elfRead<Elf64_Sym>(driver,sec.sh_offset+j);if(strz(driver,strings.sh_offset+f.st_name,strings.sh_offset+strings.sh_size)!="gen7_build_rpmh_tables")continue;auto code=elfRead<Elf64_Shdr>(driver,h.e_shoff+f.st_shndx*sizeof(Elf64_Shdr));auto bad=driver;bad[code.sh_offset+f.st_value]^=1;denied([&]{verifyDriver(bad);});changed=true;}}
 check(changed);denied([&]{verifyDriver(Bytes(64,0));});
 module=driver;const std::string level="qcom,level";auto prop=std::search(module.begin(),module.end(),level.begin(),level.end());check(prop!=module.end());*prop='x';denied([&]{verifyDriver(module);});
 // Valid unrelated content and moved GPU/string-table offsets remain compatible.
 auto moved=fresh;size_t start=nt.dtb+nt.offset;std::move_backward(moved.begin()+start,moved.begin()+nt.dtb+nt.size,moved.begin()+nt.dtb+nt.size+4);put(moved,start,4);
 put(moved,nt.dtb+4,be(fresh,nt.dtb+4)+4);put(moved,nt.dtb+12,be(fresh,nt.dtb+12)+4);put(moved,nt.dtb+36,be(fresh,nt.dtb+36)+4);uint32_t size=nt.size+4;for(int i=0;i<4;i++){moved[2100+i]=size&255;size>>=8;}
 const std::string modelText="Technologies";auto where=std::search(moved.begin()+nt.dtb,moved.begin()+nt.dtb+nt.size,modelText.begin(),modelText.end());check(where!=moved.begin()+nt.dtb+nt.size);*where='t';
 auto mt=table(moved);check(mt.offset==nt.offset+4);check(stockTables(moved));auto draft=mt.bins;draft[0].rows[0].voltage=72;check(table(editBins(moved,draft)).bins[0].rows[0].voltage==72);
 auto invalid=fresh;put(invalid,nt.dtb+nt.offset,4);denied([&]{table(invalid);});invalid=fresh;put(invalid,nt.dtb+nt.offset+property(nt.reference,"#address-cells")->offset,2);denied([&]{table(invalid);});
 // Saved logical profile survives a real firmware update. Never restore the old image.
 auto saved=ot.bins;saved[0].rows[0].frequency=700000000;saved[0].rows[0].voltage=72;saved[1].rows[0].voltage=96;
 atomic(BASE+"partition.img",old);check(request("backup",oldHash)==0);auto oldZip=text(BASE+"rollback.sha256",64);check(request("exported",oldHash+" "+oldZip)==0);
 atomic(BASE+"partition.img",fresh);check(request("status")==0);auto reply=text(IPC+"responses/response.json");check(reply.find("\"canRenew\":true")!=std::string::npos);check(request("restore",newHash)!=0);check(request("apply2",wire(newHash,saved))!=0);check(sha(current())==newHash);
 failRenewPhase=3;check(request("renew",newHash+" "+oldHash)!=0);testCacheFree=0;check(request("renew",newHash+" "+oldHash)==0);testCacheFree=-1;
 check(sha(original())==newHash);check(sha(current())==newHash);check(!exists(BASE+"exported"));verifyArchived(ARCHIVES+oldHash+"-"+oldZip+"/",Renewal{oldHash,newHash,oldHash+"-"+oldZip});check(true);
 check(request("exported",newHash+" "+oldZip)!=0);check(request("apply2",wire(newHash,saved))!=0);auto newZip=text(BASE+"rollback.sha256",64);check(newZip!=oldZip);check(request("exported",newHash+" "+newZip)==0);
 check(request("apply2",wire(newHash,saved))==0);auto tuned=current();auto t=table(tuned);check(t.bins[0].rows[0].frequency==700000000);check(t.bins[0].rows[0].voltage==72);check(t.bins[1].rows[0].voltage==96);check(sha(original())==newHash);check(request("restore",sha(tuned))==0);check(sha(current())==newHash);
 std::cout<<"PASS "<<checks<<" capability, metadata, relocated FDT, update/archive and profile reuse assertions\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
