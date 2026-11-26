/* SPDX-License-Identifier: GPL-3.0 */
#define _GNU_SOURCE
#include "nf_recovery_gate.h"
#include <assert.h>
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>

struct mock { enum nf_child_status child; enum nf_record_state record; unsigned checked; };
static enum nf_child_status cp(const struct nf_process_scope *s,const struct nf_child_identity *id,void *p) {
    (void)s;(void)id;return ((struct mock *)p)->child;
}
static enum nf_record_state rp(const struct nf_wal_record *r,void *p) {
    (void)r;struct mock *m=p;m->checked++;return m->record;
}
int main(void) {
    char path[]="/tmp/nf-gate-XXXXXX";assert(mkdtemp(path));
    int fd=open(path,O_RDONLY|O_DIRECTORY);assert(fd>=0);
    struct nf_process_scope s;assert(nf_process_current_scope(&s)==0);
    struct nf_child_identity ids[]={{123,456,7,8},{124,457,7,9}};
    struct nf_wal_record r={.ifindex=42,.kind=NF_ROUTE_LOCALNET,.desired=1,.name="ap0"};
    struct nf_wal w;
    assert(nf_recovery_record(fd,&s,ids,2,&r)==0);
    assert(nf_wal_load(fd,&s.network,&w)==NF_WAL_OK&&w.child_count==2&&w.count==1);
    assert(nf_recovery_record(fd,&s,ids,2,&r)==0);
    struct nf_child_identity reused=ids[0];reused.start_ticks++;
    assert(nf_recovery_record(fd,&s,&reused,1,&r)!=0);
    struct mock m={NF_CHILD_LIVE,NF_RECORD_ORIGINAL,0};
    assert(nf_recovery_inspect(&w,&s,cp,rp,&m)==NF_RECOVERY_CHILD_PENDING&&m.checked==0);
    m.child=NF_CHILD_ABSENT;m.record=NF_RECORD_CHANGED;
    assert(nf_recovery_inspect(&w,&s,cp,rp,&m)==NF_RECOVERY_SYSCTL_PENDING);
    assert(nf_recovery_forget_verified(fd,&s,&r,rp,&m)!=0);
    assert(nf_wal_load(fd,&s.network,&w)==NF_WAL_OK&&w.count==1);
    m.record=NF_RECORD_ORIGINAL;
    assert(nf_recovery_inspect(&w,&s,cp,rp,&m)==NF_RECOVERY_CLEAR);
    assert(nf_recovery_forget_verified(fd,&s,&r,rp,&m)==0);
    assert(nf_wal_load(fd,&s.network,&w)==NF_WAL_OK&&w.count==0&&w.child_count==0);
    assert(unlinkat(fd,NF_WAL_FILE,0)==0);close(fd);assert(rmdir(path)==0);
    return 0;
}
