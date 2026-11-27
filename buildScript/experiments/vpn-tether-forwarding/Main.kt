import io.nekohasekai.sagernet.bg.root.TetherSnapshotChecks

// Compile together with app/src/main/java/io/nekohasekai/sagernet/bg/root/TetherSnapshotChecks.kt.
fun main() {
    var cases = 0
    fun verify(lines: List<String>, expected: Boolean, subnet: String = "192.168.50.0/24") {
        check(TetherSnapshotChecks.hasForwardingAndNat(lines, "F", "N", "tun0", listOf("ap0" to subnet)) == expected) { "case $cases" }
        cases++
    }
    val rules = listOf(
        "-A F -s 192.168.50.0/24 -i ap0 -o tun0 -j ACCEPT",
        "-A F -d 192.168.50.0/24 -i tun0 -o ap0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT",
        "-A N -s 192.168.50.0/24 -o tun0 -j MASQUERADE",
    )
    verify(rules, true)
    for (i in rules.indices) verify(rules.filterIndexed { index, _ -> index != i }, false)
    verify(rules + rules[0], false)
    verify(rules + "-A F -j ACCEPT", false)
    verify(rules + "-A OTHER -j ACCEPT", true)
    verify(rules.map { it.replace("tun0", "wlan0") }, false)
    verify(rules.map { it.replace("MASQUERADE", "RETURN") }, false)
    verify(rules.map { it.replace("RELATED,ESTABLISHED", "NEW,ESTABLISHED") }, false)
    verify(rules.map { it.replace("192.168.50.0/24", "fd12:3456::/64") }, true, "fd12:3456:0:0:0:0:0:0/64")
    println("Passed $cases cases")
}
