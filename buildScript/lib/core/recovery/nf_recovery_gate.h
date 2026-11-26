/* SPDX-License-Identifier: GPL-3.0 */
#ifndef NF_RECOVERY_GATE_H
#define NF_RECOVERY_GATE_H
#include "nf_process_guard.h"
enum nf_recovery_status { NF_RECOVERY_ERROR=-1,NF_RECOVERY_CLEAR=0,NF_RECOVERY_CHILD_PENDING=1,NF_RECOVERY_SYSCTL_PENDING=2 };
enum nf_record_state { NF_RECORD_UNKNOWN=-1,NF_RECORD_ORIGINAL=0,NF_RECORD_GONE=1,NF_RECORD_CHANGED=2 };
typedef enum nf_child_status (*nf_child_probe)(const struct nf_process_scope *,const struct nf_child_identity *,void *);
typedef enum nf_record_state (*nf_record_probe)(const struct nf_wal_record *,void *);
/* Read-only recovery gate: never restores values, never signals processes.
 * Only CLEAR permits replacing a prior journal. */
enum nf_recovery_status nf_recovery_inspect(const struct nf_wal *,const struct nf_process_scope *,nf_child_probe,nf_record_probe,void *);
int nf_recovery_record(int,const struct nf_process_scope *,const struct nf_child_identity *,size_t,const struct nf_wal_record *);
int nf_recovery_forget_verified(int,const struct nf_process_scope *,const struct nf_wal_record *,nf_record_probe,void *);
#endif
