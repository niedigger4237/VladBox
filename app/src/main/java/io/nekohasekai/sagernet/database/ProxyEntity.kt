package io.nekohasekai.sagernet.database

import android.content.Context
import android.content.Intent
import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.toUri
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.naive.toUri
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.trojan_go.toUri
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.toUri
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.profile.*
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundBrutalOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.neko.*
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity

@Entity(
    tableName = "proxy_entities", indices = [Index("groupId", name = "groupId")]
)
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var type: Int = 0,
    var userOrder: Long = 0L,
    var tx: Long = 0L,
    var rx: Long = 0L,
    var status: Int = 0,
    var ping: Int = 0,
    var uuid: String = "",
    var error: String? = null,
    var socksBean: SOCKSBean? = null,
    var httpBean: HttpBean? = null,
    var ssBean: ShadowsocksBean? = null,
    var vmessBean: VMessBean? = null,
    var trojanBean: TrojanBean? = null,
    var trojanGoBean: TrojanGoBean? = null,
    var mieruBean: MieruBean? = null,
    var naiveBean: NaiveBean? = null,
    var hysteriaBean: HysteriaBean? = null,
    var tuicBean: TuicBean? = null,
    var sshBean: SSHBean? = null,
    var wgBean: WireGuardBean? = null,
    var shadowTLSBean: ShadowTLSBean? = null,
    var anyTLSBean: AnyTLSBean? = null,
    var chainBean: ChainBean? = null,
    var nekoBean: NekoBean? = null,
    var configBean: ConfigBean? = null,
) : Serializable() {

    companion object {
        const val TYPE_SOCKS = 0
        const val TYPE_HTTP = 1
        const val TYPE_SS = 2
        const val TYPE_VMESS = 4
        const val TYPE_TROJAN = 6

        const val TYPE_SSH = 17
        const val TYPE_WG = 18

        const val TYPE_TROJAN_GO = 7
        const val TYPE_NAIVE = 9
        const val TYPE_HYSTERIA = 15
        const val TYPE_SHADOWTLS = 19
        const val TYPE_TUIC = 20
        const val TYPE_MIERU = 21
        const val TYPE_ANYTLS = 22

        const val TYPE_CONFIG = 998
        const val TYPE_NEKO = 999

        const val TYPE_CHAIN = 8

        val chainName by lazy { app.getString(R.string.proxy_chain) }

        @JvmField
        val CREATOR = object : CREATOR<ProxyEntity>() {

            override fun newInstance(): ProxyEntity {
                return ProxyEntity()
            }

            override fun newArray(size: Int): Array<ProxyEntity?> {
                return arrayOfNulls(size)
            }
        }
    }

    @Ignore
    @Transient
    var dirty: Boolean = false

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(0)

        output.writeLong(id)
        output.writeLong(groupId)
        output.writeInt(type)
        output.writeLong(userOrder)
        output.writeLong(tx)
        output.writeLong(rx)
        output.writeInt(status)
        output.writeInt(ping)
        output.writeString(uuid)
        output.writeString(error)

        val data = KryoConverters.serialize(requireBean())
        output.writeVarInt(data.size, true)
        output.writeBytes(data)

        output.writeBoolean(dirty)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()

        id = input.readLong()
        groupId = input.readLong()
        type = input.readInt()
        userOrder = input.readLong()
        tx = input.readLong()
        rx = input.readLong()
        status = input.readInt()
        ping = input.readInt()
        uuid = input.readString()
        error = input.readString()
        putByteArray(input.readBytes(input.readVarInt(true)))

        dirty = input.readBoolean()
    }


    fun putByteArray(byteArray: ByteArray) {
        when (type) {
            TYPE_SOCKS -> socksBean = KryoConverters.socksDeserialize(byteArray)
            TYPE_HTTP -> httpBean = KryoConverters.httpDeserialize(byteArray)
            TYPE_SS -> ssBean = KryoConverters.shadowsocksDeserialize(byteArray)
            TYPE_VMESS -> vmessBean = KryoConverters.vmessDeserialize(byteArray)
            TYPE_TROJAN -> trojanBean = KryoConverters.trojanDeserialize(byteArray)
            TYPE_TROJAN_GO -> trojanGoBean = KryoConverters.trojanGoDeserialize(byteArray)
            TYPE_MIERU -> mieruBean = KryoConverters.mieruDeserialize(byteArray)
            TYPE_NAIVE -> naiveBean = KryoConverters.naiveDeserialize(byteArray)
            TYPE_HYSTERIA -> hysteriaBean = KryoConverters.hysteriaDeserialize(byteArray)
            TYPE_SSH -> sshBean = KryoConverters.sshDeserialize(byteArray)
            TYPE_WG -> wgBean = KryoConverters.wireguardDeserialize(byteArray)
            TYPE_TUIC -> tuicBean = KryoConverters.tuicDeserialize(byteArray)
            TYPE_SHADOWTLS -> shadowTLSBean = KryoConverters.shadowTLSDeserialize(byteArray)
            TYPE_ANYTLS -> anyTLSBean = KryoConverters.anyTLSDeserialize(byteArray)
            TYPE_CHAIN -> chainBean = KryoConverters.chainDeserialize(byteArray)
            TYPE_NEKO -> nekoBean = KryoConverters.nekoDeserialize(byteArray)
            TYPE_CONFIG -> configBean = KryoConverters.configDeserialize(byteArray)
        }
    }

    /** Presentation only: never modify the stored protocol or security configuration. */
    data class ProtocolDetails(val protocol: String, val transport: String? = null, val security: String? = null) {
        fun label(): String {
            val detail = listOfNotNull(transport?.takeUnless { it == "tcp" && security != null }, security)
            return if (detail.isEmpty()) protocol else protocol + " / " + detail.joinToString("+")
        }
    }

    private fun v2rayDetails(protocol: String, bean: StandardV2RayBean): ProtocolDetails {
        val transport = when (val value = bean.type?.trim()?.lowercase().orEmpty()) {
            "", "raw" -> "tcp"
            "h2" -> "http"
            "mkcp" -> "kcp"
            else -> value
        }
        val security = when (val value = bean.security?.trim()?.lowercase().orEmpty()) {
            "tls", "reality" -> if (!bean.realityPubKey.isNullOrBlank() || value == "reality") "reality" else "tls"
            "", "none" -> null
            else -> value
        }
        return ProtocolDetails(protocol, transport, security)
    }

    fun protocolDetails(): ProtocolDetails = when (type) {
        TYPE_SOCKS -> ProtocolDetails(socksBean!!.protocolName())
        TYPE_HTTP -> if (httpBean!!.isTLS()) ProtocolDetails("HTTPS") else ProtocolDetails("HTTP")
        TYPE_SS -> ProtocolDetails("Shadowsocks")
        TYPE_VMESS -> v2rayDetails(if (vmessBean!!.isVLESS) "VLESS" else "VMess", vmessBean!!)
        TYPE_TROJAN -> v2rayDetails("Trojan", trojanBean!!)
        TYPE_TROJAN_GO -> ProtocolDetails("Trojan-Go", trojanGoBean!!.type?.takeIf { it.isNotBlank() && it != "original" }, "tls")
        TYPE_MIERU -> ProtocolDetails("Mieru", mieruBean!!.protocol?.lowercase())
        TYPE_NAIVE -> ProtocolDetails("Naïve", if (naiveBean!!.proto == "quic") "quic" else "http", "tls")
        TYPE_HYSTERIA -> ProtocolDetails("Hysteria" + hysteriaBean!!.protocolVersion, "quic", "tls")
        TYPE_SSH -> ProtocolDetails("SSH")
        TYPE_WG -> ProtocolDetails(if (wgBean!!.isAmnezia) "AmneziaWG" else "WireGuard")
        TYPE_TUIC -> ProtocolDetails("TUIC", "quic", "tls")
        TYPE_SHADOWTLS -> ProtocolDetails("ShadowTLS")
        TYPE_ANYTLS -> ProtocolDetails("AnyTLS", security = "tls")
        TYPE_CHAIN -> ProtocolDetails(chainName)
        TYPE_NEKO -> ProtocolDetails(nekoBean!!.displayType())
        TYPE_CONFIG -> ProtocolDetails(configBean!!.displayType())
        else -> ProtocolDetails("Undefined type $type")
    }

    fun displayType(): String = protocolDetails().label()

    fun displayName() = requireBean().displayName()
    fun displayAddress() = requireBean().displayAddress()

    fun requireBean(): AbstractBean {
        return when (type) {
            TYPE_SOCKS -> socksBean
            TYPE_HTTP -> httpBean
            TYPE_SS -> ssBean
            TYPE_VMESS -> vmessBean
            TYPE_TROJAN -> trojanBean
            TYPE_TROJAN_GO -> trojanGoBean
            TYPE_MIERU -> mieruBean
            TYPE_NAIVE -> naiveBean
            TYPE_HYSTERIA -> hysteriaBean
            TYPE_SSH -> sshBean
            TYPE_WG -> wgBean
            TYPE_TUIC -> tuicBean
            TYPE_SHADOWTLS -> shadowTLSBean
            TYPE_ANYTLS -> anyTLSBean
            TYPE_CHAIN -> chainBean
            TYPE_NEKO -> nekoBean
            TYPE_CONFIG -> configBean
            else -> error("Undefined type $type")
        } ?: error("Null ${displayType()} profile")
    }

    fun haveLink(): Boolean {
        return when (type) {
            TYPE_CHAIN -> false
            else -> true
        }
    }

    fun haveStandardLink(): Boolean {
        return when (requireBean()) {
            is SSHBean -> false
            is WireGuardBean -> false
            is ShadowTLSBean -> false
            is NekoBean -> false
            is ConfigBean -> false
            else -> true
        }
    }

    fun toStdLink(compact: Boolean = false): String = with(requireBean()) {
        when (this) {
            is SOCKSBean -> toUri()
            is HttpBean -> toUri()
            is ShadowsocksBean -> toUri()
            is VMessBean -> toUriVMessVLESSTrojan(false)
            is TrojanBean -> toUriVMessVLESSTrojan(true)
            is TrojanGoBean -> toUri()
            is NaiveBean -> toUri()
            is HysteriaBean -> toUri()
            is TuicBean -> toUri()
            is AnyTLSBean -> toUri()
            is NekoBean -> ""
            else -> toUniversalLink()
        }
    }

    fun exportConfig(): Pair<String, String> {
        var name = "${requireBean().displayName()}.json"

        return with(requireBean()) {
            StringBuilder().apply {
                val config = buildConfig(this@ProxyEntity, forExport = true)
                append(config.config)

                if (!config.externalIndex.all { it.chain.isEmpty() }) {
                    name = "profiles.txt"
                }

                for ((chain) in config.externalIndex) {
                    chain.entries.forEachIndexed { index, (port, profile) ->
                        when (val bean = profile.requireBean()) {
                            is TrojanGoBean -> {
                                append("\n\n")
                                append(bean.buildTrojanGoConfig(port))
                            }

                            is MieruBean -> {
                                append("\n\n")
                                append(bean.buildMieruConfig(port))
                            }

                            is NaiveBean -> {
                                append("\n\n")
                                append(bean.buildNaiveConfig(port))
                            }

                            is HysteriaBean -> {
                                append("\n\n")
                                append(bean.buildHysteria1Config(port, null))
                            }
                        }
                    }
                }
            }.toString()
        } to name
    }

    fun needExternal(): Boolean {
        return when (type) {
            TYPE_TROJAN_GO -> true
            TYPE_MIERU -> true
            TYPE_NAIVE -> true
            TYPE_HYSTERIA -> !hysteriaBean!!.canUseSingBox()
            TYPE_NEKO -> true
            else -> false
        }
    }

    private fun StandardV2RayBean.toMux() = buildClientMux(enableMux, muxPadding, muxType, muxConcurrency, muxMaxConnections, muxMinStreams, muxBrutal, muxBrutalUp, muxBrutalDown)

    private fun ShadowsocksBean.toMux() = buildClientMux(enableMux, muxPadding, muxType, muxConcurrency, muxMaxConnections, muxMinStreams, muxBrutal, muxBrutalUp, muxBrutalDown)

    private fun buildClientMux(enableMux: Boolean, muxPadding: Boolean, muxType: Int, muxConcurrency: Int, muxMaxConnections: Int, muxMinStreams: Int, muxBrutal: Boolean, muxBrutalUp: Int, muxBrutalDown: Int) = MultiplexOptions().also {
        it.enabled = enableMux
        it.padding = muxPadding
        if (enableMux) {
            require(muxConcurrency >= 0 && muxMaxConnections >= 0 && muxMinStreams >= 0) {
                "Multiplex limits cannot be negative"
            }
            // Explicit connection limits take precedence over the legacy default max_streams=1.
            if (muxMaxConnections > 0 || muxMinStreams > 0) {
                if (muxMaxConnections > 0) it.max_connections = muxMaxConnections
                if (muxMinStreams > 0) it.min_streams = muxMinStreams
            } else if (muxConcurrency > 0) {
                it.max_streams = muxConcurrency
            }
        }
        it.protocol = when (muxType) {
            1 -> "smux"
            2 -> "yamux"
            3 -> "rmux"
            else -> "h2mux"
        }
        if (enableMux && muxBrutal) it.brutal = OutboundBrutalOptions().apply {
            require(muxBrutalUp > 0 && muxBrutalDown > 0) { "TCP Brutal requires positive upload and download speeds" }
            enabled = true
            up_mbps = muxBrutalUp
            down_mbps = muxBrutalDown
        }
    }

    fun singMux(): MultiplexOptions? {
        return when (type) {
            TYPE_SS -> ssBean!!.toMux()

            TYPE_VMESS -> vmessBean!!.toMux()

            TYPE_TROJAN -> trojanBean!!.toMux()

            else -> null
        }
    }

    fun putBean(bean: AbstractBean): ProxyEntity {
        socksBean = null
        httpBean = null
        ssBean = null
        vmessBean = null
        trojanBean = null
        trojanGoBean = null
        mieruBean = null
        naiveBean = null
        hysteriaBean = null
        sshBean = null
        wgBean = null
        tuicBean = null
        shadowTLSBean = null
        anyTLSBean = null
        chainBean = null
        configBean = null
        nekoBean = null

        when (bean) {
            is SOCKSBean -> {
                type = TYPE_SOCKS
                socksBean = bean
            }

            is HttpBean -> {
                type = TYPE_HTTP
                httpBean = bean
            }

            is ShadowsocksBean -> {
                type = TYPE_SS
                ssBean = bean
            }

            is VMessBean -> {
                type = TYPE_VMESS
                vmessBean = bean
            }

            is TrojanBean -> {
                type = TYPE_TROJAN
                trojanBean = bean
            }

            is TrojanGoBean -> {
                type = TYPE_TROJAN_GO
                trojanGoBean = bean
            }

            is MieruBean -> {
                type = TYPE_MIERU
                mieruBean = bean
            }

            is NaiveBean -> {
                type = TYPE_NAIVE
                naiveBean = bean
            }

            is HysteriaBean -> {
                type = TYPE_HYSTERIA
                hysteriaBean = bean
            }

            is SSHBean -> {
                type = TYPE_SSH
                sshBean = bean
            }

            is WireGuardBean -> {
                type = TYPE_WG
                wgBean = bean
            }

            is TuicBean -> {
                type = TYPE_TUIC
                tuicBean = bean
            }

            is ShadowTLSBean -> {
                type = TYPE_SHADOWTLS
                shadowTLSBean = bean
            }

            is AnyTLSBean -> {
                type = TYPE_ANYTLS
                anyTLSBean = bean
            }

            is ChainBean -> {
                type = TYPE_CHAIN
                chainBean = bean
            }

            is NekoBean -> {
                type = TYPE_NEKO
                nekoBean = bean
            }

            is ConfigBean -> {
                type = TYPE_CONFIG
                configBean = bean
            }

            else -> error("Undefined type $type")
        }
        return this
    }

    fun settingIntent(ctx: Context, isSubscription: Boolean): Intent {
        return Intent(
            ctx, when (type) {
                TYPE_SOCKS -> SocksSettingsActivity::class.java
                TYPE_HTTP -> HttpSettingsActivity::class.java
                TYPE_SS -> ShadowsocksSettingsActivity::class.java
                TYPE_VMESS -> VMessSettingsActivity::class.java
                TYPE_TROJAN -> TrojanSettingsActivity::class.java
                TYPE_TROJAN_GO -> TrojanGoSettingsActivity::class.java
                TYPE_MIERU -> MieruSettingsActivity::class.java
                TYPE_NAIVE -> NaiveSettingsActivity::class.java
                TYPE_HYSTERIA -> HysteriaSettingsActivity::class.java
                TYPE_SSH -> SSHSettingsActivity::class.java
                TYPE_WG -> WireGuardSettingsActivity::class.java
                TYPE_TUIC -> TuicSettingsActivity::class.java
                TYPE_SHADOWTLS -> ShadowTLSSettingsActivity::class.java
                TYPE_ANYTLS -> AnyTLSSettingsActivity::class.java
                TYPE_CHAIN -> ChainSettingsActivity::class.java
                TYPE_CONFIG -> ConfigSettingActivity::class.java
                else -> throw IllegalArgumentException()
            }
        ).apply {
            putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
            putExtra(ProfileSettingsActivity.EXTRA_IS_SUBSCRIPTION, isSubscription)
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("select * from proxy_entities")
        fun getAll(): List<ProxyEntity>

        @Query("SELECT id FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getIdsByGroup(groupId: Long): List<Long>

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities WHERE id in (:proxyIds)")
        fun getEntities(proxyIds: List<Long>): List<ProxyEntity>

        @Query("SELECT COUNT(*) FROM proxy_entities WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT  MAX(userOrder) + 1 FROM proxy_entities WHERE groupId = :groupId")
        fun nextOrder(groupId: Long): Long?

        @Query("SELECT * FROM proxy_entities WHERE id = :proxyId")
        fun getById(proxyId: Long): ProxyEntity?

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyId)")
        fun deleteById(proxyId: Long): Int

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM proxy_entities WHERE groupId in (:groupId)")
        fun deleteByGroup(groupId: LongArray)

        @Delete
        fun deleteProxy(proxy: ProxyEntity): Int

        @Delete
        fun deleteProxy(proxies: List<ProxyEntity>): Int

        @Query("UPDATE proxy_entities SET status = :status, ping = :ping, error = :error WHERE id = :profileId AND groupId = :groupId")
        fun updateTestResult(profileId: Long, groupId: Long, status: Int, ping: Int, error: String?): Int

        @Update
        fun updateProxy(proxy: ProxyEntity): Int

        @Update
        fun updateProxy(proxies: List<ProxyEntity>): Int

        @Insert
        fun addProxy(proxy: ProxyEntity): Long

        @Insert
        fun insert(proxies: List<ProxyEntity>)

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteAll(groupId: Long): Int

        @Query("DELETE FROM proxy_entities")
        fun reset()

    }

    override fun describeContents(): Int {
        return 0
    }
}