/* SPDX-License-Identifier: GPL-3.0 */
#ifndef NF_NATIVE_RECOVERY_H
#define NF_NATIVE_RECOVERY_H
#include "supervisor/supervisor.h"

int nf_native_admit(struct asteriskd_system_supervisor *);
int nf_native_before_write(struct asteriskd_system_supervisor *,const struct asteriskd_effect *);
int nf_native_after_restore(struct asteriskd_system_supervisor *,const struct asteriskd_effect *);
int nf_native_finish(struct asteriskd_system_supervisor *);
#endif
