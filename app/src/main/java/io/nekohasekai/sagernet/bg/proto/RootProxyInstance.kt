package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.root.RootCore
import io.nekohasekai.sagernet.bg.root.RootRules
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File

/**
 * Runs the profile in an external sing-box executed as root instead of the in-process core.
 *  - Key.MODE_ROOT_TUN:    sing-box creates the TUN device itself (tun inbound, auto_route)
 *  - Key.MODE_ROOT_TPROXY: tproxy inbound + iptables TPROXY rules (RootRules)
 */
class RootProxyInstance(profile: ProxyEntity, service: BaseService.Interface?) :
    ProxyInstance(profile, service) {

    private val runDir get() = "${RootCore.BASE}/run"
    private val pidFile get() = "$runDir/sing-box.pid"
    private var tproxyApplied = false

    override suspend fun loadConfig() {
        // no in-process core: `box` stays uninitialised
    }

    override fun startCore() {
        if (!RootShell.isAvailable()) throw IllegalStateException("Root access was denied")

        val core = RootCore.resolve() ?: throw IllegalStateException("No sing-box core for root mode (bundled core missing, import one in settings)")
        Logs.i("root core: ${core.label} (${core.path})")

        // config readable by root only
        val cfg = File(SagerNet.application.noBackupFilesDir, "root-config.json")
        cfg.writeText(config.config)
        val rcfg = "$runDir/config.json"
        val prep = RootShell.run(
            "mkdir -p ${RootShell.q(runDir)}",
            "cp ${RootShell.q(cfg)} ${RootShell.q(rcfg)} && chmod 600 ${RootShell.q(rcfg)}",
        )
        cfg.delete()
        if (!prep.ok) throw IllegalStateException(prep.err.ifBlank { "cannot prepare $runDir" })

        RootCore.check(core.path, File(rcfg))?.let { throw IllegalStateException("sing-box check: $it") }

        // stop a stale instance of ours, then start detached
        stopProcess()
        val log = "$runDir/sing-box.log"
        val start = RootShell.run(
            "cd ${RootShell.q(runDir)}",
            "nohup ${RootShell.q(core.path)} run -c ${RootShell.q(rcfg)} -D ${RootShell.q(runDir)} >${RootShell.q(log)} 2>&1 &",
            "echo \$! > ${RootShell.q(pidFile)}",
        )
        if (!start.ok) throw IllegalStateException(start.err.ifBlank { "failed to start sing-box" })

        // wait for it to stay alive for a moment
        Thread.sleep(1200)
        if (!isAlive()) {
            val tail = RootShell.run("tail -n 20 ${RootShell.q(log)}").out
            throw IllegalStateException("sing-box exited: $tail")
        }

        if (DataStore.serviceMode == Key.MODE_ROOT_TPROXY) {
            val err = RootRules.apply(
                DataStore.rootTproxyPort,
                DataStore.ipv6Mode != io.nekohasekai.sagernet.IPv6Mode.DISABLE,
                emptyList()
            )
            if (err != null) {
                stopProcess()
                throw IllegalStateException(err)
            }
            tproxyApplied = true
        }
    }

    private fun isAlive(): Boolean =
        RootShell.run("p=\$(cat ${RootShell.q(pidFile)} 2>/dev/null); [ -n \"\$p\" ] && kill -0 \$p").ok

    private fun stopProcess() {
        RootShell.run(
            "p=\$(cat ${RootShell.q(pidFile)} 2>/dev/null)",
            "if [ -n \"\$p\" ]; then kill \$p; i=0; while kill -0 \$p 2>/dev/null && [ \$i -lt 30 ]; do sleep 0.1; i=\$((i+1)); done; kill -9 \$p 2>/dev/null; fi",
            "rm -f ${RootShell.q(pidFile)}",
        )
    }

    override fun close() {
        // the TPROXY rules must go first or traffic is black-holed
        if (tproxyApplied) {
            RootRules.clear()
            tproxyApplied = false
        }
        stopProcess()
        super.close()
    }
}
