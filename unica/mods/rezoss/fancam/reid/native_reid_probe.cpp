// SPDX-License-Identifier: Apache-2.0
// Isolated probe for the inspected Samsung RTMCC and cosine-distance ABI.
#include <cmath>
#include <cstdio>
#include <dlfcn.h>
#include <fstream>
#include <vector>

struct Point { float x, y, score; };
struct PreprocessInfo { float scale[2]; int padding[2]; int offset[2]; int size[2]; };
static_assert(sizeof(Point) == 12);
static_assert(sizeof(PreprocessInfo) == 32);

static bool read_tensor(const char* path, std::vector<float>& values, size_t count) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file || file.tellg() != static_cast<std::streamoff>(count * sizeof(float))) return false;
    file.seekg(0);
    values.resize(count);
    if (!file.read(reinterpret_cast<char*>(values.data()), count * sizeof(float))) return false;
    for (float value : values) if (!std::isfinite(value)) return false;
    return true;
}

int main(int argc, char** argv) {
    if (argc != 5 && argc != 6) {
        std::fprintf(stderr, "Usage: native_reid_probe LIBRARY POSE FEATURE_A FEATURE_B [GEOMETRY]\n");
        return 2;
    }
    std::vector<float> pose, feature_a, feature_b;
    if (!read_tensor(argv[2], pose, 174 * 640) || !read_tensor(argv[3], feature_a, 128) ||
        !read_tensor(argv[4], feature_b, 128)) return 2;
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    using Constructor = void (*)(void*, const long*);
    using Decode = int (*)(void*, Point*, int, const float*, const long*, const PreprocessInfo&);
    using Distance = float (*)(const void*, const std::vector<float>&, const std::vector<float>&);
    auto constructor = reinterpret_cast<Constructor>(dlsym(library, "_ZN2sr7reframe9PoseRTMCCC2EPKl"));
    auto decode = reinterpret_cast<Decode>(dlsym(library, "_ZNK2sr7reframe9PoseRTMCC6decodeEPNS0_11Keypoint2DfEiPKfPKlRKNS_5image14PreprocessInfoE"));
    auto distance = reinterpret_cast<Distance>(dlsym(library, "_ZNK2sr7reframe8tracking6Target25calculate_cosine_distanceERKNSt3__16vectorIfNS3_9allocatorIfEEEES9_"));
    if (!constructor || !decode || !distance) return 1;
    alignas(16) unsigned char decoder[64] = {};
    const long input_shape[] = {1, 3, 320, 320};
    const long output_shape[] = {1, 174, 640, 1};
    PreprocessInfo info = {{1, 1}, {0, 0}, {0, 0}, {320, 320}};
    if (argc == 6) {
        std::ifstream geometry(argv[5], std::ios::binary);
        if (!geometry.read(reinterpret_cast<char*>(&info), sizeof(info)) || geometry.peek() != EOF) return 2;
        if (!(std::isfinite(info.scale[0]) && std::isfinite(info.scale[1]) &&
              info.scale[0] > 0 && info.scale[1] > 0)) return 2;
    }
    Point points[87] = {};
    constructor(decoder, input_shape);
    int result = decode(decoder, points, 87, pose.data(), output_shape, info);
    float cosine_distance = distance(nullptr, feature_a, feature_b);
    if (!std::isfinite(cosine_distance)) return 1;
    std::printf("{\"decode_status\":%d,\"cosine_distance\":%.8f,\"points\":[", result, cosine_distance);
    for (size_t i = 0; i < 87; ++i) {
        if (!std::isfinite(points[i].x) || !std::isfinite(points[i].y) || !std::isfinite(points[i].score)) return 1;
        if (i) std::printf(",");
        std::printf("[%.5f,%.5f,%.8f]", points[i].x, points[i].y, points[i].score);
    }
    std::printf("]}\n");
    return 0;
}
