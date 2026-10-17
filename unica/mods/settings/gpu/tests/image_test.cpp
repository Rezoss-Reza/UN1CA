// SPDX-License-Identifier: GPL-3.0-or-later
#include "../native/image.h"
#include <fstream>
#include <iostream>
using namespace gpu;
extern "C" void* avb_memcpy(void* a,const void* b,size_t n){return memcpy(a,b,n);}
extern "C" void* avb_memset(void* a,int v,size_t n){return memset(a,v,n);}
static unsigned checks=0;
static void check(bool ok){require(ok,"Test assertion failed");++checks;}
template<class F> void denied(F f){bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}check(rejected);}
int main(int argc,char** argv){try{
 require(argc==3,"Usage: image-test original-vendor_boot.img output.img");std::ifstream in(argv[1],std::ios::binary);Bytes b((std::istreambuf_iterator<char>(in)),{});auto t=table(b);
 check(t.frequencies==STOCK_FREQ);check(t.levels==STOCK_LEVEL);
 check(sha(Bytes{'a','b','c'})=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
 auto f=STOCK_FREQ,l=STOCK_LEVEL;f[0]=700000000;l[0]=192;auto edited=edit(b,f,l);auto next=table(edited);check(next.frequencies==f);check(next.levels==l);
 // All bytes outside the selected u32 properties and AVB metadata are preserved.
 size_t footerOffset=b.size()-64,vbmeta=be64(b,footerOffset+20),oldSize=be64(b,footerOffset+28);
 for(size_t p=0;p<b.size();p++)if(b[p]!=edited[p]){
  bool allowed=(p>=vbmeta&&p<vbmeta+std::max<size_t>(oldSize,512))||(p>=footerOffset+28&&p<footerOffset+36);
  for(int i=0;i<10;i++)allowed=allowed||(p>=t.freq[i]&&p<t.freq[i]+4)||(p>=t.level[i]&&p<t.level[i]+4);
  check(allowed);
 }
 // Editing an already edited image stays recognized and repeatable.
 auto twice=edit(edited,STOCK_FREQ,STOCK_LEVEL);check(table(twice).frequencies==STOCK_FREQ);check(table(twice).levels==STOCK_LEVEL);
 denied([&]{Bytes c=b;c.resize(4096);table(c);});
 denied([&]{Bytes c=b;c[0]^=1;table(c);});
 denied([&]{Bytes c=b;c[t.dtb+200]^=1;table(c);});
 denied([&]{auto c=f;c[1]=c[0];edit(b,c,l);});
 denied([&]{auto c=f;c[0]=900000000;edit(b,c,l);});
 denied([&]{auto c=f;c[9]=124799999;edit(b,c,l);});
 denied([&]{auto c=l;c[0]=193;edit(b,f,c);});
 denied([&]{auto c=l;c[8]=224;edit(b,f,c);});
 denied([&]{Bytes c=b;c[footerOffset]='X';edit(c,f,l);});
 denied([&]{Bytes c=b;put64(c,footerOffset+20,UINT64_MAX);edit(c,f,l);});
 std::ofstream out(argv[2],std::ios::binary);out.write(reinterpret_cast<const char*>(edited.data()),edited.size());require(out.good(),"Cannot save test artifact");
 std::cout<<"PASS "<<checks<<" image, bounds, compatibility and preservation assertions\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
