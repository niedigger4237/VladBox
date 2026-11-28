package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
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

    suspend fun clearGroup(groupId: Long) {
        val removed = SagerDatabase.proxyDao.getIdsByGroup(groupId)
        SagerDatabase.proxyDao.deleteAll(groupId)
        if (DataStore.selectedProxy in removed) DataStore.selectedProxy = 0L
        SagerNet.notifySelectorChanged(listOf(groupId), removed)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        val old = SagerDatabase.groupDao.getById(group.id)
        SagerDatabase.groupDao.updateGroup(group)
        if (old == null || old.isSelector != group.isSelector || old.frontProxy != group.frontProxy ||
            old.landingProxy != group.landingProxy || old.name != group.name)
            SagerNet.notifySelectorChanged(listOf(group.id))
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        val removed = SagerDatabase.proxyDao.getIdsByGroup(groupId)
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.groupDao.deleteById(groupId)
            SagerDatabase.proxyDao.deleteByGroup(groupId)
        }
        if (DataStore.selectedProxy in removed) DataStore.selectedProxy = 0L
        SagerNet.notifySelectorChanged(listOf(groupId), removed)
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        val groupIds = group.map { it.id }
        val removed = groupIds.flatMap { SagerDatabase.proxyDao.getIdsByGroup(it) }
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.groupDao.deleteGroup(group)
            SagerDatabase.proxyDao.deleteByGroup(groupIds.toLongArray())
        }
        if (DataStore.selectedProxy in removed) DataStore.selectedProxy = 0L
        SagerNet.notifySelectorChanged(groupIds, removed)
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}