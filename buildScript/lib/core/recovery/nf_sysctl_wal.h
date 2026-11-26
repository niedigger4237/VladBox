/* SPDX-License-Identifier: GPL-3.0 */
#ifndef NF_SYSCTL_WAL_H
#define NF_SYSCTL_WAL_H
#include <stddef.h>
#include <stdint.h>

#define NF_WAL_MAX 512
#define NF_WAL_FILE "nf-sysctl-journal.v1"
#define NF_WAL_NAME 16
#define NF_WAL_MAX_CHILDREN 64

enum nf_wal_status { NF_WAL_ERROR=-1, NF_WAL_OK=0, NF_WAL_ABSENT=1, NF_WAL_STALE_BOOT=2 };
enum nf_sysctl_kind { NF_ROUTE_LOCALNET=1, NF_DISABLE_IPV6=2 };
struct nf_wal_scope {
    char boot_id[37];
    uint64_t netns_dev, netns_ino;
};
struct nf_child_identity {
    uint32_t pid;
    uint64_t start_ticks, exe_dev, exe_ino;
};
struct nf_wal_record {
    uint32_t ifindex;
    uint8_t kind, original, desired;
    char name[NF_WAL_NAME];
};
struct nf_wal {
    struct nf_wal_scope scope;
    uint64_t pidns_dev, pidns_ino;
    size_t child_count;
    struct nf_child_identity children[NF_WAL_MAX_CHILDREN];
    size_t count;
    struct nf_wal_record records[NF_WAL_MAX];
};

/* Caller must hold exclusive supervisor ownership of dirfd.
 * No function here writes sysctl, opens a network socket, or kills a process. */
int nf_wal_current_scope(struct nf_wal_scope *);
int nf_wal_save(int dirfd, const struct nf_wal *);
enum nf_wal_status nf_wal_load(int dirfd, const struct nf_wal_scope *, struct nf_wal *);
#endif
