/* SPDX-License-Identifier: GPL-3.0 */
#define _GNU_SOURCE
#include "nf_sysctl_wal.h"
#include <assert.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
int main(void) {
    char path[]="/tmp/nf-wal-XXXXXX";
    assert(mkdtemp(path));
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    assert(fd>=0);
    struct nf_wal w={0},r={0};
    assert(nf_wal_current_scope(&w.scope)==0);
    w.pidns_dev=1;w.pidns_ino=2;w.child_count=1;
    w.children[0]=(struct nf_child_identity){.pid=123,.start_ticks=456,.exe_dev=7,.exe_ino=8};
    assert(nf_wal_load(fd,&w.scope,&r)==NF_WAL_ABSENT);
    w.count=1;w.records[0]=(struct nf_wal_record){.ifindex=42,.kind=NF_ROUTE_LOCALNET,.desired=1,.name="ap0"};
    assert(nf_wal_save(fd,&w)==0);
    assert(nf_wal_load(fd,&w.scope,&r)==NF_WAL_OK&&r.count==1&&r.records[0].ifindex==42&&!strcmp(r.records[0].name,"ap0")&&r.records[0].desired==1);
    assert(r.pidns_ino==2&&r.child_count==1&&r.children[0].start_ticks==456);
    w.child_count=0;assert(nf_wal_save(fd,&w)!=0);w.child_count=1;
    struct nf_wal_scope other=w.scope;other.boot_id[0]=other.boot_id[0]=='a'?'b':'a';
    assert(nf_wal_load(fd,&other,&r)==NF_WAL_STALE_BOOT&&r.count==0);
    other=w.scope;other.netns_ino++;
    assert(nf_wal_load(fd,&other,&r)==NF_WAL_ERROR&&r.count==0);
    w.count=2;w.records[1]=w.records[0];
    assert(nf_wal_save(fd,&w)!=0);
    assert(nf_wal_load(fd,&w.scope,&r)==NF_WAL_OK&&r.count==1);
    w.count=1;strcpy(w.records[0].name,"all");
    assert(nf_wal_save(fd,&w)!=0);
    int bad=openat(fd,NF_WAL_FILE,O_WRONLY|O_TRUNC|O_NOFOLLOW);
    assert(bad>=0&&write(bad,"bad",3)==3);close(bad);
    assert(nf_wal_load(fd,&w.scope,&r)==NF_WAL_ERROR);
    assert(unlinkat(fd,NF_WAL_FILE,0)==0);
    assert(symlinkat("/dev/null",fd,NF_WAL_FILE)==0);
    assert(nf_wal_load(fd,&w.scope,&r)==NF_WAL_ERROR);
    w.count=0;assert(nf_wal_save(fd,&w)!=0);
    assert(unlinkat(fd,NF_WAL_FILE,0)==0);
    assert(nf_wal_save(fd,&w)==0&&nf_wal_load(fd,&w.scope,&r)==NF_WAL_OK&&r.count==0);
    assert(fchmod(fd,0777)==0&&nf_wal_load(fd,&w.scope,&r)==NF_WAL_ERROR);
    assert(fchmod(fd,0700)==0);
    assert(unlinkat(fd,NF_WAL_FILE,0)==0);close(fd);assert(rmdir(path)==0);
    return 0;
}
