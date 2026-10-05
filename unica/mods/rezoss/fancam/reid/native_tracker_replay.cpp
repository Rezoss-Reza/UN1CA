// SPDX-License-Identifier: Apache-2.0
// Isolated replay of the hash-pinned Samsung tracker, without app instrumentation.
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <stdexcept>
#include <vector>

struct Point { float x, y, score; };
struct Pose87 { unsigned char header[12]; Point points[87]; };
struct Pose83 { unsigned char header[12]; Point points[83]; };
struct Detection { float score; int category; float box[4], normalized[4]; };
struct alignas(8) Target { unsigned char bytes[216]; };
struct alignas(8) Tracker { unsigned char bytes[384]; };
using BoxFrame = std::array<unsigned char, 32>;
static_assert(sizeof(Pose87) == 1056 && sizeof(Pose83) == 1008);
static_assert(sizeof(Detection) == 40 && sizeof(Target) == 216);

template<class T> T get(const void* p, size_t offset) {
    T value;
    std::memcpy(&value, static_cast<const unsigned char*>(p) + offset, sizeof(value));
    return value;
}
template<class T> void put(void* p, size_t offset, T value) {
    std::memcpy(static_cast<unsigned char*>(p) + offset, &value, sizeof(value));
}
template<class T> T symbol(void* library, const char* name) {
    void* function = dlsym(library, name);
    if (!function) throw std::runtime_error(name);
    return reinterpret_cast<T>(function);
}
template<class T> bool read(std::ifstream& input, T& value) {
    return static_cast<bool>(input.read(reinterpret_cast<char*>(&value), sizeof(value)));
}
struct Span { unsigned char* begin; unsigned char* end; unsigned char* capacity; };
Span span(const void* object, size_t offset, size_t item) {
    Span result = get<Span>(object, offset);
    uintptr_t begin = reinterpret_cast<uintptr_t>(result.begin), end = reinterpret_cast<uintptr_t>(result.end);
    if (end < begin || (end - begin) % item || (end - begin) / item > 2048)
        throw std::runtime_error("Invalid inspected vector");
    return result;
}
size_t count(const void* object, size_t offset, size_t item) {
    auto values = span(object, offset, item);
    return (reinterpret_cast<uintptr_t>(values.end) - reinterpret_cast<uintptr_t>(values.begin)) / item;
}

int main(int argc, char** argv) {
    if (argc < 3 || argc > 6) return 2;
    try {
        void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        auto ctor = symbol<void (*)(void*)>(library, "_ZN2sr7reframe8tracking7TrackerC2Ev");
        auto dtor = symbol<void (*)(void*)>(library, "_ZN2sr7reframe8tracking7TrackerD2Ev");
        auto target_ctor = symbol<void (*)(void*, const Detection&, const Pose83&, const std::vector<float>&, int, long)>(library,
            "_ZN2sr7reframe8tracking6TargetC2ERKNS0_12DetectionBoxERKNS0_15pose_template_tILi83ENS0_11Keypoint2DfENS0_13InstanceFloatEEERKNSt3__16vectorIfNSC_9allocatorIfEEEEil");
        auto target_dtor = symbol<void (*)(void*)>(library, "_ZN2sr7reframe8tracking6TargetD2Ev");
        auto add = symbol<int (*)(void*, void*)>(library, "_ZN2sr7reframe8tracking7Tracker10add_targetEONS1_6TargetE");
        auto update = symbol<void* (*)(void*, long)>(library, "_ZN2sr7reframe8tracking7Tracker6updateEl");
        auto init_match = symbol<std::vector<int> (*)(void*)>(library, "_ZN2sr7reframe8tracking7Tracker10init_matchEv");
        auto dynamic_gate = symbol<bool (*)(void*, int, int, float)>(library,
            "_ZN2sr7reframe8tracking7Tracker29disable_track_by_dynamic_distEiif");
        auto convert_box = symbol<Detection (*)(const void*)>(library,
            "_ZN2sr7reframe21conversion_operator_tINS0_10box_type_tIsEENS0_12DetectionBoxEE4fromERKS3_");
        auto convert_pose = symbol<Pose87 (*)(const void*)>(library,
            "_ZN2sr7reframe21conversion_operator_tINS0_11pose_type_tIsLi87EEENS0_15pose_template_tILi87ENS0_11Keypoint2DfENS0_13InstanceFloatEEEE4fromERKS3_");
        auto boxed_gate = symbol<bool (*)(const Detection&, const Pose83&)>(library,
            "_ZN2sr7reframe11verify_poseERKNS0_12DetectionBoxERKNS0_15pose_template_tILi83ENS0_11Keypoint2DfENS0_13InstanceFloatEEE");
        auto activation = symbol<float (*)(const Detection&, const Pose83&)>(library,
            "_ZN2sr7reframe22keypoint_activation_21ERKNS0_12DetectionBoxERKNS0_15pose_template_tILi83ENS0_11Keypoint2DfENS0_13InstanceFloatEEE");
        auto cost = symbol<uint64_t (*)(const void*, const void*)>(library, "_ZNK2sr7reframe8tracking6TargetanERKS2_");
        auto expired = symbol<bool (*)(const void*, const void*, long)>(library, "_ZNK2sr7reframe8tracking6Target7expiredERKS2_l");
        auto clean = symbol<bool (*)(const void*, float&, const std::vector<BoxFrame>&, const std::vector<BoxFrame>&, int, int)>(library,
            "_ZNK2sr7reframe8tracking6Target16is_clean_featureERfRKNSt3__16vectorINS1_8BoxFrameENS4_9allocatorIS6_EEEESB_ii");
        std::ifstream input(argv[2], std::ios::binary);
        uint32_t magic, frames;
        if (!read(input, magic) || magic != 0x31525046 || !read(input, frames) || frames > 1000) return 2;
        Tracker tracker{};
        ctor(&tracker);
        bool normalize_features = false;
        for (int argument = 3; argument < argc; ++argument) {
            if (!std::strncmp(argv[argument], "parameters=", 11)) {
                std::ifstream parameters(argv[argument] + 11, std::ios::binary);
                if (!parameters.read(reinterpret_cast<char*>(tracker.bytes + 0x30), 160) || parameters.peek() != EOF) return 2;
                continue;
            }
            if (!std::strcmp(argv[argument], "unit-features")) {
                normalize_features = true;
                continue;
            }
            if (std::strcmp(argv[argument], "video-defaults")) return 2;
            Dl_info location{};
            if (!dladdr(reinterpret_cast<void*>(ctor), &location)) return 2;
            // Hash-pinned VideoTrack constructor copies these 156 parameter bytes.
            const auto* defaults = static_cast<const unsigned char*>(location.dli_fbase) + 0x28cb0;
            std::memcpy(tracker.bytes + 0x30, defaults, 156);
        }
        std::printf("{\"tracker_parameter_words\":[");
        for (size_t offset = 0x30; offset < 0xd0; offset += 4) {
            if (offset != 0x30) std::printf(",");
            std::printf("%u", get<uint32_t>(&tracker, offset));
        }
        std::printf("],\"frames\":[\n");
        for (uint32_t index = 0; index < frames; ++index) {
            int64_t timestamp;
            uint32_t bytes;
            if (!read(input, timestamp) || !read(input, bytes) || bytes < 36 || bytes > 100000) return 2;
            std::vector<unsigned char> blob(bytes);
            if (!input.read(reinterpret_cast<char*>(blob.data()), bytes) || get<uint32_t>(blob.data(), 0) != 0x20400) return 2;
            const uint32_t boxes = get<uint32_t>(blob.data(), 8);
            int width = get<int>(blob.data(), 28), height = get<int>(blob.data(), 32);
            if (boxes > 32 || width != 768 || height != 1280) return 2;
            put(&tracker, 0xd0, width);
            put(&tracker, 0xd4, height);
            if (index) std::printf(",\n");
            std::printf("{\"timestamp_us\":%lld,\"detections\":[", static_cast<long long>(timestamp));
            size_t offset = 36;
            std::vector<int> gallery_ids;
            for (uint32_t box_index = 0; box_index < boxes; ++box_index) {
                if (offset + 562 > blob.size() || get<uint32_t>(blob.data(), offset + 32) != 1) return 2;
                uint32_t features = get<uint32_t>(blob.data(), offset + 558);
                if (features != 128 || offset + 562 + features * 4 > blob.size()) return 2;
                Detection detection = convert_box(blob.data() + offset);
                Pose87 pose = convert_pose(blob.data() + offset + 36);
                Pose83 truncated{};
                truncated.header[0] = 0xff;
                put(&truncated, 2, uint16_t(0xffff));
                std::memcpy(truncated.points, pose.points, sizeof(truncated.points));
                std::vector<float> feature(features);
                std::memcpy(feature.data(), blob.data() + offset + 562, features * 4);
                for (float value : feature) if (!std::isfinite(value)) return 2;
                double squared_norm = 0;
                for (float value : feature) squared_norm += double(value) * value;
                if (squared_norm <= 0) return 2;
                if (normalize_features) for (float& value : feature) value /= std::sqrt(squared_norm);
                Target target{};
                int type = blob[offset + 19] ? 6 : 2;
                target_ctor(&target, detection, truncated, feature, type, timestamp);
                if (box_index) std::printf(",");
                int gallery_id = get<int16_t>(blob.data(), offset + 20);
                gallery_ids.push_back(gallery_id);
                std::printf("{\"gallery_id\":%d,\"type\":%d,\"box\":[%.5f,%.5f,%.5f,%.5f],\"boxed_pose_gate\":%s,\"activation_21\":%.8f,\"raw_feature_norm\":%.8f,\"prior_costs\":[",
                    gallery_id, type, detection.box[0], detection.box[1], detection.box[2], detection.box[3],
                    boxed_gate(detection, truncated) ? "true" : "false", activation(detection, truncated), std::sqrt(squared_norm));
                auto stored = span(&tracker, 0, sizeof(Target));
                bool first = true;
                for (auto prior = stored.begin; prior != stored.end; prior += sizeof(Target)) {
                    uint64_t packed = cost(prior, &target);
                    uint32_t bits = packed;
                    float distance;
                    std::memcpy(&distance, &bits, 4);
                    if (!std::isfinite(distance)) throw std::runtime_error("Nonfinite native cost");
                    if (!first) std::printf(",");
                    first = false;
                    std::printf("{\"id\":%d,\"cost\":%.8f,\"flags\":%u,\"expired_with_roi_interval\":%s,\"memory_entries\":%zu}",
                        get<int16_t>(prior, 4), distance, unsigned(packed >> 32),
                        expired(prior, &target, get<long>(prior, 0x50)) ? "true" : "false", count(prior, 0x20, 40));
                }
                std::printf("]}");
                if (add(&tracker, &target) != 0) throw std::runtime_error("Native target rejected");
                target_dtor(&target);
                offset += 562 + features * 4;
            }
            if (offset != blob.size()) return 2;
            auto provisional = init_match(&tracker);
            std::printf("],\"pre_update_matrix\":[");
            auto distances = span(&tracker, 0x108, 4);
            auto flags = span(&tracker, 0x120, 4);
            if (distances.end - distances.begin != flags.end - flags.begin) return 2;
            size_t prior_count = count(&tracker, 0, sizeof(Target));
            for (size_t i = 0; i < count(&tracker, 0x108, 4); ++i) {
                float distance = get<float>(distances.begin, i * 4);
                if (!std::isfinite(distance)) return 2;
                uint32_t initial_flags = get<uint32_t>(flags.begin, i * 4);
                bool rejected = distance >= 2 || dynamic_gate(&tracker, i / prior_count, i % prior_count, distance);
                std::printf("%s{\"cost\":%.8f,\"flags\":%u,\"dynamic_gate_rejected\":%s,\"dynamic_flags\":%u}",
                    i ? "," : "", distance, initial_flags, rejected ? "true" : "false", get<uint32_t>(flags.begin, i * 4));
            }
            void* result = update(&tracker, timestamp);
            auto* ids = reinterpret_cast<std::vector<int>*>(static_cast<unsigned char*>(result) + 0x18);
            if (ids->size() != boxes) throw std::runtime_error("Native ID count mismatch");
            std::printf("],\"replay_ids\":[");
            for (size_t i = 0; i < ids->size(); ++i) std::printf("%s%d", i ? "," : "", (*ids)[i]);
            std::printf("],\"stored_targets\":[");
            auto stored = span(&tracker, 0, sizeof(Target));
            const auto& frame_boxes = *reinterpret_cast<const std::vector<BoxFrame>*>(tracker.bytes + 0x138);
            const std::vector<BoxFrame> no_occlusions;
            bool first = true;
            for (auto prior = stored.begin; prior != stored.end; prior += sizeof(Target)) {
                if (!first) std::printf(",");
                first = false;
                float score = 0;
                bool admission = clean(prior, score, frame_boxes, no_occlusions, width, height);
                void* latest = get<void*>(prior, 0x10);
                std::printf("{\"id\":%d,\"last_timestamp_us\":%lld,\"memory_entries\":%zu,\"clean_with_all_frame_boxes\":%s,\"clean_score\":%.8f,\"memory_timestamps_us\":[",
                    get<int16_t>(prior, 4), static_cast<long long>(get<int64_t>(latest, 0x38)),
                    count(prior, 0x20, 40), admission ? "true" : "false", score);
                auto memories = span(prior, 0x20, 40);
                for (auto item = memories.begin; item != memories.end; item += 40)
                    std::printf("%s%lld", item == memories.begin ? "" : ",", static_cast<long long>(get<int64_t>(item, 0)));
                std::printf("]}");
            }
            std::printf("]}");
            std::fflush(stdout);
        }
        if (input.peek() != EOF) return 2;
        dtor(&tracker);
        std::printf("\n],\"normal_cleanup\":true}\n");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "Replay refused: %s\n", error.what());
        return 1;
    }
}
