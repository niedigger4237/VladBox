package io.nekohasekai.sagernet.fmt.hysteria

import org.junit.Assert.*
import org.junit.Test

class HysteriaShareTest {
    @Test fun advancedSettingsRequireUniversalLink() {
        val bean = parseHysteria2("hy2://test@example.org").apply { initializeDefaultValues() }
        assertTrue(bean.canShareStandardLink())
        bean.hy2BbrProfile = "aggressive"
        assertFalse(bean.canShareStandardLink())
        bean.hy2BbrProfile = ""
        bean.caText = "custom CA"
        assertFalse(bean.canShareStandardLink())
    }

    @Test fun authWithEmptyPasswordRetainsColon() {
        val bean = parseHysteria2("hy2://user:@example.org").apply { initializeDefaultValues() }
        assertEquals("user:", bean.authPayload)
    }
}
