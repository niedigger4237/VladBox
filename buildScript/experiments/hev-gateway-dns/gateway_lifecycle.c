// SPDX-License-Identifier: GPL-3.0
// Staged HEV gateway lifecycle coordinator. No kernel operations here.
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>

/* Each operation is synchronous, returns 0 only on verified success, and is
 * idempotent on retry. The backend must never remove unowned resources.
 * epoch identifies an immutable snapshot of exact downstream link identities
 * (including ifindex and addresses), not merely a set of interface names. */
enum nf_step {
    NF_RESERVE, NF_GUARD_CLOSED, NF_START_CORE, NF_CORE_READY,
    NF_LINKS_CURRENT, NF_GUARD_OPEN, NF_NAT_INSTALL, NF_NAT_VERIFY,
    NF_GUARD_VERIFY, NF_NAT_REMOVE, NF_CONNTRACK_DRAIN, NF_STOP_CORE,
    NF_GUARD_REMOVE, NF_RELEASE
};
enum nf_phase { NF_IDLE, NF_PAUSED, NF_RUNNING, NF_FAULT };
struct nf_lifecycle {
    enum nf_phase phase;
    unsigned port;
    uint64_t epoch;
    bool reserved, guard, core, nat, flows;
};
struct nf_backend {
    void *ctx;
    int (*step)(void *, enum nf_step, unsigned, uint64_t);
};
static int invoke(const struct nf_lifecycle *s, const struct nf_backend *b, enum nf_step op) {
    return b->step(b->ctx, op, s->port, s->epoch);
}
static bool valid(const struct nf_lifecycle *s, const struct nf_backend *b) {
    return s && b && b->step;
}
/* Close ingress before withdrawing redirects. Drain only the exact old epoch's
 * owned NAT tuples; never flush shared conntrack. Keep debts across failure. */
static int withdraw(struct nf_lifecycle *s, const struct nf_backend *b) {
    int rc = 0;
    if (s->guard && invoke(s,b,NF_GUARD_CLOSED)) rc = -1;
    if (s->nat) {
        if (invoke(s,b,NF_NAT_REMOVE)) rc = -1;
        else s->nat = false;
    }
    if (s->flows && !s->nat) {
        if (invoke(s,b,NF_CONNTRACK_DRAIN)) rc = -1;
        else s->flows = false;
    }
    return rc;
}
int nf_gateway_stop(struct nf_lifecycle *s, const struct nf_backend *b) {
    if (!valid(s,b)) return -1;
    int rc = withdraw(s,b);
    // Even if redirect removal failed, stop the owned listener. Do not remove
    // the INPUT guard until listener, NAT, and conntrack are all confirmed gone.
    if (s->core) {
        if (invoke(s,b,NF_STOP_CORE)) rc = -1;
        else s->core = false;
    }
    if (s->guard && !s->core && !s->nat && !s->flows && rc == 0) {
        if (invoke(s,b,NF_GUARD_REMOVE)) rc = -1;
        else s->guard = false;
    }
    if (s->reserved && !s->guard && !s->core && !s->nat && !s->flows && rc == 0) {
        if (invoke(s,b,NF_RELEASE)) rc = -1;
        else s->reserved = false;
    }
    if (!rc && !s->reserved && !s->guard && !s->core && !s->nat && !s->flows) {
        memset(s,0,sizeof(*s)); return 0;
    }
    s->phase = NF_FAULT; return -1;
}
/* Reserve must be an atomic, no-side-effects-on-failure ownership check.
 * Subsequent operations may partially apply, so record debt before invoking. */
int nf_gateway_prepare(struct nf_lifecycle *s, const struct nf_backend *b, unsigned port) {
    if (!valid(s,b) || s->phase != NF_IDLE || s->reserved || s->guard || s->core ||
        s->nat || s->flows || port < 1024U || port > 65535U) return -1;
    s->port = port;
    if (invoke(s,b,NF_RESERVE)) { s->port = 0; return -1; }
    s->reserved = true; s->guard = true;
    if (invoke(s,b,NF_GUARD_CLOSED)) goto fail;
    s->core = true;
    if (invoke(s,b,NF_START_CORE) || invoke(s,b,NF_CORE_READY) || invoke(s,b,NF_GUARD_CLOSED)) goto fail;
    s->phase = NF_PAUSED; return 0;
fail:
    (void)nf_gateway_stop(s,b); return -1;
}
int nf_gateway_pause(struct nf_lifecycle *s, const struct nf_backend *b) {
    if (!valid(s,b) || (s->phase != NF_RUNNING && s->phase != NF_PAUSED)) return -1;
    if (withdraw(s,b)) { (void)nf_gateway_stop(s,b); return -1; }
    s->phase = NF_PAUSED; s->epoch = 0; return 0;
}
int nf_gateway_resume(struct nf_lifecycle *s, const struct nf_backend *b, uint64_t epoch) {
    if (!valid(s,b) || s->phase != NF_PAUSED || !s->reserved || !s->guard || !s->core ||
        s->nat || s->flows || !epoch) return -1;
    s->epoch = epoch;
    if (invoke(s,b,NF_GUARD_CLOSED) || invoke(s,b,NF_CORE_READY) || invoke(s,b,NF_LINKS_CURRENT) ||
        invoke(s,b,NF_GUARD_OPEN)) goto fail;
    s->nat = true; s->flows = true;
    if (invoke(s,b,NF_NAT_INSTALL) || invoke(s,b,NF_NAT_VERIFY) || invoke(s,b,NF_GUARD_VERIFY) ||
        invoke(s,b,NF_LINKS_CURRENT)) goto fail;
    s->phase = NF_RUNNING; return 0;
fail:
    (void)nf_gateway_stop(s,b); return -1;
}
/* A name reused with a new ifindex requires a new epoch. The old snapshot
 * remains available until its NAT and conntrack obligations have drained. */
int nf_gateway_refresh(struct nf_lifecycle *s, const struct nf_backend *b, uint64_t epoch) {
    if (!valid(s,b) || s->phase != NF_RUNNING || !epoch) return -1;
    if (nf_gateway_pause(s,b)) return -1;
    return nf_gateway_resume(s,b,epoch);
}
int nf_gateway_verify(struct nf_lifecycle *s, const struct nf_backend *b) {
    if (!valid(s,b)) return -1;
    if (s->phase == NF_RUNNING && !invoke(s,b,NF_GUARD_VERIFY) && !invoke(s,b,NF_CORE_READY) &&
        !invoke(s,b,NF_NAT_VERIFY) && !invoke(s,b,NF_LINKS_CURRENT)) return 0;
    if (s->phase == NF_PAUSED && !invoke(s,b,NF_GUARD_CLOSED) && !invoke(s,b,NF_CORE_READY)) return 0;
    if (s->phase == NF_RUNNING || s->phase == NF_PAUSED) {
        (void)nf_gateway_stop(s,b);
    }
    return -1;
}

#ifdef NF_LIFECYCLE_TEST
#include <stdio.h>
#include <stdlib.h>
struct fake {
    bool reserved, guard, open, core, nat, flows;
    unsigned calls, fail_at, violations;
    uint64_t epoch, drained;
    int persistent;
};
static int simulate(void *ctx, enum nf_step op, unsigned port, uint64_t epoch) {
    struct fake *f = ctx;
    if (port != 15353) ++f->violations;
    bool fail = ++f->calls == f->fail_at || (int)op == f->persistent;
    if (op == NF_RESERVE && fail) return -1;
    // Simulate partial application on failure for installing resources.
    switch (op) {
    case NF_RESERVE: f->reserved=true; break;
    case NF_GUARD_CLOSED: if (!f->reserved) ++f->violations; f->guard=true; if (!fail) f->open=false; break;
    case NF_START_CORE: if (!f->guard || f->open) ++f->violations; f->core=true; break;
    case NF_CORE_READY: if (!f->core) ++f->violations; break;
    case NF_LINKS_CURRENT: if (!epoch) ++f->violations; break;
    case NF_GUARD_OPEN: if (!f->core || !f->guard || f->flows) ++f->violations; f->open=true; break;
    case NF_NAT_INSTALL: if (!f->open || !f->core) ++f->violations; f->nat=f->flows=true; f->epoch=epoch; break;
    case NF_NAT_VERIFY: if (!f->nat || f->epoch!=epoch) ++f->violations; break;
    case NF_GUARD_VERIFY: if (!f->guard || !f->open) ++f->violations; break;
    case NF_NAT_REMOVE: if (!fail) f->nat=false; break;
    case NF_CONNTRACK_DRAIN: if (f->nat || f->epoch!=epoch) ++f->violations; if (!fail) {f->flows=false; f->drained=epoch;} break;
    case NF_STOP_CORE: if (!fail) f->core=false; break;
    case NF_GUARD_REMOVE: if (f->core || f->nat || f->flows) ++f->violations; if (!fail) f->guard=f->open=false; break;
    case NF_RELEASE: if (f->guard || f->core || f->nat || f->flows) ++f->violations; if (!fail) f->reserved=false; break;
    }
    return fail ? -1 : 0;
}
static unsigned checks;
#define CHECK(x) do { if (!(x)) {fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); exit(1);} ++checks; } while(0)
static bool clean(struct fake *f) {return !f->reserved && !f->guard && !f->core && !f->nat && !f->flows && !f->violations;}
int main(void) {
    struct nf_lifecycle s={0}; struct fake f={.persistent=-1}; struct nf_backend b={&f,simulate};
    CHECK(!nf_gateway_prepare(&s,&b,15353) && s.phase==NF_PAUSED && f.core && !f.open);
    CHECK(!nf_gateway_resume(&s,&b,1) && f.nat && f.open);
    CHECK(!nf_gateway_verify(&s,&b));
    CHECK(!nf_gateway_refresh(&s,&b,2) && f.drained==1 && f.epoch==2);
    CHECK(!nf_gateway_pause(&s,&b) && f.core && f.guard && !f.open && !f.nat && f.drained==2);
    CHECK(!nf_gateway_verify(&s,&b)); CHECK(!nf_gateway_resume(&s,&b,3));
    CHECK(!nf_gateway_stop(&s,&b) && clean(&f) && s.phase==NF_IDLE);
    unsigned normal_calls=f.calls;
    // Inject a one-shot failure at each operation of the ordinary lifecycle.
    for (unsigned i=1;i<=normal_calls;++i) {
        s=(struct nf_lifecycle){0}; f=(struct fake){.persistent=-1,.fail_at=i};
        if (!nf_gateway_prepare(&s,&b,15353) && !nf_gateway_resume(&s,&b,1) && !nf_gateway_verify(&s,&b) &&
            !nf_gateway_refresh(&s,&b,2) && !nf_gateway_pause(&s,&b) && !nf_gateway_verify(&s,&b))
            (void)nf_gateway_resume(&s,&b,3);
        (void)nf_gateway_stop(&s,&b); f.fail_at=0;
        CHECK(!nf_gateway_stop(&s,&b) && clean(&f) && s.phase==NF_IDLE);
    }
    // Persistently fail NAT withdrawal: core stops but guard and ownership stay.
    f=(struct fake){.persistent=-1}; CHECK(!nf_gateway_prepare(&s,&b,15353)); CHECK(!nf_gateway_resume(&s,&b,7));
    f.persistent=NF_NAT_REMOVE; CHECK(nf_gateway_stop(&s,&b)!=0 && !f.core && f.guard && f.nat && s.phase==NF_FAULT);
    CHECK(nf_gateway_resume(&s,&b,8)!=0); f.persistent=-1; CHECK(!nf_gateway_stop(&s,&b) && clean(&f));
    f=(struct fake){.persistent=-1}; CHECK(!nf_gateway_prepare(&s,&b,15353)); CHECK(!nf_gateway_resume(&s,&b,9));
    f.persistent=NF_CONNTRACK_DRAIN; CHECK(nf_gateway_stop(&s,&b)!=0 && !f.core && f.guard && f.flows);
    f.persistent=-1; CHECK(!nf_gateway_stop(&s,&b) && clean(&f));
    f=(struct fake){.persistent=-1}; CHECK(!nf_gateway_prepare(&s,&b,15353));
    f.persistent=NF_STOP_CORE; CHECK(nf_gateway_stop(&s,&b)!=0 && f.core && f.guard);
    f.persistent=-1; CHECK(!nf_gateway_stop(&s,&b) && clean(&f));
    CHECK(nf_gateway_prepare(&s,&b,53)!=0); CHECK(nf_gateway_resume(&s,&b,0)!=0);
    printf("PASS %u checks (%u fault positions)\n",checks,normal_calls); return 0;
}
#endif
