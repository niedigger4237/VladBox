package io.nekohasekai.sagernet.fmt.ssh

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

fun buildSingBoxOutboundSSHBean(bean: SSHBean): SingBoxOptions.Outbound_SSHOptions {
    return SingBoxOptions.Outbound_SSHOptions().apply {
        require(bean.serverAddress.isNotBlank() && bean.serverPort in 1..65535) { "Invalid SSH server address or port" }
        require(bean.authType in 0..2) { "Invalid SSH authentication type" }
        require(bean.clientVersion.isBlank() || (bean.clientVersion.startsWith("SSH-2.0-") && !bean.clientVersion.contains('\n') && !bean.clientVersion.contains('\r'))) { "Invalid SSH client version" }
        type = "ssh"
        io.nekohasekai.sagernet.fmt.v2ray.applyClientDialer(bean.outboundDialer, "", _hack_config_map)
        server = bean.serverAddress
        server_port = bean.serverPort
        user = bean.username
        bean.hostKeyAlgorithms.takeIf { it.isNotBlank() }?.let { _hack_config_map["host_key_algorithms"] = it.listByLineOrComma() }
        bean.clientVersion.takeIf { it.isNotBlank() }?.let { _hack_config_map["client_version"] = it }
        bean.cipherAlgorithms.takeIf { it.isNotBlank() }?.let { _hack_config_map["cipher"] = it.listByLineOrComma() }
        bean.macAlgorithms.takeIf { it.isNotBlank() }?.let { _hack_config_map["mac"] = it.listByLineOrComma() }
        bean.kexAlgorithms.takeIf { it.isNotBlank() }?.let { _hack_config_map["kex_algorithm"] = it.listByLineOrComma() }
        if (bean.publicKey.isNotBlank()) {
            host_key = bean.publicKey.listByLineOrComma()
        }
        when (bean.authType) {
            SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
                require(bean.privateKey.isNotBlank() xor bean.privateKeyPath.isNotBlank()) { "Choose private key text or path" }
                if (bean.privateKeyPath.isNotBlank()) _hack_config_map["private_key_path"] = bean.privateKeyPath
                else private_key = bean.privateKey
                private_key_passphrase = bean.privateKeyPassphrase
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                require(bean.password.isNotEmpty()) { "SSH password is required" }
                password = bean.password
            }
        }
    }
}
