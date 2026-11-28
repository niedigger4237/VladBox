package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.ktx.Logs

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.bg.TunAddressPlan
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildSingBoxOutboundMieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNativeNaiveOutbound
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildNativeTrojanGoOutbound
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val tunAddressPlan: TunAddressPlan? = null,
    val selectorProfileTags: Map<Long, String> = emptyMap(),
    val selectorDependencyIds: Set<Long> = emptySet(),
    val selectorDependencyGroups: Set<Long> = emptySet(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

const val TAG_SOCKS_HELPER = "socks-in"

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false, forSelectorCheck: Boolean = false
): ConfigBuildResult {

    require(proxy.type != ProxyEntity.TYPE_NEKO) { ProxyEntity.LEGACY_NEKO_UNSUPPORTED }
    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = LinkedHashMap<Long, String>()
    val selectorProfileTags = LinkedHashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = ArrayList<String>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    val dependencyIds = linkedSetOf<Long>()
    val dependencyGroups = linkedSetOf<Long>()

    fun ProxyEntity.resolvePhysicalChain(): List<ProxyEntity> =
        ProxyChainOrder.flatten(this, { it.id }, {
            dependencyIds.add(it.id)
            (it.requireBean() as? ChainBean)?.proxies
        }, { ids -> SagerDatabase.proxyDao.getEntities(ids).associateBy { it.id } })

    fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name) || name in setOf(TAG_PROXY, TAG_DIRECT, TAG_BYPASS, TAG_BLOCK) ||
            name.matches(Regex("g-\\d+|c-\\d+-\\d+-\\d+"))) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        dependencyGroups.add(groupId)
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        fun resolveGroupHop(id: Long?, label: String): List<ProxyEntity> {
            if (id == null || id < 0) return emptyList()
            // A configured group proxy can itself be selected as the active profile.
            if (id == this.id) return emptyList()
            val hop = SagerDatabase.proxyDao.getById(id) ?: error("Missing $label proxy: $id")
            return hop.resolvePhysicalChain()
        }
        return ProxyChainOrder.dialOrder(
            resolveGroupHop(thisGroup?.frontProxy, "front"),
            resolvePhysicalChain(),
            resolveGroupHop(thisGroup?.landingProxy, "landing")
        ).toMutableList()
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val isRootTun = !forTest && DataStore.serviceMode == Key.MODE_ROOT_TUN
    val isRootTproxy = !forTest && DataStore.serviceMode == Key.MODE_ROOT_TPROXY
    val isRootSocksHelper = !forTest && (DataStore.serviceMode == Key.MODE_ROOT_TUN2SOCKS ||
            DataStore.serviceMode == Key.MODE_ROOT_BPF2SOCKS)
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val dnsRouting = DnsRoutingPolicy.resolve(DataStore.dnsMain, forTest)
    val remoteDns = (if (dnsRouting.useRemote) DnsPresets.remote() else "").split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DnsPresets.direct().split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    // root TUN: traffic to the FakeIP range (198.18.0.0/15) is not captured by auto_redirect, so no FakeIP there
    val requestedFakeDns = DataStore.enableFakeDns && !forTest && !isRootTun
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode
    // Managed ordinary VPN only. Raw configs, exports and the separate ROOT engines keep their existing semantics.
    val tunPlan = if (isVPN && !forTest && !forExport && !forSelectorCheck && ipv6Mode != IPv6Mode.ONLY) {
        val includeIpv6 = ipv6Mode != IPv6Mode.DISABLE
        when (DataStore.tunAddressMode) {
            "automatic" -> TunAddressPlan.select(includeIpv6)
            "manual" -> TunAddressPlan.manual(DataStore.tunIpv4Subnet, DataStore.tunIpv6Subnet, includeIpv6)
            else -> error("Unknown TUN address mode")
        }
    } else null

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        // root-режимы: ядро во внешнем процессе, статистика и проверка связи идут через Clash API
        val rootMode = DataStore.serviceMode in listOf(
            Key.MODE_ROOT_TUN, Key.MODE_ROOT_TPROXY, Key.MODE_ROOT_TUN2SOCKS, Key.MODE_ROOT_BPF2SOCKS
        )
        if (!forTest && (!forExport || DataStore.enableClashAPI || rootMode)) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                if (!forExport) secret = io.nekohasekai.sagernet.bg.proto.ClashApiSecret.value
                if (DataStore.enableClashAPI) external_ui = "../files/yacd"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        val directDnsStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"))
        val remoteDnsStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"))
        val dnsMainDirect = dnsRouting.finalServer == "dns-direct"
        val modernDns = DnsRuleStrategy.isModern(DataStore.dnsEngineMode)
        val defaultDnsStrategy = DnsRuleStrategy.globalStrategy(
            modernDns,
            if (dnsMainDirect) directDnsStrategy else remoteDnsStrategy,
            autoDnsDomainStrategy(DataStore.dnsGlobalStrategy.takeUnless { it == "auto" }.orEmpty())
        )
        // FakeIP remains IPv4-only. Modern IPv6-only cannot return fake IPv4
        // addresses: use the normal DNS servers rather than a legacy override.
        val useFakeDns = DnsRuleStrategy.fakeDnsEnabled(requestedFakeDns, modernDns, defaultDnsStrategy)
        val fakeDnsPolicy = DnsRuleStrategy.fakeDnsPolicy(defaultDnsStrategy)
        if (requestedFakeDns && !useFakeDns) {
            Logs.i("Modern DNS: IPv4 FakeDNS disabled for IPv6-only strategy")
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isRootTproxy) inbounds.add(Inbound_TProxyOptions().apply {
                type = "tproxy"
                tag = "tproxy-in"
                listen = "::"
                listen_port = DataStore.rootTproxyPort
            })
            if (isVPN || isRootTun) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                if (isRootTun) {
                    // external sing-box running as root creates the device itself
                    interface_name = io.nekohasekai.sagernet.bg.root.RootDaemon.TUN_DEVICE
                    auto_route = true
                    strict_route = false
                    // as in AsteriskBOX: sing-box redirects TCP itself, "lo" keeps local OUTPUT captured
                    auto_redirect = true
                    val rootDaemon = io.nekohasekai.sagernet.bg.root.RootDaemon
                    include_interface = listOf("lo") + rootDaemon.configuredTunInterfaces()
                    val self = android.os.Process.myUid()
                    val selected = if (DataStore.proxyApps) rootDaemon.appUids() else emptyList()
                    if (DataStore.proxyApps && !DataStore.bypass) {
                        // AsteriskBOX keeps root and netd in the whitelist for shared traffic.
                        include_uid = (selected + listOf(0, 1052)).distinct().sorted()
                    }
                    exclude_uid = (listOf(self) + if (DataStore.proxyApps && DataStore.bypass) selected else emptyList()).distinct().sorted()
                    val bypassTags = DataStore.rootTunBypassRuleSets.split(Regex("[,;\\s]+"))
                        .filter { it.isNotBlank() }.distinct()
                    require(bypassTags.all { it.matches(Regex("geoip:[A-Za-z0-9._!-]+")) }) {
                        "ROOT TUN bypass requires GeoIP SRS tags"
                    }
                    if (bypassTags.isNotEmpty()) route_exclude_address_set = bypassTags
                }
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                // sniff / domain_strategy moved to route rule actions (sing-box >= 1.13)
                address = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> listOf(tunPlan?.cidr4 ?: (VpnService.PRIVATE_VLAN4_CLIENT + "/28"))
                    IPv6Mode.ONLY -> listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        tunPlan?.cidr4 ?: (VpnService.PRIVATE_VLAN4_CLIENT + "/28"),
                        tunPlan?.cidr6 ?: (VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    )
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
            })
            if (isRootSocksHelper) inbounds.add(Inbound_SocksOptions().apply {
                // отдельный SOCKS5-вход для tun2socks / bpf2socks
                type = "socks"
                tag = TAG_SOCKS_HELPER
                listen = LOCALHOST
                listen_port = DataStore.socksPort
            })
        }

        outbounds = mutableListOf()
        endpoints = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            // every outbound / endpoint resolves its server address with the direct DNS
            default_domain_resolver = "dns-direct"
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity,
            profileList: List<ProxyEntity> = entity.resolveChain()
        ): String {
            require(profileList.none { it.type == ProxyEntity.TYPE_NEKO }) { ProxyEntity.LEGACY_NEKO_UNSUPPORTED }
            require(profileList.none { (it.requireBean() as? ConfigBean)?.type == 0 }) {
                "Full sing-box configurations cannot be used as individual chain hops"
            }
            profileList.dropLast(1).forEach { hop ->
                if (hop.needExternal()) {
                    val bean = hop.requireBean()
                    require(bean.canMapping() && bean !is HysteriaBean) {
                        "External ${hop.displayType()} cannot be safely routed through a preceding proxy"
                    }
                }
            }
            ProxyChainTransportPolicy.validate(profileList.asReversed().map { hop ->
                describeChainTransport(hop)
            })
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val sourceBean = proxyEntity.requireBean()
                // Each external instance owns its mapping address; a repeated profile must
                // never overwrite the runtime endpoint of another instance.
                val runtimeProfile = if (proxyEntity.needExternal())
                    proxyEntity.copy().putBean(sourceBean.clone()) else proxyEntity
                val bean = runtimeProfile.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-$index-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // selector human readable name
                if (buildSelector && index == 0) {
                    tagOut = selectorName(entity.displayName())
                }


                // A shared first hop may already have a different tag (for example
                // "proxy" or a selector name). Link to the actual existing tag.
                val reusedTag = if (needGlobal) globalOutbounds[proxyEntity.id] else null
                tagOut = ServerResolverPolicy.effectiveHopTag(tagOut, reusedTag)

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    if (reusedTag != null) return@forEachIndexed
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = runtimeProfile
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config)

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TrojanGoBean ->
                            buildNativeTrojanGoOutbound(bean)

                        is MieruBean ->
                            buildSingBoxOutboundMieruBean(bean)

                        is NaiveBean -> {
                            check(forExport || BuildConfig.NATIVE_NAIVE) {
                                "NaiveProxy requires a Cronet-enabled build of NieFigger Hub"
                            }
                            buildNativeNaiveOutbound(bean)
                        }

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is WireGuardBean -> // WireGuard / AmneziaWG: endpoint (1.13+)
                            buildSingBoxEndpointWireguardBean(bean, fastHandshakeRetry = !forExport)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT && bean !is NaiveBean &&
                            (bean !is SOCKSBean || bean.protocol == SOCKSBean.PROTOCOL_SOCKS5)) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    // sing-box >= 1.12: domain_strategy -> domain_resolver
                    val serverStrategy = if (forTest) "" else defaultServerDomainStrategy
                    // Keep explicit resolver options supplied by a managed protocol
                    // builder (notably WireGuard's advanced dialer) or custom outbound.
                    // URL tests retain their existing direct resolver policy.
                    val customResolver = if (this is CustomSingBoxOption)
                        getBasicMap()["domain_resolver"] else null
                    val explicitResolver = _hack_config_map["domain_resolver"] ?: customResolver
                    _hack_config_map["domain_resolver"] =
                        ServerResolverPolicy.options(forTest, serverStrategy, explicitResolver)

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> "hysteria-plugin" // Only Hysteria 1 has an external fallback.
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-$index-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                if (bean is WireGuardBean && !proxyEntity.needExternal()) {
                    endpoints.add(currentOutbound)
                } else {
                    outbounds.add(currentOutbound)
                }
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                val hops = it.resolveChain()
                if (hops.any { hop -> hop.type == ProxyEntity.TYPE_NEKO }) {
                    require(it.id != proxy.id) { ProxyEntity.LEGACY_NEKO_UNSUPPORTED }
                    Logs.w("Skipping unsupported legacy Neko selector profile ${it.id}")
                } else {
                    tagMap[it.id] = buildChain(it.id, it, hops)
                }
            }
            selectorProfileTags.putAll(tagMap)
            val selectedTag = requireNotNull(selectorProfileTags[proxy.id]) {
                "Selected profile is missing from its selector group"
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = selectedTag
                outbounds = selectorProfileTags.values.distinct()
            })
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            if (!tagMap.containsKey(key)) tagMap[key] = buildChain(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
            }
            val uidList = rule.packages.map {
                if (!isVPN) {
                    Toast.makeText(
                        SagerNet.application,
                        SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()
            val ruleSets = mutableListOf<RuleSet>()

            val ruleObj = Rule_DefaultOptions().apply {
                if (uidList.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    user_id = uidList
                }
                var domainList: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList = rule.domains.listByLineOrComma()
                    makeSingBoxRule(domainList, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                }

                if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = listOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    source_ip_cidr = rule.source.listByLineOrComma()
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma()
                }

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (uidList.isNotEmpty()) user_id = uidList
                        domainList?.let { makeSingBoxRule(it) }
                    }
                }

                when (rule.outbound) {
                    -1L -> {
                        userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-direct"
                            strategy = DnsRuleStrategy.ruleOverride(modernDns, directDnsStrategy, defaultDnsStrategy)
                        }
                    }

                    0L -> {
                        if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-fake"
                            strategy = DnsRuleStrategy.ruleOverride(modernDns, fakeDnsPolicy.ruleOverride, defaultDnsStrategy)
                            query_type = DnsRuleStrategy.fakeQueryTypes(modernDns)
                            inbound = listOf("tun-in")
                        }
                        userDNSRuleList += makeDnsRuleObj().apply {
                            server = dnsRouting.proxyRuleServer
                            // Direct Only resolves even proxied domains via Direct DNS.
                            val preferred = if (dnsRouting.useRemote) remoteDnsStrategy else directDnsStrategy
                            strategy = DnsRuleStrategy.ruleOverride(modernDns, preferred, defaultDnsStrategy)
                        }
                    }

                    -2L -> {
                        userDNSRuleList += makeDnsRuleObj().apply {
                            // rcode://success -> predefined action
                            action = "predefined"
                            rcode = "NOERROR"
                        }
                    }
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) TAG_PROXY else tagMap[outId] ?: ""
                }

                _hack_custom_config = rule.config
            }

            if (!ruleObj.checkEmpty()) {
                if (ruleObj.outbound.isNullOrBlank()) {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // block 改用新的写法
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    route.rules.add(ruleObj)
                    route.rule_set.addAll(ruleSets)
                }
            }
        }

        // ROOT TUN kernel bypass must register its SRS even when no ordinary route rule uses it.
        if (isRootTun) {
            val tags = inbounds.filterIsInstance<Inbound_TunOptions>().flatMap { it.route_exclude_address_set.orEmpty() }
            if (tags.isNotEmpty()) generateRuleSet(tags.distinct(), route.rule_set)
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        // WireGuard/AmneziaWG are endpoints, not outbounds: without an explicit final the first
        // outbound (direct) would carry all unmatched traffic
        route.final_ = if (!forTest && DataStore.routeFinal == "direct") TAG_DIRECT else TAG_PROXY

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        // ---- DNS servers: sing-box >= 1.12 format (legacy format removed in 1.14) ----
        dns.servers.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
        })

        val bootstrap = BootstrapDnsPolicy.select(
            DataStore.dnsBootstrapPreset, DataStore.dnsBootstrapAddress, DataStore.dnsBootstrapTlsName
        )
        if (bootstrap != null) {
            dns.servers.add(DNSServerOptions().apply {
                tag = "dns-bootstrap"
                type = bootstrap.type
                server = bootstrap.host
                server_port = bootstrap.port
                path = bootstrap.path
                if (bootstrap.tlsName != null) {
                    _hack_config_map["tls"] = mapOf("enabled" to true, "server_name" to bootstrap.tlsName)
                }
                // IP literal, no domain_resolver and no proxy detour.
            })
        }
        directDNS.firstOrNull().let {
            dns.servers.add(
                buildDnsServer(
                    it ?: throw Exception("No direct DNS, check your settings!"),
                    "dns-direct",
                    if (bootstrap == null) "dns-local" else "dns-bootstrap"
                )
            )
        }

        remoteDns.firstOrNull().let {
            // Direct Only and URL tests need no remote DNS transport.
            if (dnsRouting.useRemote) dns.servers.add(
                buildDnsServer(
                    it ?: throw Exception("No remote DNS, check your settings!"),
                    "dns-remote",
                    "dns-direct"
                ).apply {
                    // legacy servers were routed through the default outbound;
                    // new-format servers connect directly unless a detour is set
                    detour = TAG_PROXY
                }
            )
        }

        dns.final_ = dnsRouting.finalServer
        // Keep the global DNS strategy; matching rules need no duplicate override.
        dns.strategy = defaultDnsStrategy
        dns.optimistic = DnsOptimisticPolicy.options(
            modernDns, forTest, DataStore.dnsOptimisticWindow
        )
        val dnsTuning = DnsClientTuningPolicy.options(
            modernDns, forTest, DataStore.dnsQueryTimeout, DataStore.dnsCacheCapacity
        )
        dns.timeout = dnsTuning.timeout
        dns.cache_capacity = dnsTuning.cacheCapacity

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in route rules. sing-box >= 1.13: sniff / resolve / hijack-dns
            // are rule actions instead of inbound fields.
            val entryInbounds = mutableListOf<String>().apply {
                if (isVPN || isRootTun) add("tun-in")
                if (isRootTproxy) add("tproxy-in")
                add(TAG_MIXED)
                if (isRootSocksHelper) add(TAG_SOCKS_HELPER)
            }
            val headRules = mutableListOf<Rule_DefaultOptions>()
            val hijack = DnsHijackPolicy.plan(DataStore.hijackDns, needSniff)
            if (hijack.sniff != DnsHijackPolicy.Sniff.NONE) {
                // Protocol-based DNS capture requires sniffing even if general
                // Traffic Sniffing is disabled. Limit that case to DNS only.
                headRules += Rule_DefaultOptions().apply {
                    inbound = entryInbounds
                    action = "sniff"
                    if (hijack.sniff == DnsHijackPolicy.Sniff.DNS_ONLY) sniffer = listOf("dns")
                }
            }
            if (hijack.protocol) headRules += Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            }
            if (hijack.port) headRules += Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            }
            if (DataStore.resolveDestination) {
                headRules += Rule_DefaultOptions().apply {
                    inbound = entryInbounds
                    action = "resolve"
                    strategy = genDomainStrategy(true)
                }
            }
            route.rules.addAll(0, headRules)

            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    inet6_range = fakeDnsPolicy.ipv6Range
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    strategy = DnsRuleStrategy.ruleOverride(modernDns, fakeDnsPolicy.ruleOverride, defaultDnsStrategy)
                    query_type = DnsRuleStrategy.fakeQueryTypes(modernDns)
                    disable_cache = true
                })
            }
            // force bypass (always top DNS rule)
            // (the old "outbound: any -> dns-direct" loop guard is replaced by
            //  per-outbound domain_resolver + route.default_domain_resolver)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                    strategy = DnsRuleStrategy.ruleOverride(modernDns, directDnsStrategy, defaultDnsStrategy)
                })
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        val json = gson.toJson(configMap)
        // Validate the merged managed config, including global and per-profile JSON.
        // Raw full-core imports return earlier and remain entirely user-owned.
        if (DnsRuleStrategy.isModern(DataStore.dnsEngineMode)) {
            ModernDnsValidation.check(com.google.gson.JsonParser.parseString(json).asJsonObject)
        }
        if (tunPlan != null) {
            // A custom JSON override must not silently leave Android and the core on different subnets.
            val expected = if (ipv6Mode == IPv6Mode.DISABLE) listOf(tunPlan.cidr4)
                else listOf(tunPlan.cidr4, tunPlan.cidr6)
            val tun = com.google.gson.JsonParser.parseString(json).asJsonObject.getAsJsonArray("inbounds")
                ?.firstOrNull { it.isJsonObject && it.asJsonObject.get("tag")?.asString == "tun-in" }
                ?.asJsonObject
            val actual = tun?.get("address")
            require(actual?.isJsonArray == true && actual.asJsonArray.map { it.asString } == expected) {
                "Custom TUN address override conflicts with the managed VPN address plan; remove the override or use a raw config"
            }
        }
        ConfigBuildResult(
            json,
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            tunPlan,
            selectorProfileTags.toMap(),
            if (buildSelector) dependencyIds.toSet() else emptySet(),
            if (buildSelector) dependencyGroups.toSet() else emptySet()
        )
    }

}
