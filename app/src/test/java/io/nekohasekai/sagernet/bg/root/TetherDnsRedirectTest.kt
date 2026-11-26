package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.*
import org.junit.Test

class TetherDnsRedirectTest {
    private val chain = "NFV_D_test"
    private fun rule(iface: String, proto: String, dest: String = "172.19.0.2:53",
                     match: Boolean = true) =
        "-A $chain -i $iface -p $proto ${if (match) "-m $proto " else ""}--dport 53 -j DNAT --to-destination $dest"
    private val normal = listOf(rule("ap0", "udp"), rule("ap0", "tcp"))
    private fun valid(rows: List<String>, names: Set<String> = setOf("ap0"),
                      dns: String = "172.19.0.2") =
        TetherSnapshotChecks.hasDnsRedirects(rows, chain, names, dns)

    @Test fun exactDnsRedirectRules() {
        assertTrue(valid(normal))
        assertTrue(valid(listOf(rule("ap0", "udp", match = false), normal[1])))
        assertTrue(valid(normal + "-A PREROUTING -j $chain"))
        assertFalse(valid(normal.take(1)))
        assertFalse(valid(normal + normal[0]))
        assertFalse(valid(normal + rule("usb0", "udp")))
        assertFalse(valid(normal.map { it.replace("172.19.0.2", "172.19.0.3") }))
        assertFalse(valid(normal.map { it.replace("--dport 53", "--dport 853") }))
        assertFalse(valid(normal.map { it.replace(":53", ":54") }))
        assertFalse(valid(normal + "-A $chain -j RETURN"))
        assertTrue(valid(normal + listOf(rule("usb0", "udp"), rule("usb0", "tcp")), setOf("ap0", "usb0")))
        assertTrue(valid(normal.map { it.replace("172.19.0.2:53", "[fdfe:dcba:9876:0:0:0:0:2]:53") }, dns = "fdfe:dcba:9876::2"))
        assertFalse(valid(normal.map { it.replace("172.19.0.2:53", "[fdfe:dcba:9876::3]:53") }, dns = "fdfe:dcba:9876::2"))
        assertFalse(valid(normal.map { it.replace("-j DNAT", "-j REDIRECT") }))
        assertFalse(valid(normal.map { it.replace("-p udp", "-p udp -s 192.168.1.2") }))
        assertFalse(valid(normal, dns = "bad"))
        assertFalse(valid(normal, setOf("ap0", "usb0")))
        assertTrue(valid(emptyList(), emptySet()))
    }
}
