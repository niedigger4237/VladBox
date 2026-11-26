/* SPDX-License-Identifier: GPL-3.0 */
#ifndef NF_PROCESS_GUARD_H
#define NF_PROCESS_GUARD_H
#include "nf_sysctl_wal.h"

struct nf_process_scope {
    struct nf_wal_scope network;
    uint64_t pidns_dev, pidns_ino;
};
enum nf_child_status {
    NF_CHILD_UNKNOWN=-1,
    NF_CHILD_ABSENT=0,
    NF_CHILD_LIVE=1,
    NF_CHILD_GROUP_PRESENT=2,
    NF_CHILD_IDENTITY_CHANGED=3
};

/* Read-only recovery gate. Caller must own the supervisor listener and
 * provide identities recorded before sysctl mutation. No process termination. */
int nf_process_current_scope(struct nf_process_scope *);
int nf_child_capture(uint32_t pid, struct nf_child_identity *);
enum nf_child_status nf_child_check(const struct nf_process_scope *, const struct nf_child_identity *);
#endif
