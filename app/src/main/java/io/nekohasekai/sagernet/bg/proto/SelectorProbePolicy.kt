package io.nekohasekai.sagernet.bg.proto

/** Prefer a temporary core whenever runtime identity cannot be established. */
internal object SelectorProbePolicy {
    fun eligible(
        sameGroup: Boolean,
        groupHops: Boolean,
        singleMember: Boolean,
        complexProfile: Boolean,
        customOverrides: Boolean,
        sameBean: Boolean,
        uniqueTag: Boolean,
    ): Boolean = sameGroup && !groupHops && singleMember && !complexProfile &&
        !customOverrides && sameBean && uniqueTag
}
