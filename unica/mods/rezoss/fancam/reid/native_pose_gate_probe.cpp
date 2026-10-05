// SPDX-License-Identifier: Apache-2.0
// Batch-call only the inspected, stateless 87-point pose/face validators.
#include <cmath>
#include <cstdio>
#include <dlfcn.h>
#include <fstream>

struct Point { float x, y, score; };
struct Pose { float header[3]; Point points[87]; };
static_assert(sizeof(Pose) == 1056);

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    using Verify = bool (*)(const Pose&);
    auto pose_gate = reinterpret_cast<Verify>(dlsym(library,
        "_ZN2sr7reframe11verify_poseERKNS0_15pose_template_tILi87ENS0_11Keypoint2DfENS0_13InstanceFloatEEE"));
    auto face_gate = reinterpret_cast<Verify>(dlsym(library,
        "_ZN2sr7reframe11verify_faceERKNS0_15pose_template_tILi87ENS0_11Keypoint2DfENS0_13InstanceFloatEEE"));
    if (!pose_gate || !face_gate) return 1;
    std::ifstream input(argv[2], std::ios::binary | std::ios::ate);
    if (!input || input.tellg() <= 0 || input.tellg() % sizeof(Pose)) return 2;
    input.seekg(0);
    Pose pose{};
    unsigned index = 0;
    while (input.read(reinterpret_cast<char*>(&pose), sizeof(pose))) {
        for (const auto& point : pose.points)
            if (!std::isfinite(point.x) || !std::isfinite(point.y) ||
                !std::isfinite(point.score)) return 2;
        std::printf("%u %d %d\n", index++, pose_gate(pose), face_gate(pose));
    }
    return input.eof() ? 0 : 2;
}
