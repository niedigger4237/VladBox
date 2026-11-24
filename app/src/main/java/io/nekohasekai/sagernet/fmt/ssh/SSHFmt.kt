package io.nekohasekai.sagernet.fmt.ssh

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

fun buildSingBoxOutboundSSHBean(bean: SSHBean): SingBoxOptions.Outbound_SSHOptions {
    return SingBoxOptions.Outbound_SSHOptions().apply {
        type = "ssh"
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
                private_key = bean.privateKey
                private_key_passphrase = bean.privateKeyPassphrase
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                password = bean.password
            }
        }
    }
}
