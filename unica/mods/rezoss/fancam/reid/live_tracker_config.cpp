// SPDX-License-Identifier: Apache-2.0
// Read only structural configuration from the hash-pinned Video Editor tracker.
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

struct Mapping { uint64_t begin, end; bool heap; };
template<class T> T value(const void* data, size_t offset) {
    T result;
    std::memcpy(&result, static_cast<const unsigned char*>(data) + offset, sizeof(result));
    return result;
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    int pid = 0;
    if (std::sscanf(argv[1], "%d", &pid) != 1 || pid <= 1) return 2;
    std::string root = "/proc/" + std::to_string(pid);
    std::ifstream name(root + "/cmdline");
    std::string command;
    std::getline(name, command, '\0');
    if (command != "com.sec.android.app.vepreload") return 2;
    std::ifstream maps(root + "/maps");
    std::vector<Mapping> mappings;
    std::string line;
    uint64_t base = 0;
    while (std::getline(maps, line)) {
        unsigned long long begin, end, offset;
        char permissions[5]{};
        int consumed = 0;
        if (std::sscanf(line.c_str(), "%llx-%llx %4s %llx %*s %*s %n", &begin, &end, permissions, &offset, &consumed) != 4) return 2;
        if (line.find("/system/lib64/libauto-reframing-arm64-v8a.so") != std::string::npos && offset == 0) base = begin;
        if (permissions[0] == 'r') mappings.push_back({begin, end,
            permissions[1] == 'w' && (line.find("[anon:libc_malloc]") != std::string::npos ||
                (consumed > 0 && line.find_first_not_of(" \t", consumed) == std::string::npos && end - begin <= 64ULL * 1024 * 1024))});
    }
    if (!base) return 3;
    int memory = open((root + "/mem").c_str(), O_RDONLY | O_CLOEXEC);
    if (memory < 0) { std::perror("open process memory"); return 4; }
    auto read_memory = [&](uint64_t address, void* data, size_t bytes) {
        return pread(memory, data, bytes, address & 0x00ffffffffffffffULL) == static_cast<ssize_t>(bytes);
    };
    // ELF vtable plus two ABI header pointers, verified against the pinned donor.
    uint64_t vtable = base + 0x79b18;
    uint64_t methods[4]{};
    if (!read_memory(vtable, methods, sizeof(methods)) || methods[0] != base + 0x4fef8 || methods[2] != base + 0x50468 || methods[3] != base + 0x504b4) {
        close(memory);
        std::fprintf(stderr, "Pinned vtable mismatch\n");
        return 5;
    }
    std::array<unsigned char, 65536> buffer{};
    std::vector<uint64_t> candidates;
    uint64_t scanned = 0;
    for (const auto& mapping : mappings) {
        if (!mapping.heap) continue;
        if (mapping.end - mapping.begin > 512ULL * 1024 * 1024) return 6;
        for (uint64_t address = mapping.begin; address < mapping.end; address += buffer.size()) {
            size_t bytes = std::min<uint64_t>(buffer.size(), mapping.end - address);
            if (!read_memory(address, buffer.data(), bytes)) continue;
            scanned += bytes;
            for (size_t offset = 0; offset + 8 <= bytes; offset += 8)
                if ((value<uint64_t>(buffer.data(), offset) & 0x00ffffffffffffffULL) == vtable) candidates.push_back(address + offset);
        }
    }
    std::printf("{\"pid\":%d,\"heap_bytes_scanned\":%llu,\"vtable_candidates\":%zu,\"objects\":[", pid, static_cast<unsigned long long>(scanned), candidates.size());
    bool first = true;
    for (uint64_t address : candidates) {
        std::array<unsigned char, 0x1290> object{};
        if (!read_memory(address, object.data(), object.size()) || value<uint64_t>(object.data(), 0) != vtable) continue;
        int width = value<int>(object.data(), 0x1100), height = value<int>(object.data(), 0x1104);
        int tracker_width = value<int>(object.data(), 0x11e0), tracker_height = value<int>(object.data(), 0x11e4);
        if (width < 0 || width > 8192 || height < 0 || height > 8192 || tracker_width < 0 || tracker_width > 8192 || tracker_height < 0 || tracker_height > 8192) continue;
        if (!first) std::printf(",");
        first = false;
        std::printf("{\"config_words\":[");
        for (size_t offset = 0x1100; offset < 0x1110; offset += 4)
            std::printf("%s%u", offset == 0x1100 ? "" : ",", value<uint32_t>(object.data(), offset));
        std::printf("],\"tracker_parameter_words\":[");
        for (size_t offset = 0x1140; offset < 0x11e0; offset += 4)
            std::printf("%s%u", offset == 0x1140 ? "" : ",", value<uint32_t>(object.data(), offset));
        std::printf("],\"tracker_dimensions\":[%d,%d],\"last_update_us\":%lld}", tracker_width, tracker_height, static_cast<long long>(value<int64_t>(object.data(), 0x1260)));
    }
    close(memory);
    std::printf("]}\n");
    return 0;
}
