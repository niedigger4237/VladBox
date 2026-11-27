import io.nekohasekai.sagernet.ktx.LogRedactor

// Run from the repository root:
// kotlinc app/src/main/java/io/nekohasekai/sagernet/ktx/LogRedactor.kt buildScript/experiments/log-redaction/Main.kt -include-runtime -d /tmp/log-redaction.jar
// java -jar /tmp/log-redaction.jar
fun main() {
    val marker = "dummy7"
    var count = 0
    fun verify(input: String) {
        check(marker !in LogRedactor.text(input)) { "Credential redaction failed in case $count" }
        count++
    }
    val schemes = listOf("anytls", "mieru", "shadowtls", "trojan-go", "socks", "socks4", "socks4a", "socks5h", "naive+quic", "amneziawg", "vless", "vmess", "hysteria2", "https")
    for (scheme in schemes) {
        verify("import $scheme://$marker@example.invalid:443")
        verify("import $scheme:\\/\\/$marker@example.invalid:443")
    }
    for (header in listOf("Authorization: Bearer", "Authorization: Basic", "Bearer", "password:", "password: \"", "password: \\" + "\"")) {
        verify("$header\n\n$marker\nconnected")
        check(LogRedactor.text("$header\n\n$marker\nconnected").endsWith("connected"))
    }
    verify("-----BEGIN OPENSSH PRIVATE KEY-----\n$marker\n-----END OPENSSH PRIVATE KEY-----")
    verify("password=$marker")
    check(LogRedactor.text("ROOT health: running") == "ROOT health: running")
    println("Passed $count redaction cases, plus preservation checks")
}
