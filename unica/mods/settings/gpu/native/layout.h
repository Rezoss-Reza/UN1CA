// SPDX-License-Identifier: GPL-3.0-or-later
// Resolve FDT paths/property names at runtime; no firmware hashes or offsets.
inline Bytes referenceStrings(){
 Bytes strings(36032,0);
 for(const auto& p:std::map<size_t,std::string>{{6,"compatible"},{46,"#address-cells"},{61,"#size-cells"},{99,"reg"},{35871,"qcom,sku-codes"},{35886,"qcom,initial-pwrlevel"},{35908,"qcom,initial-min-pwrlevel"},{35934,"qcom,gpu-freq"},{35948,"qcom,level"},{35959,"qcom,bus-freq"},{35973,"qcom,bus-min"},{35986,"qcom,bus-max"},{35999,"qcom,acd-level"}})
  std::copy(p.second.begin(),p.second.end(),strings.begin()+p.first);
 return strings;
}
inline void relocateNames(FdtNode& node,const Bytes& b,size_t strings,size_t end){
 for(auto& p:node.properties){Bytes needle(p.name.begin(),p.name.end());needle.push_back(0);auto found=std::search(b.begin()+strings,b.begin()+end,needle.begin(),needle.end());require(found!=b.begin()+end,"Missing GPU property name in current FDT: "+p.name);p.nameOffset=found-(b.begin()+strings);}
 for(auto& child:node.children)relocateNames(child,b,strings,end);
}
inline FdtNode referenceBins(const Bytes& b,size_t strings,size_t end){
 auto names=referenceStrings();size_t p=0;auto ref=parseNode(STOCK_BINS,p,names,0,names.size());require(p==STOCK_BINS.size(),"Invalid logical GPU reference");relocateNames(ref,b,strings,end);return ref;
}
inline size_t locateBins(const Bytes& b,size_t dtb,size_t total,size_t sb,size_t ss,size_t strings,size_t ns){
 const std::string gpuPath="/soc/qcom,kgsl-3d0@3d00000",binPath=gpuPath+"/qcom,gpu-pwrlevel-bins";
 std::vector<std::string> stack;std::vector<std::map<std::string,bool>> seen;size_t p=sb,end=sb+ss,offset=0,binEnd=0;bool soc=false,chip=false,gen7=false,bus=false,ended=false;unsigned tokens=0;
 while(p<end){require(++tokens<200000,"Excessive FDT tokens");range(b,dtb+p,4);size_t start=p;auto token=be(b,dtb+p);p+=4;
  if(token==1){require(stack.size()<32,"Excessive FDT depth");auto name=strz(b,dtb+p,dtb+end);require(name.size()<=128,"Oversized FDT node name");p=align(p+name.size()+1,4);require(p<=end,"Truncated FDT node");std::string path=stack.empty()?name:stack.back()+"/"+name;stack.push_back(path);seen.emplace_back();if(path==binPath){require(offset==0,"Duplicate GPU bins");offset=start;}}
  else if(token==2){require(!stack.empty(),"Unbalanced FDT node");if(stack.back()==binPath)binEnd=p;stack.pop_back();seen.pop_back();}
  else if(token==3){require(!stack.empty()&&p+8<=end,"Invalid FDT property");auto n=be(b,dtb+p),off=be(b,dtb+p+4);p+=8;require(off<ns&&n<=end-p,"FDT property outside sections");auto name=strz(b,dtb+strings+off,dtb+strings+ns);Bytes value(b.begin()+dtb+p,b.begin()+dtb+p+n);p=align(p+n,4);require(p<=end,"Truncated FDT property padding");
   require(!seen.back().count(name),"Duplicate FDT property");seen.back()[name]=true;
   if(stack.back().empty()&&name=="compatible")soc=value==Bytes{'q','c','o','m',',','k','a','l','a','m','a',0};
   if(stack.back().empty()&&name=="qcom,msm-id")chip=value==Bytes{0,0,2,7,0,2,0,0};
   if(stack.back()==gpuPath){
    if(name=="compatible"){const std::string text="qcom,adreno-gpu-gen7-2-1";Bytes needle(text.begin(),text.end());needle.push_back(0);gen7=std::search(value.begin(),value.end(),needle.begin(),needle.end())!=value.end();}
    if(name=="qcom,bus-table-ddr")bus=n==40;
    if(name.find("qcom,bus-table-ddr")==0)require(n==40,"Changed GPU DDR table capacity; audit bus limits");
   }
  }else if(token==4)continue;
  else if(token==9){require(stack.empty(),"Unclosed FDT node");ended=true;break;}
  else throw std::runtime_error("Invalid FDT token");
 }
 require(ended&&soc&&chip&&gen7&&bus&&offset&&binEnd>offset,"Unsupported GPU capabilities: need Kalama v2, Gen7-2-1 and ten DDR bus levels");
 require(offset+BINS_SIZE<=sb+ss&&offset+BINS_SIZE<=total&&binEnd<=offset+BINS_SIZE,"GPU bins exceed the supported fixed edit capacity");
 for(size_t q=binEnd;q<offset+BINS_SIZE;q+=4)require(be(b,dtb+q)==4,"GPU edit capacity overlaps another FDT node");
 return offset;
}
