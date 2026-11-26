#!/bin/bash
# Builds asteriskd (the root supervisor from Asterisk4Magisk/asteriskd, GPL-3.0) for the root modes
# and drops it into jniLibs as libasteriskd.so. Same flags as the upstream AsteriskBOX build.
set -e
source "buildScript/init/env.sh"

ROOT="$(pwd)"
ASTERISKD_TAG=v2.3.2
ASTERISKD_COMMIT=a31fd6dfdbfb515b8c18cc91bc3637b523f0b255
SRC="$ROOT/../asteriskd-src"
API=24
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

if [ ! -d "$SRC/.git" ]; then
  git clone -q --depth 1 --branch "$ASTERISKD_TAG" https://github.com/Asterisk4Magisk/asteriskd "$SRC"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$ASTERISKD_COMMIT" ] || { echo "asteriskd $ASTERISKD_TAG moved: refusing to build"; exit 1; }

# Keep the downstream refresh staging primitive pinned to the same upstream
# revision. Embed its patch so the existing native cache key covers every edit.
ASTERISKD_PATCH=$(mktemp)
trap 'rm -f "$ASTERISKD_PATCH"' EXIT
cat > "$ASTERISKD_PATCH" <<'NIEFIGER_ASTERISKD_PATCH'
diff --git a/supervisor/supervisor_xtables.c b/supervisor/supervisor_xtables.c
--- a/supervisor/supervisor_xtables.c
+++ b/supervisor/supervisor_xtables.c
@@ -881,6 +881,60 @@
     return -1;
 }
 
+/*
+ * NieFiger: queue a replacement of only the daemon-owned mangle chains for
+ * TPROXY/TUN2SOCKS. The supervisor caller must own the reactor, hold a verified
+ * current rule snapshot, and supply a validated downstream config. No traffic
+ * hooks, routes, processes, DNS enhancement or TC/BPF resources are changed.
+ *
+ * This is a staging primitive, not a control endpoint. The caller must discard
+ * the entire batch on error, commit it once, and verify the new contents before
+ * acknowledging an update. A partial cross-family commit needs rollback or
+ * normal verified lifecycle cleanup.
+ */
+int niefiger_supervisor_queue_tether_chain_refresh(
+    struct asteriskd_system_supervisor *system) {
+    if (system == NULL || !system->rule_commands.active ||
+        !system->rules_runtime.installed || !system->rules_verified ||
+        system->verify_private_rules ||
+        system->loaded_config.config.dns_enhancement ||
+        system->rule_snapshot.phase != SYSTEM_RULE_SNAPSHOT_BEFORE_APPLY)
+        return -1;
+    enum asteriskd_mode mode = system->loaded_config.config.mode;
+    if (mode != ASTERISKD_MODE_TPROXY && mode != ASTERISKD_MODE_TUN2SOCKS)
+        return -1;
+    const struct asteriskd_rule_transaction_plan *plan = &system->rules_runtime.plan;
+    enum asteriskd_chain_id chain_id = mode == ASTERISKD_MODE_TPROXY
+        ? ASTERISKD_CHAIN_TPROXY : ASTERISKD_CHAIN_ROUTING;
+    size_t selected = 0U;
+    /* Validate every selected group before enqueueing any changes. */
+    for (size_t i = 0U; i < plan->private_group_count; ++i) {
+        const struct asteriskd_private_chain_group *group = &plan->private_groups[i];
+        if (group->table != ASTERISKD_IP_TABLE_MANGLE || group->chain_id != chain_id)
+            continue;
+        bool all_present = false, any_present = false;
+        if (!system->rules_runtime.private_cleanup_required[i] ||
+            asteriskd_supervisor_probe_private_group(system, group,
+                &all_present, &any_present) != 0 || !all_present)
+            return -1;
+        ++selected;
+    }
+    if (selected == 0U) return -1;
+    for (size_t i = 0U; i < plan->private_group_count; ++i) {
+        const struct asteriskd_private_chain_group *group = &plan->private_groups[i];
+        if (group->table != ASTERISKD_IP_TABLE_MANGLE || group->chain_id != chain_id)
+            continue;
+        for (size_t n = 0U; n < group->name_count; ++n) {
+            const char *name = group->names[n];
+            if (asteriskd_supervisor_xtables_zero(system, group->family, group->table,
+                    "-F", name, NULL, 0U) != 0 ||
+                system_populate_private_chain(system, group, name) != 0)
+                return -1;
+        }
+    }
+    return 0;
+}
+
 int asteriskd_supervisor_create_private_group(struct asteriskd_system_supervisor *system,
     const struct asteriskd_private_chain_group *group) {
     for (size_t index = 0U; index < group->name_count; ++index) {
diff --git a/supervisor/supervisor.h b/supervisor/supervisor.h
--- a/supervisor/supervisor.h
+++ b/supervisor/supervisor.h
@@ -327,6 +327,10 @@
     enum asteriskd_ip_family family, enum asteriskd_ip_table table,
     const char *operation, const char *chain, const char *const *arguments,
     size_t argument_count);
+/* Stage only existing TPROXY/TUN2SOCKS mangle-chain replacements in an owned batch. */
+int niefiger_supervisor_queue_tether_chain_refresh(
+    struct asteriskd_system_supervisor *system);
+
 int asteriskd_supervisor_create_private_group(struct asteriskd_system_supervisor *system,
     const struct asteriskd_private_chain_group *group);
 int asteriskd_supervisor_verify_private_group_contents(
@@ -446,6 +450,12 @@
     struct asteriskd_system_supervisor *system);
 int asteriskd_supervisor_reconcile_hotspot_tc(struct asteriskd_system_supervisor *system);
 
+/* Refresh exact BPF2SOCKS downstreams while retaining helper and pins. */
+int niefiger_supervisor_refresh_bpf_tether(struct asteriskd_system_supervisor *system,
+    char previous[][ASTERISKD_MAX_INTERFACE_NAME], size_t previous_count);
+int niefiger_supervisor_rtnl_remove_bpf_filter(struct asteriskd_system_supervisor *system,
+    const struct asteriskd_tc_filter_resource *resource);
+
 /* supervisor/supervisor_reconcile.c */
 uint32_t asteriskd_supervisor_rule_categories(const struct asteriskd_config *config);
 void asteriskd_supervisor_reconcile_error(
diff --git a/supervisor/supervisor.c b/supervisor/supervisor.c
--- a/supervisor/supervisor.c
+++ b/supervisor/supervisor.c
@@ -541,6 +541,179 @@
     }
 }
 
+/* App-owned exact downstream refresh. Requests are consumed only by the
+ * reactor, never from inside the control callback or a nested action pump.
+ * Keep boot selectors, DNS enhancement, service-control and IPv6 guard
+ * sessions on their existing lifecycle until those paths support hot changes. */
+#define NF_TETHER_REQUEST "nf-tether-request"
+#define NF_TETHER_ACK "nf-tether-ack"
+#define NF_TETHER_ACK_TMP ".nf-tether-ack.new"
+
+static bool system_nf_tether_allowed(struct asteriskd_system_supervisor *system) {
+    const struct asteriskd_config *c = &system->loaded_config.config;
+    if (c->owner != ASTERISKD_OWNER_BOX ||
+        (c->mode != ASTERISKD_MODE_TPROXY && c->mode != ASTERISKD_MODE_TUN2SOCKS &&
+         c->mode != ASTERISKD_MODE_BPF2SOCKS) ||
+        (c->mode == ASTERISKD_MODE_BPF2SOCKS && (!system->b2s_verified || system->helper_reaped)) ||
+        c->service_control.enabled || c->dns_enhancement || c->disable_system_ipv6 ||
+        !system->runtime->supervising || system->state.phase != ASTERISKD_PHASE_RUNNING ||
+        !system->rules_verified || system->rules_reconciling || system->cleanup_in_progress ||
+        system->shutdown_requested || system->stop_requested)
+        return false;
+    for (size_t i = 0; i < c->hotspot_interface_count; ++i)
+        if (strchr(c->hotspot_interfaces[i], '+') != NULL) return false;
+    return true;
+}
+
+static bool system_nf_tether_pending(struct asteriskd_system_supervisor *system) {
+    if (!system_nf_tether_allowed(system)) return false;
+    struct stat st;
+    return fstatat(system->loaded_config.directory.fd, NF_TETHER_REQUEST, &st,
+        AT_SYMLINK_NOFOLLOW) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 &&
+        (st.st_mode & 077U) == 0U;
+}
+
+struct nf_tether_request {
+    char token[33];
+    char names[ASTERISKD_MAX_INTERFACES][ASTERISKD_MAX_INTERFACE_NAME];
+    unsigned indices[ASTERISKD_MAX_INTERFACES];
+    size_t count;
+};
+
+static bool system_nf_tether_links_current(const struct nf_tether_request *r) {
+    for (size_t i = 0; i < r->count; ++i)
+        if (if_nametoindex(r->names[i]) != r->indices[i]) return false;
+    return true;
+}
+
+static bool system_nf_tether_read(struct asteriskd_system_supervisor *system,
+    struct nf_tether_request *r) {
+    int dir = system->loaded_config.directory.fd;
+    int fd = openat(dir, NF_TETHER_REQUEST, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
+    if (fd < 0) return false;
+    struct stat st;
+    char buf[4096];
+    bool ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 &&
+        (st.st_mode & 077U) == 0U && st.st_size > 0 && st.st_size < (off_t)sizeof(buf);
+    size_t used = 0;
+    if (ok) {
+        while (used < (size_t)st.st_size) {
+            ssize_t n = read(fd, buf + used, (size_t)st.st_size - used);
+            if (n < 0 && errno == EINTR) continue;
+            if (n <= 0) { ok = false; break; }
+            used += (size_t)n;
+        }
+    }
+    (void)close(fd);
+    if (unlinkat(dir, NF_TETHER_REQUEST, 0) != 0) return false;
+    if (!ok || memchr(buf, '\0', used) != NULL) return false;
+    buf[used] = '\0';
+    memset(r, 0, sizeof(*r));
+    char *save = NULL;
+    char *pid = strtok_r(buf, " \n", &save);
+    char *token = strtok_r(NULL, " \n", &save);
+    char expected_pid[32];
+    (void)snprintf(expected_pid, sizeof(expected_pid), "%ld", (long)getpid());
+    if (pid == NULL || strcmp(pid, expected_pid) != 0 || token == NULL ||
+        strlen(token) != 32U || strspn(token, "0123456789abcdef") != 32U) return false;
+    memcpy(r->token, token, 33U);
+    const struct asteriskd_config *c = &system->loaded_config.config;
+    for (char *item = strtok_r(NULL, " \n", &save); item != NULL;
+         item = strtok_r(NULL, " \n", &save)) {
+        char *sep = strchr(item, ':');
+        if (sep == NULL || r->count >= ASTERISKD_MAX_INTERFACES) return false;
+        *sep++ = '\0';
+        if (!asteriskd_interface_selector_valid(item) || strchr(item, '+') != NULL ||
+            strcmp(item, ".") == 0 || strcmp(item, "..") == 0 ||
+            strcmp(item, "lo") == 0 || strcmp(item, "asterisk0") == 0 ||
+            strcmp(item, "hevtun0") == 0) return false;
+        for (size_t i = 0; i < c->ignored_interface_count; ++i)
+            if (asteriskd_interface_matches_selector(item, c->ignored_interfaces[i])) return false;
+        for (size_t i = 0; i < c->virtual_interface_count; ++i)
+            if (strcmp(item, c->virtual_interfaces[i]) == 0) return false;
+        for (size_t i = 0; i < r->count; ++i)
+            if (strcmp(item, r->names[i]) == 0) return false;
+        if (*sep == '\0' || strspn(sep, "0123456789") != strlen(sep)) return false;
+        errno = 0;
+        unsigned long index = strtoul(sep, NULL, 10);
+        if (errno != 0 || index == 0 || index > UINT_MAX ||
+            if_nametoindex(item) != (unsigned)index) return false;
+        (void)snprintf(r->names[r->count], sizeof(r->names[r->count]), "%s", item);
+        r->indices[r->count++] = (unsigned)index;
+    }
+    return true;
+}
+
+static void system_nf_tether_ack(struct asteriskd_system_supervisor *system,
+    const struct nf_tether_request *r, const char *result) {
+    int dir = system->loaded_config.directory.fd;
+    (void)unlinkat(dir, NF_TETHER_ACK_TMP, 0);
+    int fd = openat(dir, NF_TETHER_ACK_TMP,
+        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
+    if (fd < 0) return;
+    char line[64];
+    int len = snprintf(line, sizeof(line), "%s %s\n", r->token, result);
+    bool ok = len > 0 && (size_t)len < sizeof(line) &&
+        write(fd, line, (size_t)len) == (ssize_t)len;
+    if (close(fd) != 0) ok = false;
+    if (ok && renameat(dir, NF_TETHER_ACK_TMP, dir, NF_TETHER_ACK) == 0) return;
+    (void)unlinkat(dir, NF_TETHER_ACK_TMP, 0);
+}
+
+static int system_nf_tether_replace(struct asteriskd_system_supervisor *system) {
+    int rc = -1;
+    if (asteriskd_supervisor_capture_local_address_snapshot(system) == 0 &&
+        asteriskd_supervisor_rule_snapshot_capture(system, &system->rules_runtime.plan,
+            SYSTEM_RULE_SNAPSHOT_BEFORE_APPLY) == 0 &&
+        asteriskd_supervisor_rule_batch_begin(&system->rule_commands) == 0 &&
+        niefiger_supervisor_queue_tether_chain_refresh(system) == 0 &&
+        asteriskd_supervisor_rule_batch_flush(system) == 0) rc = 0;
+    asteriskd_supervisor_rule_batch_destroy(&system->rule_commands);
+    asteriskd_supervisor_rule_snapshot_destroy(&system->rule_snapshot);
+    if (rc == 0 && (asteriskd_supervisor_rule_snapshot_capture(system,
+            &system->rules_runtime.plan, SYSTEM_RULE_SNAPSHOT_AFTER_APPLY) != 0 ||
+        asteriskd_rules_verify(&system->rules_runtime, &system->rules_backend) != 0)) rc = -1;
+    asteriskd_supervisor_rule_snapshot_destroy(&system->rule_snapshot);
+    asteriskd_supervisor_clear_local_address_snapshot(system);
+    return rc;
+}
+
+/* Called after the ordinary reconcile verified the previous rules. If a
+ * table commit or verification fails, restore the old exact selectors and
+ * verify both families again. Unconfirmed rollback is a lifecycle failure. */
+static int system_nf_tether_refresh(struct asteriskd_system_supervisor *system) {
+    if (!system_nf_tether_pending(system)) return 0;
+    struct nf_tether_request r;
+    if (!system_nf_tether_read(system, &r)) return 0;
+    struct asteriskd_config *c = &system->loaded_config.config;
+    char previous[ASTERISKD_MAX_INTERFACES][ASTERISKD_MAX_INTERFACE_NAME];
+    memcpy(previous, c->hotspot_interfaces, sizeof(previous));
+    size_t previous_count = c->hotspot_interface_count;
+    system->rules_reconciling = true;
+    memcpy(c->hotspot_interfaces, r.names, sizeof(r.names));
+    c->hotspot_interface_count = r.count;
+    bool bpf = c->mode == ASTERISKD_MODE_BPF2SOCKS;
+    int rc = bpf ? niefiger_supervisor_refresh_bpf_tether(system, previous, previous_count)
+                 : system_nf_tether_replace(system);
+    if (rc == 0 && system_nf_tether_links_current(&r) &&
+        (bpf || asteriskd_supervisor_reconcile_hotspot_tc(system) == 0)) {
+        system->rules_reconciling = false;
+        system_nf_tether_ack(system, &r, "applied");
+        return 0;
+    }
+    memcpy(c->hotspot_interfaces, previous, sizeof(previous));
+    c->hotspot_interface_count = previous_count;
+    rc = bpf ? niefiger_supervisor_refresh_bpf_tether(system, r.names, r.count)
+             : system_nf_tether_replace(system);
+    system->rules_reconciling = false;
+    if (rc != 0) {
+        system->rules_verified = false;
+        return -1;
+    }
+    system_nf_tether_ack(system, &r, "rejected");
+    return 0;
+}
+
 static int system_runtime_dispatch(void *opaque, const struct asteriskd_poll_source *source,
     short ready, struct asteriskd_runtime_delta *delta) {
     struct asteriskd_system_supervisor *system = opaque;
@@ -622,6 +795,9 @@
                 "WiFi event dispatch failed");
         }
     }
+    if ((source->kind == ASTERISKD_POLL_CONTROL_CLIENT ||
+         source->kind == ASTERISKD_POLL_CONTROL_LISTENER) && system_nf_tether_pending(system))
+        delta->flags |= ASTERISKD_DELTA_RECONCILE_DUE;
     system_service_take_actions(system, delta);
     if (system->stop_requested &&
         (delta->flags & ASTERISKD_DELTA_STOP_REQUESTED) == 0U) {
@@ -1187,7 +1363,8 @@
         }
         return -1;
     }
-    system->rules_verified = true;
+    system->rules_verified = true;
+    if (system_nf_tether_refresh(system) != 0) return -1;
     if (asteriskd_mode_core_managed(system->loaded_config.config.mode)) {
         *active = false;
         *generation = 0U;
diff --git a/supervisor/supervisor_rtnl.c b/supervisor/supervisor_rtnl.c
--- a/supervisor/supervisor_rtnl.c
+++ b/supervisor/supervisor_rtnl.c
@@ -861,4 +861,35 @@
     return result == 0 ? 0 : -1;
 }
 
+/* Delete only the previously verified BPF filter at an exact ifindex. */
+int niefiger_supervisor_rtnl_remove_bpf_filter(struct asteriskd_system_supervisor *system,
+    const struct asteriskd_tc_filter_resource *resource) {
+    if (system == NULL || resource == NULL || resource->interface_index == 0U ||
+        asteriskd_supervisor_interface_state(resource->interface_name,
+            resource->interface_index) != 0) return -1;
+    uint32_t parent = resource->direction == ASTERISKD_TC_DIRECTION_INGRESS
+        ? ASTERISKD_TC_PARENT_CLSACT_INGRESS
+        : resource->direction == ASTERISKD_TC_DIRECTION_EGRESS
+            ? ASTERISKD_TC_PARENT_CLSACT_EGRESS : 0U;
+    if (parent == 0U) return -1;
+    struct rtnl_request request;
+    memset(&request, 0, sizeof(request));
+    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(struct tcmsg));
+    request.header.nlmsg_type = RTM_DELTFILTER;
+    request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
+    request.body.filter.tcm_family = AF_UNSPEC;
+    request.body.filter.tcm_ifindex = (int)resource->interface_index;
+    request.body.filter.tcm_parent = parent;
+    request.body.filter.tcm_info = TC_H_MAKE(ASTERISKD_HOTSPOT_TC_PRIORITY << 16U,
+        htons((uint16_t)ASTERISKD_ETH_PROTOCOL_ALL));
+    request.body.filter.tcm_handle = ASTERISKD_HOTSPOT_TC_HANDLE;
+    if (rtnl_attribute_add(&request, TCA_KIND, "bpf", sizeof("bpf")) != 0) return -1;
+    int fd = rtnl_open();
+    if (fd < 0) return -1;
+    int rc = rtnl_transact(fd, &request.header, NULL, NULL);
+    (void)close(fd);
+    if (rc > 0) rtnl_record_error(system, rc);
+    return rc == 0 ? 0 : -1;
+}
+
 #endif
diff --git a/supervisor/supervisor_network.c b/supervisor/supervisor_network.c
--- a/supervisor/supervisor_network.c
+++ b/supervisor/supervisor_network.c
@@ -573,4 +573,79 @@
     return result;
 }
 
+/* Never delete the shared clsact or a foreign filter. Use the live helper's
+ * verified pin identities and verify detachment after the kernel ACK. */
+static int system_nf_detach_bpf_interface(struct asteriskd_system_supervisor *system,
+    const char *name, uint32_t index) {
+    for (unsigned i = 0; i < 2U; ++i) {
+        bool ingress = i == 0U;
+        struct asteriskd_tc_filter_resource f = {0};
+        f.interface_index = index;
+        f.direction = ingress ? ASTERISKD_TC_DIRECTION_INGRESS : ASTERISKD_TC_DIRECTION_EGRESS;
+        f.filter_id = ingress ? ASTERISKD_FILTER_HOTSPOT_INGRESS : ASTERISKD_FILTER_HOTSPOT_EGRESS;
+        f.program_id = ingress ? ASTERISKD_PROGRAM_BPF2SOCKS_INGRESS : ASTERISKD_PROGRAM_BPF2SOCKS_EGRESS;
+        (void)snprintf(f.interface_name, sizeof(f.interface_name), "%s", name);
+        enum asteriskd_pin_id pin = ingress ? ASTERISKD_PIN_BPF2SOCKS_TC_INGRESS
+                                           : ASTERISKD_PIN_BPF2SOCKS_TC_EGRESS;
+        if (asteriskd_supervisor_verified_pin_id(system, pin) == 0U) return -1;
+        int link = asteriskd_supervisor_interface_state(name, index);
+        if (link == 1) return 0;
+        if (link != 0) return -1;
+        enum asteriskd_rules_slot_state state = ASTERISKD_RULES_SLOT_FOREIGN;
+        if (asteriskd_supervisor_tc_probe_filter(system, &f, &state) != 0) {
+            if (asteriskd_supervisor_interface_state(name, index) == 1) return 0;
+            return -1;
+        }
+        if (state != ASTERISKD_RULES_SLOT_OWNED) continue;
+        int removed = niefiger_supervisor_rtnl_remove_bpf_filter(system, &f);
+        link = asteriskd_supervisor_interface_state(name, index);
+        if (link == 1) return 0;
+        if (link != 0 || removed != 0 ||
+            asteriskd_supervisor_tc_probe_filter(system, &f, &state) != 0 ||
+            state == ASTERISKD_RULES_SLOT_OWNED) return -1;
+    }
+    return 0;
+}
+
+int niefiger_supervisor_refresh_bpf_tether(struct asteriskd_system_supervisor *system,
+    char previous[][ASTERISKD_MAX_INTERFACE_NAME], size_t previous_count) {
+    if (system == NULL || !system->b2s_verified || system->helper_reaped ||
+        system->loaded_config.config.mode != ASTERISKD_MODE_BPF2SOCKS ||
+        previous_count > ASTERISKD_MAX_INTERFACES) return -1;
+    const struct asteriskd_config *c = &system->loaded_config.config;
+    for (size_t i = 0; i < previous_count; ++i) {
+        if (system_hotspot_interface_selected(c, previous[i])) continue;
+        uint32_t index = 0U;
+        int found = system_interface_index(previous[i], &index);
+        if (found == 1) continue;
+        if (found != 0 || system_nf_detach_bpf_interface(system, previous[i], index) != 0)
+            return -1;
+    }
+    /* Restore only recorded route_localnet values for deselected links.
+     * The journal distinguishes removed/replaced ifindices. */
+    struct asteriskd_effect_journal *journal = &system->volatile_effects;
+    struct asteriskd_effect_backend backend = asteriskd_supervisor_effect_backend(system);
+    for (size_t i = journal->count; i > 0U; --i) {
+        struct asteriskd_effect e = journal->entries[i - 1U];
+        if (e.kind != ASTERISKD_EFFECT_SYSCTL ||
+            e.resource.sysctl.sysctl_id != ASTERISKD_SYSCTL_ROUTE_LOCALNET ||
+            e.resource.sysctl.interface_index == 0U ||
+            system_hotspot_interface_selected(c, e.resource.sysctl.interface_name)) continue;
+        char error[128U];
+        if (asteriskd_effect_journal_restore(journal, &e, &backend, error, sizeof(error)) != 0)
+            return -1;
+    }
+    if (asteriskd_supervisor_reconcile_b2s_local_maps(system) != 0 ||
+        asteriskd_supervisor_reconcile_hotspot_tc(system) != 0) return -1;
+    /* Explicitly probe selected links, even if absent from getifaddrs. */
+    for (size_t i = 0; i < c->hotspot_interface_count; ++i) {
+        uint32_t index = 0U;
+        int found = system_interface_index(c->hotspot_interfaces[i], &index);
+        if (found == 1) continue; /* Rollback can include a vanished old link. */
+        if (found != 0 || system_reconcile_hotspot_tc_interface(system,
+                c->hotspot_interfaces[i], index) != 0) return -1;
+    }
+    return 0;
+}
+
 #endif
diff --git a/runtime/lifecycle.c b/runtime/lifecycle.c
--- a/runtime/lifecycle.c
+++ b/runtime/lifecycle.c
@@ -147,12 +147,13 @@
     try_inverse(lifecycle, &lifecycle->matcher, backend->stop_matcher);
     try_inverse(lifecycle, &lifecycle->helper, backend->stop_helper);
     try_inverse(lifecycle, &lifecycle->core, backend->stop_core);
-    if (backend->restore_best_effort != NULL) {
-        (void)backend->restore_best_effort(context);
-    }
-    try_inverse(lifecycle, &lifecycle->acquire, backend->release);
-    lifecycle->starting = false;
-    bool remains = lifecycle->traffic_may_be_active || lifecycle->rules.cleanup_required ||
+    /* A failed restoration is a remaining cleanup obligation. Do not
+     * advertise STOPPED or allow a service-control cycle to discard it. */
+    bool restore_failed = backend->restore_best_effort != NULL &&
+        backend->restore_best_effort(context) != 0;
+    if (!restore_failed) try_inverse(lifecycle, &lifecycle->acquire, backend->release);
+    lifecycle->starting = false;
+    bool remains = restore_failed || lifecycle->traffic_may_be_active || lifecycle->rules.cleanup_required ||
         lifecycle->network.cleanup_required || lifecycle->matcher.cleanup_required ||
         lifecycle->helper.cleanup_required || lifecycle->core.cleanup_required ||
         lifecycle->acquire.cleanup_required;
diff --git a/resources/effect_journal.c b/resources/effect_journal.c
--- a/resources/effect_journal.c
+++ b/resources/effect_journal.c
@@ -160,10 +160,34 @@
             return result;
         }
     }
-    result = backend->apply(backend->context, &candidate, error, error_size);
-    if (result != 0) {
-        return result;
-    }
+    /* A sysctl write can change the kernel value even when the caller
+     * receives an error. Journal the original before attempting the write. */
+    if (candidate.kind == ASTERISKD_EFFECT_SYSCTL) {
+        if (existing_index == journal->count) ++journal->count;
+        journal->entries[existing_index] = candidate;
+        result = backend->apply(backend->context, &candidate, error, error_size);
+        if (result == 0) {
+            result = backend->verify_applied(backend->context, &candidate, error, error_size);
+        }
+        if (result != 0) {
+            char cleanup_error[128U] = {0};
+            /* Already restored (or old interface gone): no write needed.
+             * Otherwise try undo, but retain the entry unless verified. */
+            if (backend->verify_restored(backend->context, &candidate,
+                    cleanup_error, sizeof(cleanup_error)) == 0 ||
+                (backend->undo(backend->context, &candidate,
+                    cleanup_error, sizeof(cleanup_error)) == 0 &&
+                 backend->verify_restored(backend->context, &candidate,
+                    cleanup_error, sizeof(cleanup_error)) == 0)) {
+                effect_remove(journal, existing_index);
+            }
+        }
+        return result;
+    }
+    result = backend->apply(backend->context, &candidate, error, error_size);
+    if (result != 0) {
+        return result;
+    }
     result = backend->verify_applied(
         backend->context, &candidate, error, error_size);
     if (result != 0) {
NIEFIGER_ASTERISKD_PATCH
if git -C "$SRC" apply --reverse --check "$ASTERISKD_PATCH" >/dev/null 2>&1; then
  echo "asteriskd tether staging patch already applied"
else
  git -C "$SRC" apply --check "$ASTERISKD_PATCH"
  git -C "$SRC" apply "$ASTERISKD_PATCH"
fi

# Scoped TPROXY and HEV DNS interception. Exact anchors are checked against the pinned
# source, including when a cached checkout has already received the patch.
python3 - "$SRC" <<'NF_TETHER_DNS_PATCH'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
replacements = [["asteriskd.h","    bool enable_local_dns;\n    bool dns_enhancement;","    bool enable_local_dns;\n    bool nf_tether_dns_hijack;\n    bool dns_enhancement;"],["core/config.c","        \"bypassPrivateCidrs\", \"appPolicy\",\n    };\n    size_t values[11];\n    if (object_fields(document, object, names, 11U, values) != 0 ||","        \"bypassPrivateCidrs\", \"appPolicy\", \"nfTetherDnsHijack\",\n    };\n    size_t values[12];\n    // Older published boot configs keep their exact original field set.\n    size_t fields = document->tokens[object].child_count == 12U ? 12U : 11U;\n    if (object_fields(document, object, names, fields, values) != 0 ||"],["core/config.c","    return parse_policy(document, values[10], config);\n}","    if (fields == 12U && parse_bool(document, values[11], &config->nf_tether_dns_hijack) != 0)\n        return -1;\n    return parse_policy(document, values[10], config);\n}"],["core/config.c","    if (asteriskd_mode_core_managed(config->mode) &&\n        (config->enable_local_dns","    if (config->nf_tether_dns_hijack) {\n        if (config->owner != ASTERISKD_OWNER_BOX || (config->mode != ASTERISKD_MODE_TPROXY &&\n             config->mode != ASTERISKD_MODE_TUN2SOCKS) ||\n            config->enable_local_dns || config->dns_enhancement) return -1;\n        for (size_t i = 0; i < config->hotspot_interface_count; ++i) {\n            const char *name = config->hotspot_interfaces[i];\n            if (strchr(name, '+') != NULL || strcmp(name, \"lo\") == 0 ||\n                strcmp(name, \"asterisk0\") == 0 || strcmp(name, \"hevtun0\") == 0) return -1;\n        }\n    }\n    if (asteriskd_mode_core_managed(config->mode) &&\n        (config->enable_local_dns"],["supervisor/supervisor_xtables.c","static int system_populate_tproxy_prerouting(\n","/* NieFiger: only exact selected tether interfaces, never OUTPUT or all\n * PREROUTING. Rebuilt through the existing owned chain transaction and\n * hot downstream refresh, including native verification and rollback. */\nstatic int system_nf_tether_dns_tproxy(struct asteriskd_system_supervisor *system,\n    enum asteriskd_ip_family family, const char *chain) {\n    const struct asteriskd_config *config = &system->loaded_config.config;\n    if (!config->nf_tether_dns_hijack) return 0;\n    char port[8U];\n    if (snprintf(port, sizeof(port), \"%u\", (unsigned)config->transparent_port) <= 0) return -1;\n    for (size_t i = 0; i < config->hotspot_interface_count; ++i) {\n        const char *name = config->hotspot_interfaces[i];\n        if (strchr(name, '+') != NULL || strcmp(name, \"lo\") == 0 ||\n            strcmp(name, \"asterisk0\") == 0 || strcmp(name, \"hevtun0\") == 0) return -1;\n        bool ignored = false;\n        for (size_t j = 0; j < config->ignored_interface_count; ++j)\n            if (asteriskd_interface_matches_selector(name, config->ignored_interfaces[j])) ignored = true;\n        if (ignored) continue;\n        for (unsigned p = 0; p < 2U; ++p) {\n            const char *protocol = p ? \"tcp\" : \"udp\";\n            if (p && system_append_established_tcp6_dns(system, family, chain, name, false) != 0)\n                return -1;\n            const char *args[] = {\"-i\", name, \"-p\", protocol, \"-m\", protocol, \"--dport\", \"53\",\n                \"-j\", \"TPROXY\", \"--on-port\", port, \"--on-ip\",\n                family == ASTERISKD_IP_FAMILY_IPV4 ? \"0.0.0.0\" : \"::\",\n                \"--tproxy-mark\", ASTERISKD_ROUTE_MATCH};\n            if (asteriskd_supervisor_xtables_zero(system, family, ASTERISKD_IP_TABLE_MANGLE, \"-A\",\n                chain, args, sizeof(args) / sizeof(args[0])) != 0) return -1;\n        }\n    }\n    return 0;\n}\n\nstatic int system_populate_tproxy_prerouting(\n"],["supervisor/supervisor_xtables.c","    if (config->enable_local_dns && !config->dns_enhancement && system_append_dns_tproxy(system, family, chain) != 0) return -1;\n    if (family == ASTERISKD_IP_FAMILY_IPV6 && !config->enable_ipv6) return 0;\n    if (system_append_ipsec_udp_bypass(system, family, chain) != 0) return -1;\n    if (system_append_local_bypass_interval(","    if (config->enable_local_dns && !config->dns_enhancement && system_append_dns_tproxy(system, family, chain) != 0) return -1;\n    if (family == ASTERISKD_IP_FAMILY_IPV6 && !config->enable_ipv6) return 0;\n    if (system_append_ipsec_udp_bypass(system, family, chain) != 0) return -1;\n    if (system_nf_tether_dns_tproxy(system, family, chain) != 0) return -1;\n    if (system_append_local_bypass_interval("],["supervisor/supervisor_xtables.c","static int system_populate_tun_prerouting(\n    struct asteriskd_system_supervisor *system, enum asteriskd_ip_family family,\n    const char *chain, const char *local_begin, const char *local_end) {\n    const struct asteriskd_config *config = &system->loaded_config.config;\n    if (config->dns_enhancement && system_enhanced_dns_prerouting(system, family, chain, false) != 0) return -1;\n    if (config->enable_local_dns && !config->dns_enhancement && system_append_dns_mark(system, family, chain, false) != 0) {\n        return -1;\n    }\n    if (family == ASTERISKD_IP_FAMILY_IPV6 && !config->enable_ipv6) return 0;\n    if (system_append_ipsec_udp_bypass(system, family, chain) != 0) return -1;\n    if (system_append_local_bypass_interval(\n            system, family, chain, local_begin, local_end) != 0) return -1;","/* HEV uses a unicast route, not TPROXY's local route. Never touch\n * the handset's own DNS sockets, broadcasts, multicast or link-local IPv6 here.\n * Stop before private-CIDR bypass; the existing mark/table sends the\n * request into HEV. All rules belong to the existing owned chain. */\nstatic int system_nf_tether_dns_hev(struct asteriskd_system_supervisor *system,\n    enum asteriskd_ip_family family, const char *chain) {\n    const struct asteriskd_config *config = &system->loaded_config.config;\n    if (!config->nf_tether_dns_hijack ||\n        (family == ASTERISKD_IP_FAMILY_IPV6 && !config->enable_ipv6)) return 0;\n    for (size_t i = 0; i < config->hotspot_interface_count; ++i) {\n        const char *name = config->hotspot_interfaces[i];\n        if (strchr(name, '+') != NULL || strcmp(name, \"lo\") == 0 ||\n            strcmp(name, \"asterisk0\") == 0 || strcmp(name, config->helper.value.hev.tunnel_name) == 0) return -1;\n        bool ignored = false;\n        for (size_t j = 0; j < config->ignored_interface_count; ++j)\n            if (asteriskd_interface_matches_selector(name, config->ignored_interfaces[j])) ignored = true;\n        if (ignored) continue;\n        for (unsigned p = 0; p < 2U; ++p) {\n            const char *protocol = p ? \"tcp\" : \"udp\";\n            if (family == ASTERISKD_IP_FAMILY_IPV6) {\n                /* IPv6 UNICAST also includes local addresses; exclude LOCAL\n                 * explicitly, and never move link-local or multicast into HEV. */\n                const char *prefixes[] = {\"2000::/3\", \"fc00::/7\"};\n                for (size_t n = 0; n < sizeof(prefixes) / sizeof(prefixes[0]); ++n) {\n                    const char *mark6[] = {\"-d\", prefixes[n], \"-i\", name, \"-p\", protocol,\n                        \"-m\", protocol, \"--dport\", \"53\", \"-m\", \"addrtype\", \"!\", \"--dst-type\", \"LOCAL\",\n                        \"-j\", \"MARK\", \"--set-xmark\", ASTERISKD_ROUTE_MATCH};\n                    const char *stop6[] = {\"-d\", prefixes[n], \"-i\", name, \"-p\", protocol,\n                        \"-m\", protocol, \"--dport\", \"53\", \"-m\", \"addrtype\", \"!\", \"--dst-type\", \"LOCAL\",\n                        \"-j\", \"RETURN\"};\n                    if (asteriskd_supervisor_xtables_zero(system, family, ASTERISKD_IP_TABLE_MANGLE, \"-A\",\n                        chain, mark6, sizeof(mark6) / sizeof(mark6[0])) != 0 ||\n                        asteriskd_supervisor_xtables_zero(system, family, ASTERISKD_IP_TABLE_MANGLE, \"-A\",\n                        chain, stop6, sizeof(stop6) / sizeof(stop6[0])) != 0) return -1;\n                }\n                continue;\n            }\n            const char *mark[] = {\"-i\", name, \"-p\", protocol, \"-m\", protocol, \"--dport\", \"53\",\n                \"-m\", \"addrtype\", \"--dst-type\", \"UNICAST\", \"-j\", \"MARK\",\n                \"--set-xmark\", ASTERISKD_ROUTE_MATCH};\n            const char *stop[] = {\"-i\", name, \"-p\", protocol, \"-m\", protocol, \"--dport\", \"53\",\n                \"-m\", \"addrtype\", \"--dst-type\", \"UNICAST\", \"-j\", \"RETURN\"};\n            if (asteriskd_supervisor_xtables_zero(system, family, ASTERISKD_IP_TABLE_MANGLE, \"-A\",\n                chain, mark, sizeof(mark) / sizeof(mark[0])) != 0 ||\n                asteriskd_supervisor_xtables_zero(system, family, ASTERISKD_IP_TABLE_MANGLE, \"-A\",\n                chain, stop, sizeof(stop) / sizeof(stop[0])) != 0) return -1;\n        }\n    }\n    return 0;\n}\n\nstatic int system_populate_tun_prerouting(\n    struct asteriskd_system_supervisor *system, enum asteriskd_ip_family family,\n    const char *chain, const char *local_begin, const char *local_end) {\n    const struct asteriskd_config *config = &system->loaded_config.config;\n    if (config->dns_enhancement && system_enhanced_dns_prerouting(system, family, chain, false) != 0) return -1;\n    if (config->enable_local_dns && !config->dns_enhancement && system_append_dns_mark(system, family, chain, false) != 0) {\n        return -1;\n    }\n    if (family == ASTERISKD_IP_FAMILY_IPV6 && !config->enable_ipv6) return 0;\n    if (system_append_ipsec_udp_bypass(system, family, chain) != 0) return -1;\n    if (system_append_local_bypass_interval(\n            system, family, chain, local_begin, local_end) != 0) return -1;\n    if (system_nf_tether_dns_hev(system, family, chain) != 0) return -1;"]]
# Upgrade a cached checkout patched by 5bb7a6e without duplicating the HEV helper.
legacy_hev = "/* HEV uses a unicast route, not TPROXY's local route. Never touch\n * the handset's own DNS sockets, broadcasts, multicast or IPv6 here.\n * Stop before private-CIDR bypass; the existing mark/table sends the\n * request into HEV. All rules belong to the existing owned chain. */\nstatic int system_nf_tether_dns_hev(struct asteriskd_system_supervisor *system,\n    enum asteriskd_ip_family family, const char *chain) {\n    const struct asteriskd_config *config = &system->loaded_config.config;\n    if (!config->nf_tether_dns_hijack || family != ASTERISKD_IP_FAMILY_IPV4) return 0;\n    for (size_t i = 0; i < config->hotspot_interface_count; ++i) {\n        const char *name = config->hotspot_interfaces[i];\n        if (strchr(name, '+') != NULL || strcmp(name, \"lo\") == 0 ||\n            strcmp(name, \"asterisk0\") == 0 || strcmp(name, config->helper.value.hev.tunnel_name) == 0) return -1;\n        bool ignored = false;\n        for (size_t j = 0; j < config->ignored_interface_count; ++j)\n            if (asteriskd_interface_matches_selector(name, config->ignored_interfaces[j])) ignored = true;\n        if (ignored) continue;\n        for (unsigned p = 0; p < 2U; ++p) {\n            const char *protocol = p ? \"tcp\" : \"udp\";\n            const char *mark[] = {\"-i\", name, \"-p\", protocol, \"-m\", protocol, \"--dport\", \"53\",\n                \"-m\", \"addrtype\", \"--dst-type\", \"UNICAST\", \"-j\", \"MARK\",\n                \"--set-xmark\", ASTERISKD_ROUTE_MATCH};\n            const char *stop[] = {\"-i\", name, \"-p\", protocol, \"-m\", protocol, \"--dport\", \"53\",\n                \"-m\", \"addrtype\", \"--dst-type\", \"UNICAST\", \"-j\", \"RETURN\"};\n            if (asteriskd_supervisor_xtables_zero(system, family, ASTERISKD_IP_TABLE_MANGLE, \"-A\",\n                chain, mark, sizeof(mark) / sizeof(mark[0])) != 0 ||\n                asteriskd_supervisor_xtables_zero(system, family, ASTERISKD_IP_TABLE_MANGLE, \"-A\",\n                chain, stop, sizeof(stop) / sizeof(stop[0])) != 0) return -1;\n        }\n    }\n    return 0;\n}\n\nstatic int system_populate_tun_prerouting(\n    struct asteriskd_system_supervisor *system, enum asteriskd_ip_family family,\n    const char *chain, const char *local_begin, const char *local_end) {\n    const struct asteriskd_config *config = &system->loaded_config.config;\n    if (config->dns_enhancement && system_enhanced_dns_prerouting(system, family, chain, false) != 0) return -1;\n    if (config->enable_local_dns && !config->dns_enhancement && system_append_dns_mark(system, family, chain, false) != 0) {\n        return -1;\n    }\n    if (family == ASTERISKD_IP_FAMILY_IPV6 && !config->enable_ipv6) return 0;\n    if (system_append_ipsec_udp_bypass(system, family, chain) != 0) return -1;\n    if (system_append_local_bypass_interval(\n            system, family, chain, local_begin, local_end) != 0) return -1;\n    if (system_nf_tether_dns_hev(system, family, chain) != 0) return -1;"
hev_path = root / "supervisor/supervisor_xtables.c"
hev_source = hev_path.read_text()
if hev_source.count(legacy_hev) == 1:
    hev_path.write_text(hev_source.replace(legacy_hev, replacements[-1][2], 1))
# Admission must run before telemetry reset, after the control listener is acquired.
# Upgrade the previous cached admission fence in place, if present.
previous_admission = [["supervisor/supervisor.c","#if defined(__linux__) || defined(__ANDROID__)\n","#if defined(__linux__) || defined(__ANDROID__)\n\nextern int nf_gateway_admission_check(int directory);\n"],["supervisor/supervisor.c","    int state_result = system_reset_telemetry_state(\n","    // The global control listener is held. Do not overwrite the old state\n    // or start an owned service if gateway DNS recovery is unresolved.\n    if (system.loaded_config.config.owner == ASTERISKD_OWNER_BOX &&\n        nf_gateway_admission_check(system.store.directory_fd) != 0) {\n        (void)close(listener);\n        *has_early_result = true;\n        (void)system_start_result(early_result, ASTERISKD_CONTROL_RESULT_START_FAILED,\n            \"HEV gateway DNS recovery pending or journal unreadable\", NULL);\n        system_runtime_cleanup(&system);\n        (void)sigprocmask(SIG_SETMASK, &previous, NULL);\n        return 1;\n    }\n\n    int state_result = system_reset_telemetry_state(\n"]]
replacements += [["supervisor/supervisor.c","#if defined(__linux__) || defined(__ANDROID__)\n","#if defined(__linux__) || defined(__ANDROID__)\n\nextern int nf_gateway_admission_check(int directory);\nextern int nf_gateway_startup_reconcile(struct asteriskd_system_supervisor *system);\n"],["supervisor/supervisor.c","    int state_result = system_reset_telemetry_state(\n","    // The global control listener is held. A dirty journal needs the native\n    // action reactor for read-only table inspection before telemetry reset.\n    if (system.loaded_config.config.owner == ASTERISKD_OWNER_BOX &&\n        nf_gateway_admission_check(system.store.directory_fd) != 0) {\n        struct asteriskd_runtime_reactor_backend recovery_backend = system_runtime_reactor_backend();\n        int recovered = -1;\n        if (asteriskd_runtime_create(&system.runtime, &recovery_backend, &system) == 0) {\n            recovered = nf_gateway_startup_reconcile(&system);\n            asteriskd_runtime_destroy(system.runtime);\n            system.runtime = NULL;\n        }\n        if (recovered != 0 || system.shutdown_requested) {\n            (void)close(listener);\n            *has_early_result = true;\n            (void)system_start_result(early_result, ASTERISKD_CONTROL_RESULT_START_FAILED,\n                \"HEV gateway DNS recovery pending or journal unreadable\", NULL);\n            system_runtime_cleanup(&system);\n            (void)sigprocmask(SIG_SETMASK, &previous, NULL);\n            return 1;\n        }\n    }\n\n    int state_result = system_reset_telemetry_state(\n"]]
for prior, current in zip(previous_admission, replacements[-2:]):
    path = root / prior[0]
    source = path.read_text()
    if source.count(current[2]) == 0 and source.count(prior[2]) == 1:
        path.write_text(source.replace(prior[2], current[2], 1))
for name, old, new in replacements:
    path = root / name
    source = path.read_text()
    if source.count(new) == 1:
        continue
    if source.count(old) != 1:
        raise SystemExit("asteriskd DNS patch anchor mismatch: " + name)
    path.write_text(source.replace(old, new, 1))
NF_TETHER_DNS_PATCH

SOURCES=$(cd "$SRC" && find . -name '*.c' -not -path './.git/*' | LC_ALL=C sort)
# Compile the HEV gateway backend with the pinned supervisor. Runtime activation
# remains gated until owned listener, conntrack and crash recovery are wired.
# The backend includes gateway_journal.c: durable checkpoint before firewall mutation.
# Recovery includes the existing backend; compile once, not both units.
# Same-boot pre-core recovery requires the durable spawn-intent checkpoint.
# Post-core recovery persists verified child identity and checks listener absence.
# Active recovery closes the guard and waits for read-only conntrack drain.
# Inventory also checks candidate connmark masks without allocating them.
# Marked rule generation remains opt-in until mask ownership and WAL support are wired.
HEV_GATEWAY_BACKEND="$ROOT/buildScript/experiments/hev-gateway-dns/gateway_recovery.c"

build() { # abi cc-prefix
  local abi=$1 cc=$2
  if [ -n "$ROOTCORE_ABIS" ] && [[ " $ROOTCORE_ABIS " != *" $abi "* ]]; then return; fi
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  ( cd "$SRC" && "$TOOLCHAIN/${cc}${API}-clang" -O2 -flto=full -std=c17 -fPIE -pie -I"$SRC" \
      $SOURCES "$HEV_GATEWAY_BACKEND" "$ROOT/buildScript/experiments/hev-gateway-dns/gateway_socket.c" "$ROOT/buildScript/experiments/hev-gateway-dns/gateway_netlink.c" -ldl -lz -o "$ROOT/app/src/main/jniLibs/$abi/libasteriskd.so" )
}

build arm64-v8a  aarch64-linux-android
build armeabi-v7a armv7a-linux-androideabi
build x86_64     x86_64-linux-android
