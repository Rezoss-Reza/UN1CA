// SPDX-License-Identifier: GPL-3.0-or-later
#define main worker_main
#include "../native/worker.cpp"
#undef main
#include <cassert>
#include <fstream>
int main(int argc,char** argv){
    assert(argc==2);std::string dir=argv[1];
    assert(certName("0f4ed297.0"));assert(certName("0f4ed297.10"));
    assert(!certName("../enabled"));assert(!certName("0f4ed297.tmp"));assert(!certName("0F4ED297.0"));
    assert(personalName(dir.c_str()).empty());
    writeFile(dir+"/0f4ed297.2","a",0644);writeFile(dir+"/0f4ed297.10","b",0644);
    writeFile(dir+"/47ec1af8.99","intermediate",0644);
    assert(personalName(dir.c_str())=="0f4ed297.10");
    std::string first="stock root\n",second="imported root\n";
    add(dir,"12345678.0",first);add(dir,"12345678.0",second);add(dir,"12345678.0",first);
    assert(readFile(dir+"/12345678.0")==first);assert(readFile(dir+"/12345678.1")==second);assert(names(dir.c_str()).size()==5);
    assert(symlink("12345678.0",(dir+"/12345678.2").c_str())==0);
    bool rejected=false;try{readFile(dir+"/12345678.2");}catch(...){rejected=true;}assert(rejected);
    std::ofstream large(dir+"/12345678.3");large<<std::string(65537,'x');large.close();
    rejected=false;try{readFile(dir+"/12345678.3");}catch(...){rejected=true;}assert(rejected);
    puts("PASS Personal CA selection, Intermediate exclusion, numeric ordering, collision and input checks");
}
