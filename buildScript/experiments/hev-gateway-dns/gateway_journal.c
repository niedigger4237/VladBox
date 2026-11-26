// SPDX-License-Identifier: GPL-3.0
// Durable gateway DNS write-ahead record. No firewall or process operations.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <zlib.h>

#define NF_WAL_MAX (128U * 1024U)
#define NF_WAL_HEADER 72U
#define NF_WAL_NAME "nf-hev-dns.wal"
#define NF_WAL_TEMP ".nf-hev-dns.wal.new"
/* The caller serializes exact old/pending rules and resource identities into
 * payload. Unknown payload schemas must never authorize resource removal. */
struct nf_wal_record {
    uint64_t generation, epoch;
    uint8_t boot[16], session[16];
    uint16_t port;
    uint8_t debts; // reserve, guard, core, NAT, conntrack (bits 0..4)
    uint8_t schema;
    const uint8_t *payload;
    size_t length;
};
static uint64_t nf_get(const uint8_t *p,size_t n) {
    uint64_t x=0;while(n--) x=(x<<8)|*p++;return x;
}
static void nf_put(uint8_t *p,size_t n,uint64_t x) {
    while(n) {p[--n]=(uint8_t)x;x>>=8;}
}
static bool nf_nonzero(const uint8_t *p,size_t n) {
    while(n--) if(*p++) return true;
    return false;
}
static bool nf_wal_valid(const struct nf_wal_record *r) {
    return r && r->generation && r->port>=1024 && r->schema==1 && !(r->debts&~31U) &&
        (!r->debts || (r->debts&1U)) && (!(r->debts&24U) || r->epoch) &&
        nf_nonzero(r->boot,16) && nf_nonzero(r->session,16) &&
        r->length<=NF_WAL_MAX && (!r->length || r->payload) && (!r->debts || r->length);
}
static bool nf_dir_ok(int fd) {
    struct stat st;return !fstat(fd,&st) && S_ISDIR(st.st_mode) && st.st_uid==geteuid() && (st.st_mode&0777)==0700;
}
/* Caller supplies an already trusted directory descriptor, not a pathname.
 * Hold the returned independent flock for the entire recovery/session. */
int nf_gateway_wal_lock(int directory) {
    if(!nf_dir_ok(directory)) return -1;
    int fd=openat(directory,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) return -1;
    if(!nf_dir_ok(fd) || flock(fd,LOCK_EX|LOCK_NB)) {close(fd);return -1;}
    return fd;
}
static bool nf_file_ok(int fd) {
    struct stat st;return !fstat(fd,&st) && S_ISREG(st.st_mode) && st.st_uid==geteuid() &&
        (st.st_mode&0777)==0600 && st.st_nlink==1;
}
static int nf_io(int fd,void *buf,size_t n,bool write_data) {
    uint8_t *p=buf;
    while(n) {
        ssize_t count=write_data?write(fd,p,n):read(fd,p,n);
        if(count<0 && errno==EINTR) continue;
        if(count<=0) return -1;
        p+=count;n-=(size_t)count;
    }
    return 0;
}
/* 0 = valid, 1 = absent, -1 = unreadable/corrupt. Never treat corrupt as absent.
 * Caller owns buffer and holds the directory lock. */
int nf_gateway_wal_load(int fd,struct nf_wal_record *r,uint8_t *payload,size_t capacity) {
    if(!r || !nf_dir_ok(fd)) return -1;
    memset(r,0,sizeof(*r));
    int f=openat(fd,NF_WAL_NAME,O_RDONLY|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
    if(f<0) return errno==ENOENT?1:-1;
    uint8_t h[NF_WAL_HEADER],extra;int rc=-1;
    if(!nf_file_ok(f) || nf_io(f,h,sizeof(h),false) || memcmp(h,"NFDNSW01",8)) goto done;
    size_t n=(size_t)nf_get(h+64,4);
    if(n>NF_WAL_MAX || n>capacity || (n&&!payload) || nf_io(f,payload,n,false)) goto done;
    ssize_t tail;do {tail=read(f,&extra,1);} while(tail<0&&errno==EINTR);
    if(tail!=0) goto done;
    uint32_t crc=(uint32_t)crc32(0,h,68);if(n) crc=(uint32_t)crc32(crc,payload,(uInt)n);
    if(crc!=nf_get(h+68,4) || nf_get(h+60,4)!=0) goto done;
    r->generation=nf_get(h+8,8);r->epoch=nf_get(h+16,8);memcpy(r->boot,h+24,16);memcpy(r->session,h+40,16);
    r->port=(uint16_t)nf_get(h+56,2);r->debts=h[58];r->schema=h[59];r->payload=payload;r->length=n;
    if(nf_wal_valid(r)) rc=0;
done:
    close(f);if(rc) memset(r,0,sizeof(*r));return rc;
}
/* Save is a compare-and-swap under the session lock. A previous clean record
 * can be replaced by a new session; unresolved debts cannot change identity.
 * A negative result after rename is ambiguous: stop and reload, never commit
 * firewall changes based on that result. */
int nf_gateway_wal_save(int fd,const struct nf_wal_record *r,uint64_t previous) {
    if(!nf_dir_ok(fd) || !nf_wal_valid(r) || previous==UINT64_MAX || r->generation!=previous+1) return -1;
    uint8_t *scratch=malloc(NF_WAL_MAX);if(!scratch)return -1;
    struct nf_wal_record old;int read_rc=nf_gateway_wal_load(fd,&old,scratch,NF_WAL_MAX);
    bool match=(read_rc==1 && !previous) || (!read_rc && old.generation==previous &&
        (!old.debts || (old.port==r->port && !memcmp(old.boot,r->boot,16) && !memcmp(old.session,r->session,16))));
    free(scratch);if(!match) return -1;
    // Only a regular, private, single-link stale temp from a cooperating writer
    // can be removed. Never follow a symlink or overwrite an unknown file.
    int stale=openat(fd,NF_WAL_TEMP,O_RDONLY|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
    if(stale>=0) {bool safe=nf_file_ok(stale);close(stale);if(!safe || unlinkat(fd,NF_WAL_TEMP,0))return -1;}
    else if(errno!=ENOENT)return -1;
    int f=openat(fd,NF_WAL_TEMP,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
    if(f<0)return -1;
    uint8_t h[NF_WAL_HEADER]={0};memcpy(h,"NFDNSW01",8);nf_put(h+8,8,r->generation);nf_put(h+16,8,r->epoch);
    memcpy(h+24,r->boot,16);memcpy(h+40,r->session,16);nf_put(h+56,2,r->port);h[58]=r->debts;h[59]=r->schema;nf_put(h+64,4,r->length);
    uint32_t crc=(uint32_t)crc32(0,h,68);if(r->length)crc=(uint32_t)crc32(crc,r->payload,(uInt)r->length);nf_put(h+68,4,crc);
    int rc=nf_file_ok(f)?nf_io(f,h,sizeof(h),true):-1;
    if(!rc)rc=nf_io(f,(void*)r->payload,r->length,true);
    if(!rc)rc=fsync(f);
    if(close(f)&&!rc)rc=-1;
    if(rc)return -1;
    if(renameat(fd,NF_WAL_TEMP,fd,NF_WAL_NAME))return -1;
    return fsync(fd)?-1:0;
}
#ifdef NF_WAL_HOST_TEST
#include <stdio.h>
#include <sys/wait.h>
static unsigned checks;
#define CHECK(x) do{if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}++checks;}while(0)
int main(void) {
    char path[]="/tmp/nf-wal-XXXXXX";CHECK(mkdtemp(path)!=NULL);
    int d=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);CHECK(d>=0);int l=nf_gateway_wal_lock(d);CHECK(l>=0);
    CHECK(nf_gateway_wal_lock(d)<0);
    uint8_t bytes[]={1,2,3},out[16];struct nf_wal_record a={.generation=1,.epoch=4,.boot={1},.session={2},.port=15353,.debts=31,.schema=1,.payload=bytes,.length=3},b;
    CHECK(nf_gateway_wal_load(l,&b,out,sizeof(out))==1);
    CHECK(!nf_gateway_wal_save(l,&a,0));CHECK(!nf_gateway_wal_load(l,&b,out,sizeof(out)) && b.generation==1 && b.debts==31 && !memcmp(out,bytes,3));
    CHECK(nf_gateway_wal_save(l,&a,0)<0);a.generation=2;a.session[0]=3;CHECK(nf_gateway_wal_save(l,&a,1)<0);a.session[0]=2;
    close(l);pid_t child=fork();CHECK(child>=0);
    if(!child) {int lock=nf_gateway_wal_lock(d);if(lock<0)_exit(2);
        int tmp=openat(lock,NF_WAL_TEMP,O_WRONLY|O_CREAT|O_EXCL,0600);
        if(tmp<0 || write(tmp,"partial",7)!=7 || fsync(tmp))_exit(3);
        _exit(0); // no close/unlock/rename: simulate process termination
    }
    int status;CHECK(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    l=nf_gateway_wal_lock(d);CHECK(l>=0);
    CHECK(!nf_gateway_wal_load(l,&b,out,sizeof(out))&&b.generation==1);
    CHECK(!nf_gateway_wal_save(l,&a,1));CHECK(!nf_gateway_wal_load(l,&b,out,sizeof(out))&&b.generation==2);
    a.generation=3;CHECK(!symlinkat(NF_WAL_NAME,l,NF_WAL_TEMP));CHECK(nf_gateway_wal_save(l,&a,2)<0);CHECK(!unlinkat(l,NF_WAL_TEMP,0));
    a.debts=0;a.length=0;CHECK(!nf_gateway_wal_save(l,&a,2));a.generation=4;a.session[0]=3;a.debts=1;a.length=3;
    CHECK(!nf_gateway_wal_save(l,&a,3));close(l);l=nf_gateway_wal_lock(d);CHECK(l>=0);
    CHECK(!nf_gateway_wal_load(l,&b,out,sizeof(out))&&b.generation==4&&b.session[0]==3);
    CHECK(nf_gateway_wal_load(l,&b,out,2)<0);
    int f=openat(l,NF_WAL_NAME,O_RDWR);CHECK(f>=0);uint8_t bad=9;CHECK(pwrite(f,&bad,1,NF_WAL_HEADER)==1);close(f);
    CHECK(nf_gateway_wal_load(l,&b,out,sizeof(out))<0);a.generation=5;CHECK(nf_gateway_wal_save(l,&a,4)<0);
    CHECK(!unlinkat(l,NF_WAL_NAME,0));CHECK(!symlinkat("missing",l,NF_WAL_NAME));CHECK(nf_gateway_wal_load(l,&b,out,sizeof(out))<0);
    CHECK(!unlinkat(l,NF_WAL_NAME,0));close(l);CHECK(!fchmod(d,0755));CHECK(nf_gateway_wal_lock(d)<0);close(d);CHECK(!rmdir(path));
    printf("PASS %u journal checks\n",checks);return 0;
}
#endif
