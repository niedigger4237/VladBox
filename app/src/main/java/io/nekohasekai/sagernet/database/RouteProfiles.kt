package io.nekohasekai.sagernet.database

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.nekohasekai.sagernet.SagerNet
import java.util.UUID

/**
 * Independent routing profiles. The selected profile lives in the existing rules table,
 * so the VPN engine and existing rule editor keep using the same active rules.
 * Inactive profiles are saved as JSON snapshots in app-private preferences.
 */
object RouteProfiles {
    data class Snapshot(
        val id: String,
        var name: String,
        var rules: List<RuleEntity>,
        var routeFinal: String,
        var dnsMain: String,
        var importedFinal: Long? = null,
        var importedDns: String? = null,
        var importedDnsDirect: Boolean? = null,
        var importedDnsStrategy: String? = null
    )

    private val gson = Gson()
    private val type = TypeToken.getParameterized(List::class.java, Snapshot::class.java).type
    private val prefs get() = SagerNet.application.getSharedPreferences("routing_profiles_v1", 0)

    @Synchronized
    fun all(): MutableList<Snapshot> {
        val saved = prefs.getString("profiles", null)
        if (saved != null) return gson.fromJson<MutableList<Snapshot>>(saved, type)
        val defaults = mutableListOf(Snapshot("default", "Default", emptyList(), "proxy", "remote"))
        persist(defaults, "default")
        return defaults
    }

    fun selectedId(): String = prefs.getString("selected", "default") ?: "default"

    private fun persist(items: List<Snapshot>, selected: String) {
        check(prefs.edit().putString("profiles", gson.toJson(items))
            .putString("selected", selected).commit()) { "Unable to save routing profiles" }
    }

    @Synchronized
    fun activeImported(): Snapshot? = all().firstOrNull { it.id == selectedId() && it.importedFinal != null }

    @Synchronized
    fun addImported(items: List<Snapshot>) {
        if (items.isEmpty()) return
        val profiles = all()
        profiles.addAll(items)
        persist(profiles, selectedId())
    }

    /** Save the active profile before switching; never modify another profile's rules. */
    @Synchronized
    fun select(id: String) {
        if (id == selectedId()) return
        val profiles = all()
        val current = profiles.firstOrNull { it.id == selectedId() } ?: error("Current profile missing")
        val target = profiles.firstOrNull { it.id == id } ?: error("Profile missing")
        if (id == "default") {
            target.rules.forEach { rule ->
                if (rule.name == "Analysis" &&
                    rule.domains == "domain:appcenter.ms\ndomain:firebase.io\ndomain:crashlytics.com") {
                    rule.domains = "appcenter.ms\nfirebase.io\ncrashlytics.com"
                }
            }
        }
        current.rules = SagerDatabase.rulesDao.allRules()
        current.routeFinal = DataStore.routeFinal
        current.dnsMain = DataStore.dnsMain
        val previousRules = current.rules
        val previousFinal = current.routeFinal
        val previousDns = current.dnsMain
        try {
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.rulesDao.reset()
                SagerDatabase.rulesDao.insert(target.rules.map { it.copy() })
            }
            DataStore.routeFinal = target.routeFinal
            DataStore.dnsMain = target.dnsMain
            persist(profiles, id)
            SagerNet.reloadService()
        } catch (e: Exception) {
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.rulesDao.reset()
                SagerDatabase.rulesDao.insert(previousRules.map { it.copy() })
            }
            DataStore.routeFinal = previousFinal
            DataStore.dnsMain = previousDns
            throw e
        }
    }

    @Synchronized
    fun create(name: String, copyActive: Boolean = false): String {
        require(name.isNotBlank()) { "Profile name cannot be empty" }
        val profiles = all()
        val id = UUID.randomUUID().toString()
        val rules = if (copyActive) SagerDatabase.rulesDao.allRules().map { it.copy(id = 0L) } else emptyList()
        profiles.add(Snapshot(id, name.trim(), rules, DataStore.routeFinal, DataStore.dnsMain))
        persist(profiles, selectedId())
        return id
    }

    @Synchronized
    fun rename(id: String, name: String) {
        require(name.isNotBlank())
        val profiles = all()
        profiles.first { it.id == id }.name = name.trim()
        persist(profiles, selectedId())
    }

    @Synchronized
    fun delete(id: String) {
        require(id != "default") { "Default profile cannot be deleted" }
        if (id == selectedId()) select("default")
        val profiles = all()
        check(profiles.removeAll { it.id == id }) { "Profile not found" }
        persist(profiles, selectedId())
    }

    fun ruleCount(profile: Snapshot): Int =
        if (profile.id == selectedId()) SagerDatabase.rulesDao.allRules().size else profile.rules.size
}
