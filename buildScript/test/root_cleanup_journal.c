#include "asteriskd.h"
#include <assert.h>
#include <string.h>

struct fake { unsigned value; int apply_fail, verify_fail, undo_fail, restores; };
static int probe(void *ctx, struct asteriskd_effect *e, char *err, size_t len) {
    (void)err; (void)len; e->resource.sysctl.original_value = ((struct fake *)ctx)->value; return 0;
}
static int apply(void *ctx, const struct asteriskd_effect *e, char *err, size_t len) {
    (void)err; (void)len; struct fake *f = ctx; f->value=e->resource.sysctl.desired_value; return f->apply_fail ? -1 : 0;
}
static int verify(void *ctx, const struct asteriskd_effect *e, char *err, size_t len) {
    (void)err; (void)len; struct fake *f=ctx; return !f->verify_fail && f->value==e->resource.sysctl.desired_value ? 0 : -1;
}
static int undo(void *ctx, const struct asteriskd_effect *e, char *err, size_t len) {
    (void)err; (void)len; struct fake *f=ctx; if(f->undo_fail) return -1; f->value=e->resource.sysctl.original_value; return 0;
}
static int restored(void *ctx, const struct asteriskd_effect *e, char *err, size_t len) {
    (void)err; (void)len; return ((struct fake *)ctx)->value==e->resource.sysctl.original_value ? 0 : -1;
}
static int restore_lifecycle(void *ctx) {
    struct fake *f=ctx; ++f->restores; return f->undo_fail ? -1 : 0;
}
static int release(void *ctx) { (void)ctx; return 0; }
int main(void) {
    struct fake f={0};
    struct asteriskd_effect_journal journal={0};
    struct asteriskd_effect_backend b={.context=&f,.probe_original=probe,.apply=apply,.verify_applied=verify,.undo=undo,.verify_restored=restored};
    struct asteriskd_effect e={.kind=ASTERISKD_EFFECT_SYSCTL};
    e.resource.sysctl.sysctl_id=ASTERISKD_SYSCTL_ROUTE_LOCALNET;
    strcpy(e.resource.sysctl.interface_name,"ap0");
    e.resource.sysctl.interface_index=42;
    e.resource.sysctl.desired_value=1;
    char error[128];
    for(int failed=0;failed<2;++failed) {
        f=(struct fake){.apply_fail=failed==0,.verify_fail=failed==1,.undo_fail=1};
        assert(asteriskd_effect_journal_apply(&journal,&e,&b,error,sizeof(error))!=0);
        assert(journal.count==1 && f.value==1 && journal.entries[0].resource.sysctl.original_value==0);
        assert(asteriskd_effect_journal_rollback(&journal,&b,error,sizeof(error))!=0 && journal.count==1);
        f.undo_fail=0;
        assert(asteriskd_effect_journal_rollback(&journal,&b,error,sizeof(error))==0 && journal.count==0 && f.value==0);
    }
    f=(struct fake){.apply_fail=1};
    assert(asteriskd_effect_journal_apply(&journal,&e,&b,error,sizeof(error))!=0 && journal.count==0 && f.value==0);
    f=(struct fake){0};
    assert(asteriskd_effect_journal_apply(&journal,&e,&b,error,sizeof(error))==0 && journal.count==1);
    assert(asteriskd_effect_journal_apply(&journal,&e,&b,error,sizeof(error))==0 && journal.count==1);
    assert(asteriskd_effect_journal_rollback(&journal,&b,error,sizeof(error))==0 && f.value==0);
    struct asteriskd_lifecycle_backend lb={.restore_best_effort=restore_lifecycle,.release=release};
    struct asteriskd_lifecycle lc={.backend=&lb,.backend_context=&f};
    lc.acquire.cleanup_required=true;
    f.undo_fail=1;
    assert(asteriskd_lifecycle_stop(&lc)==ASTERISKD_LIFECYCLE_STOP_FAILED && !lc.stopped && lc.acquire.cleanup_required);
    f.undo_fail=0;
    assert(asteriskd_lifecycle_stop(&lc)==0 && lc.stopped && !lc.acquire.cleanup_required && f.restores==2);
    return 0;
}
