// SPDX-License-Identifier: GPL-3.0-or-later
// Refuse redirected archive paths before copying or deleting any source file.
#define GPU_TEST
#define main gpu_worker_main
#include "../native/worker.cpp"
#undef main
#include <filesystem>
#include <iostream>
int main(int argc,char** argv){try{
 require(argc==2,"storage-paths-test temporary-directory");
 const std::string root=std::string(argv[1])+"/";
 BASE=root+"cache/";IPC=root+"ipc/";ARCHIVES=IPC+"archives/";
 std::filesystem::create_directories(BASE);std::filesystem::create_directories(IPC);
 std::filesystem::create_directories(root+"unrelated/");atomic(root+"unrelated/original.img","keep this file");
 auto rejects=[](auto operation){bool refused=false;try{operation();}catch(const std::exception&){refused=true;}require(refused,"Symlink archive path was accepted");};
 std::filesystem::create_directory_symlink(root+"unrelated",BASE+"archives");
 rejects([]{migrateLegacyArchives();});
 std::filesystem::remove(BASE+"archives");directory(BASE+"archives/");
 const std::string id=std::string(64,'a')+"-"+std::string(64,'b');Renewal r{std::string(64,'a'),std::string(64,'c'),id};
 std::filesystem::create_directory_symlink(root+"unrelated",BASE+"archives/"+id);
 rejects([]{migrateLegacyArchives();});
 std::filesystem::create_directory_symlink(root+"unrelated",IPC+"archives");
 rejects([&]{copyArchive(BASE,r);});
 std::filesystem::remove(IPC+"archives");directory(ARCHIVES);
 std::filesystem::create_directory_symlink(root+"unrelated",ARCHIVES+id);
 rejects([&]{copyArchive(BASE,r);});
 require(text(root+"unrelated/original.img")=="keep this file","Unrelated source file changed");
 std::cout<<"PASS four symlink guards and unrelated-file preservation\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
