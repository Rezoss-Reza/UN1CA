// SPDX-License-Identifier: GPL-3.0-or-later
// Bounded, fixed-size dm3q DTB edits. No DTS text rewriting or ramdisk repacking.
#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" {
#include "avb_sha.h"
}
namespace gpu {
using Bytes = std::vector<uint8_t>;
using Values = std::array<uint32_t,10>;
inline constexpr size_t IMAGE_SIZE=100663296;
inline constexpr Values STOCK_FREQ={719000000,680000000,615000000,550000000,475000000,401000000,348000000,295000000,220000000,124800000};
inline constexpr Values STOCK_LEVEL={224,192,144,128,80,64,60,56,52,52};
inline constexpr uint32_t CORNERS[]={52,56,60,64,80,128,144,192,224};
inline const std::string DTB_HASH="99066fde3eede3bd5e17ba0a07182ebf06fda2fbf775003890c938af4e8fe23b";
inline void require(bool ok,const std::string& s){if(!ok)throw std::runtime_error(s);}
inline void range(const Bytes& b,size_t o,size_t n){require(o<=b.size()&&n<=b.size()-o,"Truncated image");}
inline uint32_t be(const Bytes& b,size_t o){range(b,o,4);return uint32_t(b[o])<<24|uint32_t(b[o+1])<<16|uint32_t(b[o+2])<<8|b[o+3];}
inline uint64_t be64(const Bytes& b,size_t o){return uint64_t(be(b,o))<<32|be(b,o+4);}
inline uint32_t le(const Bytes& b,size_t o){range(b,o,4);return uint32_t(b[o+3])<<24|uint32_t(b[o+2])<<16|uint32_t(b[o+1])<<8|b[o];}
inline void put(Bytes& b,size_t o,uint32_t v){range(b,o,4);for(int i=3;i>=0;i--){b[o+i]=v&255;v>>=8;}}
inline void put64(Bytes& b,size_t o,uint64_t v){put(b,o,v>>32);put(b,o+4,v);}
inline size_t align(size_t n,size_t a){return (n+a-1)/a*a;}
inline std::string sha(const Bytes& b,size_t o=0,size_t n=SIZE_MAX){if(n==SIZE_MAX)n=b.size()-o;range(b,o,n);AvbSHA256Ctx c;avb_sha256_init(&c);avb_sha256_update(&c,b.data()+o,n);auto d=avb_sha256_final(&c);const char* hex="0123456789abcdef";std::string out;for(int i=0;i<32;i++){out+=hex[d[i]>>4];out+=hex[d[i]&15];}return out;}
inline Bytes digest(const Bytes& b){AvbSHA256Ctx c;avb_sha256_init(&c);avb_sha256_update(&c,b.data(),b.size());auto p=avb_sha256_final(&c);return Bytes(p,p+32);}
inline std::string strz(const Bytes& b,size_t o,size_t end){require(o<end&&end<=b.size(),"Bad string bounds");size_t p=o;while(p<end&&b[p])p++;require(p<end,"Unterminated device-tree string");return std::string(b.begin()+o,b.begin()+p);}
#include "tables.h"
struct Table {size_t dtb=0,size=0;std::array<size_t,10> freq{},level{};Values frequencies{},levels{};GpuBins bins;FdtNode reference;};
inline Table table(const Bytes& b){
 require(b.size()==IMAGE_SIZE,"Unsupported vendor_boot partition size");
 require(std::memcmp(b.data(),"VNDRBOOT",8)==0&&le(b,8)==4&&le(b,12)==4096&&le(b,2096)==2128,"Unsupported vendor_boot header");
 require(strz(b,2080,2096)=="SRPVH05C010","Unsupported vendor_boot board");
 Table t;t.dtb=4096+align(le(b,24),4096);t.size=le(b,2100);range(b,t.dtb,t.size);
 require(t.size==1808679,"Firmware DTB set is unsupported");require(be(b,t.dtb)==0xd00dfeed,"Missing base DTB");
 size_t total=be(b,t.dtb+4);range(b,t.dtb,total);require(total<=t.size,"Invalid DTB length");
 size_t sb=be(b,t.dtb+8),ss=be(b,t.dtb+36),strings=be(b,t.dtb+12),ns=be(b,t.dtb+32);
 require(sb<=total&&ss<=total-sb&&strings<=total&&ns<=total-strings&&BINS_OFFSET>=sb&&BINS_OFFSET+BINS_SIZE<=sb+ss,"Bad FDT sections");
 Bytes normalized(b.begin()+t.dtb,b.begin()+t.dtb+t.size);std::copy(STOCK_BINS.begin(),STOCK_BINS.end(),normalized.begin()+BINS_OFFSET);
 require(sha(normalized)==DTB_HASH,"Unsupported firmware or unrelated DTB changes; refusing write");
 size_t p=0;t.reference=parseNode(STOCK_BINS,p,b,t.dtb+strings,t.dtb+strings+ns);require(p==STOCK_BINS.size(),"Bad reference GPU template");
 Bytes zone(b.begin()+t.dtb+BINS_OFFSET,b.begin()+t.dtb+BINS_OFFSET+BINS_SIZE);p=0;auto node=parseNode(zone,p,b,t.dtb+strings,t.dtb+strings+ns);while(p<zone.size()){require(be(zone,p)==4,"Unexpected data after GPU bins");p+=4;}t.bins=decodeBins(node,t.reference);
 for(size_t i=0;i<std::min<size_t>(10,t.bins[0].rows.size());i++){auto& row=t.bins[0].rows[i];t.frequencies[i]=row.frequency;t.levels[i]=row.voltage;t.freq[i]=t.dtb+BINS_OFFSET+property(node.children[0].children[i],"qcom,gpu-freq")->offset;t.level[i]=t.dtb+BINS_OFFSET+property(node.children[0].children[i],"qcom,level")->offset;}
 return t;
}
inline bool stockTables(const Bytes& b){auto t=table(b);return std::equal(STOCK_BINS.begin(),STOCK_BINS.end(),b.begin()+t.dtb+BINS_OFFSET);}
inline void validate(const Values& f,const Values& l){
 for(int i=0;i<10;i++){require(f[i]>0,"GPU frequency must be a positive u32 Hz value");require(l[i]>=1&&l[i]<=480,"RPMh request must be 1–480");if(i)require(f[i]<f[i-1],"Frequencies must strictly descend");}
}
// Replace the embedded, now-invalid Samsung signature with an unsigned SHA256
// hash footer. This is allowed ONLY with raw top-level vbmeta verification disabled.
// Layout follows AOSP libavb structs; host tests use avbtool to verify the result.
inline void footer(Bytes& b){
 size_t fo=b.size()-64;require(std::memcmp(b.data()+fo,"AVBf",4)==0,"Missing AVB footer");
 uint64_t original=be64(b,fo+12),vo=be64(b,fo+20),vs=be64(b,fo+28);
 require(original>4096&&original<=vo&&vo%4096==0&&vo<b.size()-4096&&vs>=256&&vs<=4096,"Unsupported AVB layout");
 range(b,vo,vs);require(std::memcmp(b.data()+vo,"AVB0",4)==0,"Missing embedded vbmeta");
 size_t aux=vo+256+be64(b,vo+12),desc=aux+be64(b,vo+96),dlen=be64(b,vo+104);
 range(b,desc,dlen);require(dlen>=132&&be64(b,desc)==2,"Unsupported AVB descriptor");
 require(be64(b,desc+16)==original&&strz(b,desc+24,desc+56)=="sha256"&&be(b,desc+56)==11&&be(b,desc+60)==32&&be(b,desc+64)==32,"Unsupported vendor_boot hash descriptor");
 range(b,desc,207);require(std::memcmp(b.data()+desc+132,"vendor_boot",11)==0,"Wrong AVB partition");
 Bytes salt(b.begin()+desc+143,b.begin()+desc+175),data=salt;data.insert(data.end(),b.begin(),b.begin()+original);Bytes d=digest(data);
 Bytes vb(512,0);std::memcpy(vb.data(),"AVB0",4);put(vb,4,1);put64(vb,20,256);put64(vb,104,208);put64(vb,112,be64(b,vo+112));
 std::memcpy(vb.data()+128,"UN1CA GPU trial",15);size_t q=256;put64(vb,q,2);put64(vb,q+8,192);put64(vb,q+16,original);
 std::memcpy(vb.data()+q+24,"sha256",6);put(vb,q+56,11);put(vb,q+60,32);put(vb,q+64,32);put(vb,q+68,be(b,desc+68));
 std::memcpy(vb.data()+q+132,"vendor_boot",11);std::copy(salt.begin(),salt.end(),vb.begin()+q+143);std::copy(d.begin(),d.end(),vb.begin()+q+175);
 std::fill(b.begin()+vo,b.begin()+vo+std::max<size_t>(vs,vb.size()),0);std::copy(vb.begin(),vb.end(),b.begin()+vo);put64(b,fo+28,vb.size());
}
inline Bytes editBins(const Bytes& original,const GpuBins& bins){auto t=table(original);auto zone=renderBins(bins,t.reference);Bytes b=original;std::copy(zone.begin(),zone.end(),b.begin()+t.dtb+BINS_OFFSET);footer(b);auto next=table(b);require(renderBins(next.bins,next.reference)==zone,"GPU table readback validation failed");return b;}
inline Bytes edit(const Bytes& original,const Values& f,const Values& l){validate(f,l);auto t=table(original);require(t.bins[0].rows.size()==10,"Legacy Apply needs ten table-0 levels");for(int i=0;i<10;i++){t.bins[0].rows[i].frequency=f[i];t.bins[0].rows[i].voltage=l[i];}return editBins(original,t.bins);}
inline uint32_t crc32(const Bytes& b){uint32_t c=~0U;for(auto v:b){c^=v;for(int i=0;i<8;i++)c=c>>1^(0xedb88320U&-(c&1));}return ~c;}
} // namespace gpu
