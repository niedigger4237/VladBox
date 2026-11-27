/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Native Mieru is registered by the pinned extended core without build tags.
 * Preserve the existing bean and its serialized format. */
fun buildSingBoxOutboundMieruBean(bean: MieruBean): moe.matsuri.nb4a.SingBoxOptions.Outbound {
    return moe.matsuri.nb4a.SingBoxOptions.Outbound().apply {
        require(bean.serverAddress.isNotBlank() && bean.serverPort in 1..65535) { "Invalid Mieru server" }
        require(bean.protocol in listOf("TCP", "UDP")) { "Invalid Mieru transport" }
        require(bean.username.isNotEmpty() && bean.password.isNotEmpty()) { "Mieru requires username and password" }
        type = "mieru"
        _hack_config_map["server"] = bean.serverAddress
        _hack_config_map["server_port"] = bean.serverPort
        _hack_config_map["transport"] = bean.protocol
        _hack_config_map["username"] = bean.username
        _hack_config_map["password"] = bean.password
        require(bean.mtu > 0) { "Invalid Mieru MTU" }
        _hack_config_map["mtu"] = bean.mtu
    }
}

/** Human-readable mierus:// format. Reject features not representable by MieruBean. */
fun parseMieruSimple(link: String): MieruBean {
    require(link.startsWith("mierus://")) { "Unsupported Mieru link format" }
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid Mieru link")
    val ports = url.queryParameterValues("port")
    val transports = url.queryParameterValues("protocol")
    require(ports.size == 1 && transports.size == 1) {
        "Mieru profiles currently support one port binding"
    }
    val port = ports.single()?.toIntOrNull()
    require(port != null && port in 1..65535) { "Unsupported Mieru port or range" }
    val transport = transports.single()
    require(transport in listOf("TCP", "UDP")) { "Invalid Mieru transport" }
    require(url.username.isNotEmpty() && url.password.isNotEmpty()) { "Mieru requires username and password" }
    require(url.queryParameterNames.all { it in setOf("profile", "port", "protocol", "mtu") }) {
        "Mieru link contains unsupported advanced settings"
    }
    val profileName = url.queryParameter("profile").orEmpty()
    require(profileName.isNotBlank()) { "Missing Mieru profile name" }
    val mtu = url.queryParameter("mtu")?.let { it.toIntOrNull() ?: error("Invalid Mieru MTU") } ?: 1400
    require(mtu > 0 && (transport == "UDP" || mtu == 1400)) { "Unsupported Mieru MTU" }
    return MieruBean().apply {
        serverAddress = url.host
        serverPort = port
        protocol = transport!!
        username = url.username
        password = url.password
        name = profileName
        this.mtu = mtu
        initializeDefaultValues()
    }
}

fun MieruBean.toUri(): String = linkBuilder()
    .host(serverAddress)
    .username(username)
    .password(password)
    .addQueryParameter("profile", name.ifBlank { displayName() })
    .addQueryParameter("port", serverPort.toString())
    .addQueryParameter("protocol", protocol)
    .apply { if (protocol == "UDP") addQueryParameter("mtu", mtu.toString()) }
    .toLink("mierus", false)
