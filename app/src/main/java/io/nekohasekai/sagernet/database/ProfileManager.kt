package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.v2ray.XrayJsonImport
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: TrafficData)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        SagerNet.notifySelectorChanged(listOf(groupId), listOf(profile.id))
        iterator { onAdd(profile) }
        return profile
    }

    /** Insert chain members and resolve their actual database IDs atomically.
     * Use identity, not AbstractBean.equals (which ignores profile names).
     * Do not notify UI listeners until the transaction has committed.
     */
    suspend fun createImportedChains(
        groupId: Long, beans: List<AbstractBean>, chains: List<XrayJsonImport.ImportedChain>
    ) {
        val group = SagerDatabase.groupDao.getById(groupId) ?: error("Import group not found")
        require(group.frontProxy <= 0L && group.landingProxy <= 0L) {
            "Import Xray chains into a group without front or landing proxies"
        }
        val ids = java.util.IdentityHashMap<AbstractBean, Long>()
        val created = ArrayList<ProxyEntity>()
        SagerDatabase.instance.runInTransaction {
            fun insert(bean: AbstractBean): ProxyEntity {
                bean.applyDefaultValues()
                val entity = ProxyEntity(groupId = groupId).apply {
                    putBean(bean)
                    userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
                }
                entity.id = SagerDatabase.proxyDao.addProxy(entity)
                created.add(entity)
                return entity
            }
            for (bean in beans) ids[bean] = insert(bean).id
            for (chain in chains) {
                require(chain.hops.size >= 2) { "Incomplete imported Xray chain" }
                insert(ChainBean().apply {
                    name = chain.name
                    proxies = chain.hops.map { ids[it] ?: error("Missing imported Xray hop") }
                })
            }
        }
        SagerNet.notifySelectorChanged(listOf(groupId), created.map { it.id })
        for (entity in created) iterator { onAdd(entity) }
    }

    private fun configurationChanged(old: ProxyEntity?, updated: ProxyEntity): Boolean =
        old == null || old.groupId != updated.groupId || old.type != updated.type ||
            old.displayName() != updated.displayName() || old.requireBean() != updated.requireBean()

    suspend fun updateProfile(profile: ProxyEntity) {
        val old = SagerDatabase.proxyDao.getById(profile.id)
        SagerDatabase.proxyDao.updateProxy(profile)
        if (configurationChanged(old, profile))
            SagerNet.notifySelectorChanged(listOfNotNull(old?.groupId, profile.groupId), listOf(profile.id))
        iterator { onUpdated(profile, false) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        val old = SagerDatabase.proxyDao.getEntities(profiles.map { it.id }).associateBy { it.id }
        val changed = profiles.filter { configurationChanged(old[it.id], it) }
        SagerDatabase.proxyDao.updateProxy(profiles)
        SagerNet.notifySelectorChanged(changed.flatMap { listOfNotNull(old[it.id]?.groupId, it.groupId) },
            changed.map { it.id })
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        SagerNet.notifySelectorChanged(listOf(groupId), listOf(profileId))
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        SagerNet.notifySelectorChanged(listOf(groupId), listOf(profileId))
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: TrafficData) {
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    /** One-time replacement requested for the Default routing template. */
    fun installDefaultRouteTemplate() {
        val store = DataStore.configurationStore
        if (store.getBoolean("defaultRouteTemplateV1Installed", false)) return
        SagerDatabase.instance.runInTransaction {
            if (store.getBoolean("defaultRouteTemplateV1Installed", false)) return@runInTransaction
            SagerDatabase.rulesDao.reset()
            val defaults = listOf(
                RuleEntity(name = "Ads", userOrder = 1, enabled = true,
                    domains = "geosite:category-ads-all", outbound = -2L),
                RuleEntity(name = "Analysis", userOrder = 2, enabled = true,
                    domains = "appcenter.ms\nfirebase.io\ncrashlytics.com", outbound = -2L),
                RuleEntity(name = "Direct IP", userOrder = 3, enabled = true,
                    ip = "geoip:ru\ngeoip:private", outbound = -1L),
                RuleEntity(name = "Direct Domain", userOrder = 4, enabled = true,
                    domains = listOf("geosite:category-gov-ru", "geosite:category-ru",
                        "geosite:ru-suffixes", "geosite:yandex", "geosite:mailru",
                        "geosite:vk", "geosite:private").joinToString("\n"), outbound = -1L)
            )
            SagerDatabase.rulesDao.insert(defaults)
            store.putBoolean("defaultRouteTemplateV1Installed", true)
        }
    }

    /** Update existing Default Analysis rules without resetting user customizations. */
    fun migrateDefaultAnalysisDomains() {
        if (RouteProfiles.selectedId() != "default") return
        SagerDatabase.rulesDao.allRules()
            .filter { it.name == "Analysis" }
            .forEach { rule ->
                val old = "domain:appcenter.ms\ndomain:firebase.io\ndomain:crashlytics.com"
                if (rule.domains == old) {
                    rule.domains = "appcenter.ms\nfirebase.io\ncrashlytics.com"
                    SagerDatabase.rulesDao.updateRule(rule)
                }
            }
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.allRules()
        // маршрутизация по умолчанию пуста: никаких предустановленных правил

        return rules
    }

}
