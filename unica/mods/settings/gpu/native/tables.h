// SPDX-License-Identifier: GPL-3.0-or-later
// Restricted FDT subtree editor: both inspected bins, immutable SKU/header data.
#include "stock_bins.h"
struct GpuRow {uint32_t frequency=0,voltage=0,bus=0,minimum=0,maximum=0,acd=0;bool hasAcd=false;};
struct GpuBin {uint32_t initial=0,minimum=0;std::vector<GpuRow> rows;};
using GpuBins=std::array<GpuBin,2>;
struct FdtProperty {std::string name;Bytes value;uint32_t nameOffset=0;size_t offset=0;Bytes padding;};
struct FdtNode {std::string name;Bytes namePadding;std::vector<FdtProperty> properties;std::vector<FdtNode> children;};
inline void emit32(Bytes& b,uint32_t v){size_t p=b.size();b.resize(p+4);put(b,p,v);}
inline FdtNode parseNode(const Bytes& zone,size_t& p,const Bytes& image,size_t strings,size_t stringsEnd,int depth=0){
 require(depth<4&&be(zone,p)==1,"Invalid GPU node");p+=4;FdtNode node;node.name=strz(zone,p,zone.size());size_t nameEnd=p+node.name.size()+1;node.namePadding=Bytes(zone.begin()+nameEnd,zone.begin()+align(nameEnd,4));p=align(nameEnd,4);
 for(;;){uint32_t token=be(zone,p);p+=4;
  if(token==2)return node;
  if(token==4)continue;
  if(token==1){p-=4;require(node.children.size()<16,"Too many GPU nodes");node.children.push_back(parseNode(zone,p,image,strings,stringsEnd,depth+1));continue;}
  require(token==3&&node.properties.size()<16,"Unexpected GPU subtree token");uint32_t size=be(zone,p),off=be(zone,p+4);p+=8;range(zone,p,size);require(strings+off<stringsEnd,"Bad GPU property name");
  FdtProperty v{strz(image,strings+off,stringsEnd),Bytes(zone.begin()+p,zone.begin()+p+size),off,p,Bytes(zone.begin()+p+size,zone.begin()+align(p+size,4))};for(auto& old:node.properties)require(old.name!=v.name,"Duplicate GPU property");node.properties.push_back(v);p=align(p+size,4);
 }
}
inline const FdtProperty* property(const FdtNode& n,const std::string& name){for(auto& p:n.properties)if(p.name==name)return &p;return nullptr;}
inline uint32_t cell(const FdtNode& n,const std::string& name){auto p=property(n,name);require(p&&p->value.size()==4,"Missing or malformed GPU cell: "+name);return be(p->value,0);}
inline bool sameProperties(const FdtNode& a,const FdtNode& b,bool header=false){
 if(a.properties.size()!=b.properties.size())return false;
 for(auto& p:a.properties){auto q=property(b,p.name);if(!q)return false;if(header&&(p.name=="qcom,initial-pwrlevel"||p.name=="qcom,initial-min-pwrlevel")){if(p.value.size()!=4)return false;}else if(p.value!=q->value)return false;}return true;
}
inline void serialize(const FdtNode& n,Bytes& out){
 emit32(out,1);out.insert(out.end(),n.name.begin(),n.name.end());out.push_back(0);size_t padding=align(out.size(),4)-out.size();if(n.namePadding.size()==padding)out.insert(out.end(),n.namePadding.begin(),n.namePadding.end());else out.resize(align(out.size(),4),0);
 for(auto& p:n.properties){emit32(out,3);emit32(out,p.value.size());emit32(out,p.nameOffset);out.insert(out.end(),p.value.begin(),p.value.end());size_t extra=align(out.size(),4)-out.size();if(p.padding.size()==extra)out.insert(out.end(),p.padding.begin(),p.padding.end());else out.resize(align(out.size(),4),0);}
 for(auto& c:n.children){serialize(c,out);}emit32(out,2);
}
inline void validateBin(const GpuBin& bin){
 require(bin.rows.size()>=2&&bin.rows.size()<=15,"Each GPU table needs 2–15 levels (GMU reserves an off level)");
 require(bin.initial<bin.rows.size()&&bin.minimum<bin.rows.size(),"GPU initial/minimum index is outside its table");
 for(size_t i=0;i<bin.rows.size();i++){auto& r=bin.rows[i];require(r.frequency>0,"Zero frequency is skipped by the driver");require(r.voltage>=1&&r.voltage<=480,"RPMh request must be 1–480; this is an editor limit, not a hardware guarantee");
  require(r.minimum<=r.bus&&r.bus<=r.maximum,"DDR bus requests must satisfy min <= frequency <= max");require(i+1<bin.rows.size()||r.maximum<=9,"The final GPU row bypasses the driver bus clamp and requires bus indices 0–9");
  if(i)require(r.frequency<bin.rows[i-1].frequency,"GPU frequencies must strictly descend");
 }
}
inline GpuBins decodeBins(const FdtNode& node,const FdtNode& reference){
 require(node.name==reference.name&&sameProperties(node,reference)&&node.children.size()==2,"GPU bin container changed");GpuBins bins;
 for(int id=0;id<2;id++){
  auto& n=node.children[id];auto& ref=reference.children[id];require(n.name==ref.name&&sameProperties(n,ref,true),"Unsupported GPU table or changed SKU selection");auto& bin=bins[id];bin.initial=cell(n,"qcom,initial-pwrlevel");bin.minimum=cell(n,"qcom,initial-min-pwrlevel");
  for(size_t i=0;i<n.children.size();i++){auto& row=n.children[i];require(row.name=="qcom,gpu-pwrlevel@"+std::to_string(i)&&row.children.empty(),"GPU indices must be contiguous");
   for(auto& p:row.properties)require(p.name=="reg"||p.name=="qcom,gpu-freq"||p.name=="qcom,level"||p.name=="qcom,bus-freq"||p.name=="qcom,bus-min"||p.name=="qcom,bus-max"||p.name=="qcom,acd-level","Unsupported GPU property");
   require(cell(row,"reg")==i,"GPU reg index mismatch");GpuRow r;r.frequency=cell(row,"qcom,gpu-freq");r.voltage=cell(row,"qcom,level");r.bus=cell(row,"qcom,bus-freq");r.minimum=cell(row,"qcom,bus-min");r.maximum=cell(row,"qcom,bus-max");r.hasAcd=property(row,"qcom,acd-level")!=nullptr;if(r.hasAcd)r.acd=cell(row,"qcom,acd-level");bin.rows.push_back(r);
  }validateBin(bin);
 }return bins;
}
inline void setCell(FdtNode& node,const FdtNode& ref,const std::string& name,uint32_t value){
 auto p=property(ref,name);require(p,"Missing reference GPU property");FdtProperty v=*p;v.value.resize(4);put(v.value,0,value);node.properties.push_back(v);
}
inline FdtNode encodeBins(const GpuBins& bins,const FdtNode& reference){
 FdtNode root=reference;
 for(int id=0;id<2;id++){
  validateBin(bins[id]);auto& table=root.children[id];for(auto& p:table.properties){if(p.name=="qcom,initial-pwrlevel")put(p.value,0,bins[id].initial);if(p.name=="qcom,initial-min-pwrlevel")put(p.value,0,bins[id].minimum);}
  table.children.clear();const auto& ref=reference.children[id].children[0];
  for(size_t i=0;i<bins[id].rows.size();i++){auto& r=bins[id].rows[i];FdtNode row;row.name="qcom,gpu-pwrlevel@"+std::to_string(i);
   if(r.hasAcd)setCell(row,ref,"qcom,acd-level",r.acd);
   setCell(row,ref,"qcom,bus-max",r.maximum);setCell(row,ref,"qcom,bus-min",r.minimum);setCell(row,ref,"qcom,bus-freq",r.bus);setCell(row,ref,"qcom,level",r.voltage);setCell(row,ref,"qcom,gpu-freq",r.frequency);setCell(row,ref,"reg",i);table.children.push_back(row);
  }
 }return root;
}
inline Bytes renderBins(const GpuBins& bins,const FdtNode& reference){
 Bytes out;serialize(encodeBins(bins,reference),out);require(out.size()<=BINS_SIZE,"GPU tables exceed the original FDT subtree space; remove a level before adding another");while(out.size()<BINS_SIZE)emit32(out,4);return out;
}
