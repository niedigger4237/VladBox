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

import io.nekohasekai.sagernet.ktx.toStringPretty
import org.json.JSONArray
import org.json.JSONObject

fun MieruBean.buildMieruConfig(port: Int): String {
    val serverInfo = JSONArray().apply {
        put(JSONObject().apply {
            put("ipAddress", finalAddress)
            put("portBindings", JSONArray().apply {
                put(JSONObject().apply {
                    put("port", finalPort)
                    put("protocol", protocol)
                })
            })
        })
    }
    return JSONObject().apply {
        put("activeProfile", "default")
        put("socks5Port", port)
        // TODO: follow NekoBox logging level.
        put("loggingLevel", "INFO")
        put("profiles", JSONArray().apply {
            put(JSONObject().apply {
                put("profileName", "default")
                put("user", JSONObject().apply {
                    put("name", username)
                    put("password", password)
                })
                put("servers", serverInfo)
                put("mtu", mtu)
            })
        })
    }.toStringPretty()
}

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
