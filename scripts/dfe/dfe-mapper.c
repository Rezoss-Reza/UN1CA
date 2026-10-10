/* SPDX-License-Identifier: Apache-2.0 */
/* UN1CA recovery-only linear mapper. Never writes partition contents. */
#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/dm-ioctl.h>
#include <linux/fs.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

/* Static Bionic startup requires 64-byte ARM64 TLS alignment. */
static _Thread_local char tls_alignment __attribute__((aligned(64), used));

#define MAX_EXTENTS 128
#define PREFIX "unica-dfe-vendor-"
struct extent { uint64_t start, length, offset; };

static void init_io(struct dm_ioctl *io, const char *name) {
    memset(io, 0, sizeof(*io));
    io->version[0] = 4;
    io->version[1] = 0;
    io->version[2] = 0;
    io->data_size = sizeof(*io);
    if (name) snprintf(io->name, sizeof(io->name), "%s", name);
}
static int failure(const char *operation) {
    fprintf(stderr, "dfe-mapper: %s: %s\n", operation, strerror(errno));
    return 1;
}
static int valid_name(const char *name) {
    size_t n = strlen(PREFIX);
    if (strncmp(name, PREFIX, n) || !name[n] || strlen(name) >= DM_NAME_LEN) return 0;
    for (const char *p = name + n; *p; ++p) if (!isdigit((unsigned char)*p)) return 0;
    return 1;
}
static int read_extents(const char *file, uint64_t sectors, struct extent *e, unsigned *count) {
    FILE *f = fopen(file, "re");
    if (!f) return failure("open extent table");
    char line[256], tail;
    uint64_t total = 0;
    *count = 0;
    while (fgets(line, sizeof(line), f)) {
        struct extent next;
        /* Decimal numbers only: reject signs, extra tokens and truncated lines. */
        if (!strchr(line, '\n') || strspn(line, "0123456789 \t\r\n") != strlen(line) ||
            sscanf(line, "%" SCNu64 " %" SCNu64 " %" SCNu64 " %c",
                   &next.start, &next.length, &next.offset, &tail) != 3 ||
            *count >= MAX_EXTENTS || !next.length || next.start != total ||
            next.offset >= sectors || next.length > sectors - next.offset ||
            next.length > sectors - total) {
            fprintf(stderr, "dfe-mapper: invalid/noncontiguous/out-of-bounds extent table\n");
            fclose(f); return 1;
        }
        for (unsigned i = 0; i < *count; ++i) {
            if (next.offset < e[i].offset + e[i].length &&
                e[i].offset < next.offset + next.length) {
                fprintf(stderr, "dfe-mapper: overlapping physical extents\n");
                fclose(f); return 1;
            }
        }
        e[(*count)++] = next;
        total += next.length;
    }
    int bad = ferror(f) || !*count;
    fclose(f);
    if (bad) fprintf(stderr, "dfe-mapper: empty/unreadable extent table\n");
    return bad;
}
static int remove_mapping(int ctl, const char *name, const char *node) {
    struct dm_ioctl io;
    init_io(&io, name);
    if (ioctl(ctl, DM_DEV_STATUS, &io)) {
        if (errno == ENXIO || errno == ENODEV) return 0;
        return failure("mapping status");
    }
    char uuid[DM_UUID_LEN];
    snprintf(uuid, sizeof(uuid), "unica-dfe-%s", name);
    if (strcmp(io.uuid, uuid)) {
        fprintf(stderr, "dfe-mapper: refusing to delete an unowned mapping\n"); return 1;
    }
    init_io(&io, name);
    if (ioctl(ctl, DM_DEV_REMOVE, &io)) return failure("remove mapping");
    if (unlink(node) && errno != ENOENT) return failure("remove temporary node");
    return 0;
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "help")) {
        puts("dfe-mapper create SUPER NAME TABLE\ndfe-mapper delete NAME\nTABLE: logical_start length physical_start (512-byte sectors)");
        return 0;
    }
    int create = argc == 5 && !strcmp(argv[1], "create");
    int del = argc == 3 && !strcmp(argv[1], "delete");
    if (!create && !del) { fprintf(stderr, "dfe-mapper: see help\n"); return 1; }
    const char *name = create ? argv[3] : argv[2];
    if (!valid_name(name)) { fprintf(stderr, "dfe-mapper: invalid temporary mapping name\n"); return 1; }
    char node[256];
    snprintf(node, sizeof(node), "/dev/block/%s", name);
    int ctl = open("/dev/device-mapper", O_RDWR | O_CLOEXEC);
    if (ctl < 0) ctl = open("/dev/mapper/control", O_RDWR | O_CLOEXEC);
    if (ctl < 0) return failure("open device-mapper control");
    if (del) { int rc = remove_mapping(ctl, name, node); close(ctl); return rc; }
    struct stat st;
    uint64_t bytes;
    int super = open(argv[2], O_RDONLY | O_CLOEXEC);
    if (super < 0) { close(ctl); return failure("open super"); }
    if (fstat(super, &st) || !S_ISBLK(st.st_mode) || ioctl(super, BLKGETSIZE64, &bytes) || bytes % 512) {
        fprintf(stderr, "dfe-mapper: invalid super block device\n"); close(super); close(ctl); return 1;
    }
    struct extent extents[MAX_EXTENTS];
    unsigned count;
    if (read_extents(argv[4], bytes / 512, extents, &count)) { close(super); close(ctl); return 1; }
    struct stat node_st;
    if (!lstat(node, &node_st) || errno != ENOENT) {
        fprintf(stderr, "dfe-mapper: temporary node already exists or cannot be checked\n"); close(super); close(ctl); return 1;
    }
    size_t capacity = sizeof(struct dm_ioctl) + count * (sizeof(struct dm_target_spec) + 128);
    char *buffer = calloc(1, capacity);
    if (!buffer) { close(super); close(ctl); return failure("allocate table"); }
    struct dm_ioctl io;
    init_io(&io, name);
    snprintf(io.uuid, sizeof(io.uuid), "unica-dfe-%s", name);
    if (ioctl(ctl, DM_DEV_CREATE, &io)) {
        free(buffer); close(super); close(ctl); return failure("create mapping");
    }
    struct dm_ioctl *table = (struct dm_ioctl *)buffer;
    init_io(table, name);
    table->data_start = sizeof(*table);
    table->target_count = count;
    size_t cursor = sizeof(*table);
    for (unsigned i = 0; i < count; ++i) {
        struct dm_target_spec *target = (struct dm_target_spec *)(buffer + cursor);
        target->sector_start = extents[i].start;
        target->length = extents[i].length;
        strcpy(target->target_type, "linear");
        char *params = (char *)(target + 1);
        int len = snprintf(params, 128, "%u:%u %" PRIu64,
                           major(st.st_rdev), minor(st.st_rdev), extents[i].offset);
        size_t next = (sizeof(*target) + (size_t)len + 1 + 7) & ~(size_t)7;
        target->next = (uint32_t)next;
        cursor += next;
    }
    table->data_size = (uint32_t)cursor;
    int rc = 1;
    if (ioctl(ctl, DM_TABLE_LOAD, table)) { failure("load linear table"); goto cleanup; }
    init_io(&io, name);
    if (ioctl(ctl, DM_DEV_SUSPEND, &io)) { failure("activate mapping"); goto cleanup; }
    init_io(&io, name);
    if (ioctl(ctl, DM_DEV_STATUS, &io)) { failure("mapping status"); goto cleanup; }
    if (io.flags & DM_READONLY_FLAG) { fprintf(stderr, "dfe-mapper: mapping is read-only\n"); goto cleanup; }
    if (mknod(node, S_IFBLK | 0600, (dev_t)io.dev)) { failure("create temporary node"); goto cleanup; }
    printf("%s\n", node);
    rc = 0;
cleanup:
    if (rc) remove_mapping(ctl, name, node);
    free(buffer); close(super); close(ctl);
    return rc;
}
