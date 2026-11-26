/* SPDX-License-Identifier: GPL-3.0 */
#define _GNU_SOURCE
#include "nf_process_guard.h"
#include <assert.h>
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

int main(void) {
    struct nf_process_scope scope;
    assert(nf_process_current_scope(&scope)==0);
    int ready[2],stop[2];assert(pipe(ready)==0&&pipe(stop)==0);
    pid_t pid=fork();assert(pid>=0);
    if(pid==0) {
        close(ready[0]);close(stop[1]);
        if(setsid()<0||write(ready[1],"x",1)!=1) _exit(1);
        close(ready[1]);char c;
        while(read(stop[0],&c,1)<0&&errno==EINTR) {}
        _exit(0);
    }
    close(ready[1]);close(stop[0]);char c;
    assert(read(ready[0],&c,1)==1);close(ready[0]);
    struct nf_child_identity id;
    assert(nf_child_capture((uint32_t)pid,&id)==0);
    assert(nf_child_check(&scope,&id)==NF_CHILD_LIVE);
    struct nf_child_identity wrong=id;wrong.start_ticks++;
    assert(nf_child_check(&scope,&wrong)==NF_CHILD_IDENTITY_CHANGED);
    wrong=id;wrong.exe_ino++;
    assert(nf_child_check(&scope,&wrong)==NF_CHILD_IDENTITY_CHANGED);
    struct nf_process_scope foreign=scope;foreign.pidns_ino++;
    assert(nf_child_check(&foreign,&id)==NF_CHILD_UNKNOWN);
    wrong=id;wrong.pid=1;
    assert(nf_child_check(&scope,&wrong)==NF_CHILD_UNKNOWN);
    close(stop[1]);int status;
    assert(waitpid(pid,&status,0)==pid&&WIFEXITED(status)&&WEXITSTATUS(status)==0);
    assert(nf_child_check(&scope,&id)==NF_CHILD_ABSENT);

    // A session leader can disappear while another member of its group lives.
    int report[2],hold[2];assert(pipe(report)==0&&pipe(hold)==0);
    pid=fork();assert(pid>=0);
    if(pid==0) {
        close(report[0]);close(hold[1]);
        if(setsid()<0) _exit(1);
        pid_t worker=fork();if(worker<0) _exit(1);
        if(worker==0) {
            close(report[1]);char b;
            while(read(hold[0],&b,1)<0&&errno==EINTR) {}
            _exit(0);
        }
        close(hold[0]);
        struct nf_child_identity leader;
        if(nf_child_capture((uint32_t)getpid(),&leader)!=0||
           write(report[1],&leader,sizeof(leader))!=(ssize_t)sizeof(leader)) _exit(1);
        _exit(0);
    }
    close(report[1]);close(hold[0]);
    assert(read(report[0],&id,sizeof(id))==(ssize_t)sizeof(id));close(report[0]);
    assert(waitpid(pid,&status,0)==pid&&WIFEXITED(status)&&WEXITSTATUS(status)==0);
    assert(nf_child_check(&scope,&id)==NF_CHILD_GROUP_PRESENT);
    close(hold[1]);
    return 0;
}
