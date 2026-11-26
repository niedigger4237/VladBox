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
NIEFIGER_ASTERISKD_PATCH
if git -C "$SRC" apply --reverse --check "$ASTERISKD_PATCH" >/dev/null 2>&1; then
  echo "asteriskd tether staging patch already applied"
else
  git -C "$SRC" apply --check "$ASTERISKD_PATCH"
  git -C "$SRC" apply "$ASTERISKD_PATCH"
fi

SOURCES=$(cd "$SRC" && find . -name '*.c' -not -path './.git/*' | LC_ALL=C sort)

build() { # abi cc-prefix
  local abi=$1 cc=$2
  if [ -n "$ROOTCORE_ABIS" ] && [[ " $ROOTCORE_ABIS " != *" $abi "* ]]; then return; fi
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  ( cd "$SRC" && "$TOOLCHAIN/${cc}${API}-clang" -O2 -flto=full -std=c17 -fPIE -pie -I"$SRC" \
      $SOURCES -ldl -lz -o "$ROOT/app/src/main/jniLibs/$abi/libasteriskd.so" )
}

build arm64-v8a  aarch64-linux-android
build armeabi-v7a armv7a-linux-androideabi
build x86_64     x86_64-linux-android
