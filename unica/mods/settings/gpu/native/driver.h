// SPDX-License-Identifier: GPL-3.0-or-later
// Match the audited table-handling code, not a firmware version or module hash.
#include <elf.h>
template<class T> inline T elfRead(const Bytes& b,size_t p){range(b,p,sizeof(T));T v;std::memcpy(&v,b.data()+p,sizeof(T));return v;}
inline void signatureText(Bytes& b,const std::string& s){b.insert(b.end(),s.begin(),s.end());b.push_back(0);}
inline void signatureNumber(Bytes& b,uint64_t n){size_t p=b.size();b.resize(p+8);put64(b,p,n);}
inline std::map<std::string,std::string> driverSignatures(const Bytes& b){
 auto h=elfRead<Elf64_Ehdr>(b,0);
 require(std::memcmp(h.e_ident,ELFMAG,SELFMAG)==0&&h.e_ident[EI_CLASS]==ELFCLASS64&&h.e_ident[EI_DATA]==ELFDATA2LSB&&h.e_machine==EM_AARCH64&&h.e_type==ET_REL,"GPU driver must be an AArch64 ELF module");
 require(h.e_shentsize==sizeof(Elf64_Shdr)&&h.e_shnum>0&&h.e_shnum<512&&h.e_shstrndx<h.e_shnum,"Invalid GPU driver sections");
 range(b,h.e_shoff,size_t(h.e_shnum)*sizeof(Elf64_Shdr));std::vector<Elf64_Shdr> sections;
 for(unsigned i=0;i<h.e_shnum;i++){auto s=elfRead<Elf64_Shdr>(b,h.e_shoff+i*sizeof(Elf64_Shdr));if(s.sh_type!=SHT_NOBITS)range(b,s.sh_offset,s.sh_size);sections.push_back(s);}
 auto names=sections[h.e_shstrndx];auto sectionName=[&](const Elf64_Shdr& s){require(s.sh_name<names.sh_size,"Invalid ELF section name");return strz(b,names.sh_offset+s.sh_name,names.sh_offset+names.sh_size);};
 std::map<std::string,std::string> result;
 for(size_t si=0;si<sections.size();si++){
  auto syms=sections[si];if(syms.sh_type!=SHT_SYMTAB)continue;
  require(syms.sh_link<sections.size()&&syms.sh_entsize==sizeof(Elf64_Sym)&&syms.sh_size%sizeof(Elf64_Sym)==0,"Invalid GPU symbol table");auto strings=sections[syms.sh_link];
  auto symbol=[&](size_t i){require(i<syms.sh_size/sizeof(Elf64_Sym),"Bad GPU relocation symbol");return elfRead<Elf64_Sym>(b,syms.sh_offset+i*sizeof(Elf64_Sym));};
  auto symbolName=[&](const Elf64_Sym& s){require(s.st_name<strings.sh_size,"Invalid GPU symbol name");return strz(b,strings.sh_offset+s.st_name,strings.sh_offset+strings.sh_size);};
  for(size_t i=0;i<syms.sh_size/sizeof(Elf64_Sym);i++){
   auto f=symbol(i);auto name=symbolName(f);
   if(name!="adreno_device_probe"&&name!="adreno_of_parse_pwrlevels"&&name!="kgsl_bus_init"&&name!="gen7_build_rpmh_tables")continue;
   require(ELF64_ST_TYPE(f.st_info)==STT_FUNC&&f.st_shndx<sections.size()&&f.st_size>0&&f.st_size<=65536&&!result.count(name),"Invalid or duplicate GPU parser function");auto code=sections[f.st_shndx];
   require(code.sh_type==SHT_PROGBITS&&(code.sh_flags&SHF_EXECINSTR)&&f.st_value<=code.sh_size&&f.st_size<=code.sh_size-f.st_value,"GPU parser outside executable section");
   Bytes signature(b.begin()+code.sh_offset+f.st_value,b.begin()+code.sh_offset+f.st_value+f.st_size);
   for(const auto& rs:sections){if(rs.sh_type!=SHT_RELA||rs.sh_info!=f.st_shndx)continue;
    require(rs.sh_link==si&&rs.sh_entsize==sizeof(Elf64_Rela)&&rs.sh_size%sizeof(Elf64_Rela)==0,"Invalid GPU relocations");
    for(size_t p=0;p<rs.sh_size;p+=sizeof(Elf64_Rela)){auto r=elfRead<Elf64_Rela>(b,rs.sh_offset+p);if(r.r_offset<f.st_value||r.r_offset-f.st_value>=f.st_size)continue;
     auto target=symbol(ELF64_R_SYM(r.r_info));signatureNumber(signature,r.r_offset-f.st_value);signatureNumber(signature,ELF64_R_TYPE(r.r_info));signatureText(signature,symbolName(target));signatureNumber(signature,ELF64_ST_TYPE(target.st_info));
     if(target.st_shndx<sections.size()){
      auto data=sections[target.st_shndx];signatureText(signature,sectionName(data));
      if(data.sh_flags&SHF_STRINGS){require(r.r_addend>=0&&target.st_value<data.sh_size&&uint64_t(r.r_addend)<data.sh_size-target.st_value,"Invalid GPU string relocation");signatureText(signature,strz(b,data.sh_offset+target.st_value+r.r_addend,data.sh_offset+data.sh_size));}
      else signatureNumber(signature,r.r_addend);
     }else{signatureText(signature,"external");signatureNumber(signature,r.r_addend);}
    }
   }result.emplace(name,sha(signature));
  }
 }require(result.size()==4,"GPU driver is missing the audited Gen7 table-handling paths");return result;
}
inline void verifyDriver(const Bytes& b){
 // The earlier exact audited module remains supported even if its ELF names
 // were emitted differently. New builds use code + relocation identities.
 if(sha(b)=="16cb910e20983d9ff90c8eb11eb075310af50411d45853e7925282ac240f3ecc")return;
 const std::map<std::string,std::string> expected={
  {"adreno_device_probe","1b87e7b0475d53b218d02dd8862487af2e2632dded563887ddd119cc5a4cb012"},
  {"adreno_of_parse_pwrlevels","6ac176b1f23c0ed14f360f7bd16ede5ed1caf187e5900268477fe8cb746377a7"},
  {"kgsl_bus_init","48d4290339ec3139173960e1923c23bf29a22e5d834f64c31d8ee268e45712a2"},
  {"gen7_build_rpmh_tables","bcb8d646b77d137fdedaf17c6c98740fb3a2388d0f03b126c165989029e298d9"}
 };
 const auto actual=driverSignatures(b);
 for(const auto& p:expected)require(actual.at(p.first)==p.second,"GPU table-handling capability changed: "+p.first+". Saved profiles remain available; review this driver change before Apply");
}
