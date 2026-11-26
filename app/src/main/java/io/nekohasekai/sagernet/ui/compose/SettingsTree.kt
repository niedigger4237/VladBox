package io.nekohasekai.sagernet.ui.compose
import androidx.preference.*
import moe.matsuri.nb4a.ui.*
import io.nekohasekai.sagernet.R

fun buildSettingsTree(manager: PreferenceManager): PreferenceScreen {
    val ctx = manager.context
    val screen = manager.createPreferenceScreen(ctx)
    fun category(titleId: Int, content: PreferenceCategory.() -> Unit) {
        val group = PreferenceCategory(ctx).apply { setTitle(titleId) }
        screen.addPreference(group)
        group.content()
    }
    category(R.string.cat_general) {
        addPreference(SwitchPreference(ctx).apply { key = "isAutoConnect"; setTitle(R.string.auto_connect); setSummary(R.string.auto_connect_summary); setDefaultValue(false) })
        addPreference(SimpleMenuPreference(ctx).apply { key = "nightTheme"; setTitle(R.string.night_mode); setEntries(R.array.night_mode); setEntryValues(R.array.int_array_4); setDefaultValue("1"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SwitchPreference(ctx).apply { key = "alwaysShowAddress"; setTitle(R.string.always_show_address); setSummary(R.string.always_show_address_sum) })
        addPreference(SwitchPreference(ctx).apply { key = "showGroupInNotification"; setTitle(R.string.show_group_in_notification) })
    }
    category(R.string.cat_service) {
        addPreference(SimpleMenuPreference(ctx).apply { key = "serviceMode"; setTitle(R.string.service_mode); setEntries(R.array.service_modes); setEntryValues(R.array.service_mode_values); setDefaultValue("vpn"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply { key = "tunImplementation"; setTitle(R.string.tun_implementation); setEntries(R.array.tun_implementation); setEntryValues(R.array.int_array_3); setDefaultValue("0"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply {
            key = "tunAddressMode"
            setTitle(R.string.tun_address_mode)
            entries = arrayOf(ctx.getString(R.string.tun_address_automatic), ctx.getString(R.string.tun_address_manual))
            entryValues = arrayOf("automatic", "manual")
            setDefaultValue("automatic")
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        })
        addPreference(EditTextPreference(ctx).apply { key = "tunIpv4Subnet"; setTitle(R.string.tun_ipv4_subnet); setDefaultValue("172.19.0.0/28"); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(EditTextPreference(ctx).apply { key = "tunIpv6Subnet"; setTitle(R.string.tun_ipv6_subnet); setDefaultValue("fdfe:dcba:9876::/126"); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(MTUPreference(ctx).apply { key = "mtu"; setTitle(R.string.mtu); setEntries(R.array.mtu_select); setEntryValues(R.array.mtu_select); setDefaultValue("9000") })
        addPreference(SimpleMenuPreference(ctx).apply { key = "ipv6Mode"; setTitle(R.string.ipv6); setEntries(R.array.ipv6_mode); setEntryValues(R.array.int_array_4); setDefaultValue("0"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(Preference(ctx).apply { key = "proxyApps"; setTitle(R.string.proxied_apps); setSummary(R.string.proxied_apps_summary) })
        addPreference(SwitchPreference(ctx).apply { key = "bypassLan"; setTitle(R.string.route_opt_bypass_lan) })
        addPreference(SwitchPreference(ctx).apply { key = "meteredNetwork"; setTitle(R.string.metered); setSummary(R.string.metered_summary) })
        addPreference(SwitchPreference(ctx).apply { key = "acquireWakeLock"; setTitle(R.string.acquire_wake_lock); setSummary(R.string.acquire_wake_lock_summary) })
    }
    category(R.string.cat_stats) {
        addPreference(SimpleMenuPreference(ctx).apply { key = "speedInterval"; setTitle(R.string.speed_interval); setEntries(R.array.notification_entry); setEntryValues(R.array.notification_value); setDefaultValue("1000"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SwitchPreference(ctx).apply { key = "profileTrafficStatistics"; setTitle(R.string.profile_traffic_statistics); setSummary(R.string.profile_traffic_statistics_summary); setDefaultValue(true) })
        addPreference(SwitchPreference(ctx).apply { key = "showDirectSpeed"; setTitle(R.string.show_direct_speed); setSummary(R.string.show_direct_speed_sum); setDefaultValue(true) })
    }
    category(R.string.cag_route) {
        addPreference(SimpleMenuPreference(ctx).apply { key = "routeFinal"; setTitle(R.string.route_final); setEntries(R.array.route_final_names); setEntryValues(R.array.route_final_values); setDefaultValue("proxy"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply { key = "trafficSniffing"; setTitle(R.string.traffic_sniffing); setEntries(R.array.traffic_sniffing_values); setEntryValues(R.array.int_array_3); setDefaultValue("1"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SwitchPreference(ctx).apply { key = "resolveDestination"; setTitle(R.string.resolve_destination); setSummary(R.string.resolve_destination_summary) })
        addPreference(SwitchPreference(ctx).apply { key = "bypassLanInCore"; setTitle(R.string.bypass_lan_in_core) })
        addPreference(SimpleMenuPreference(ctx).apply { key = "rulesProvider"; setTitle(R.string.route_rules_provider); setEntries(R.array.rules_dat_provider); setEntryValues(R.array.srs_provider_values); setDefaultValue("0"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
    }
    category(R.string.cag_dns) {
        addPreference(SimpleMenuPreference(ctx).apply { key = "remoteDnsPreset"; setTitle(R.string.remote_dns); setEntries(R.array.dns_preset_names); setEntryValues(R.array.dns_preset_values); setDefaultValue("google"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(EditTextPreference(ctx).apply { key = "remoteDns"; setTitle(R.string.dns_custom_remote); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply { key = "domain_strategy_for_remote"; setTitle(R.string.domain_strategy_for_remote); setEntries(R.array.dns_network_select); setEntryValues(R.array.dns_network_select); setDefaultValue("auto"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply { key = "directDnsPreset"; setTitle(R.string.direct_dns); setEntries(R.array.dns_preset_names); setEntryValues(R.array.dns_preset_values); setDefaultValue("quad9"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(EditTextPreference(ctx).apply { key = "directDns"; setTitle(R.string.dns_custom_direct); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply { key = "dnsMain"; setTitle(R.string.dns_main); setEntries(R.array.dns_main_names); setEntryValues(R.array.dns_main_values); setDefaultValue("direct"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply { key = "domain_strategy_for_direct"; setTitle(R.string.domain_strategy_for_direct); setEntries(R.array.dns_network_select); setEntryValues(R.array.dns_network_select); setDefaultValue("auto"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SimpleMenuPreference(ctx).apply { key = "domain_strategy_for_server"; setTitle(R.string.domain_strategy_for_server); setEntries(R.array.dns_network_select); setEntryValues(R.array.dns_network_select); setDefaultValue("auto"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SwitchPreference(ctx).apply { key = "enableDnsRouting"; setTitle(R.string.enable_dns_routing); setSummary(R.string.dns_routing_message); setDefaultValue(true) })
        addPreference(SwitchPreference(ctx).apply { key = "enableFakeDns"; setTitle(R.string.enable_fakedns); setSummary(R.string.fakedns_message); setDefaultValue(false) })
        addPreference(SimpleMenuPreference(ctx).apply { key = "hijackDns"; setTitle(R.string.hijack_dns); setEntries(R.array.hijack_dns_names); setEntryValues(R.array.hijack_dns_values); setDefaultValue("both"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
    }
    category(R.string.cat_local_proxy) {
        addPreference(EditTextPreference(ctx).apply { key = "mixedPort"; setTitle(R.string.port_proxy); setDefaultValue("10808"); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(EditTextPreference(ctx).apply { key = "socksPort"; setTitle(R.string.socks_port); setDefaultValue("10809"); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(EditTextPreference(ctx).apply { key = "rootTproxyPort"; setTitle(R.string.root_tproxy_port); setDefaultValue("9898"); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(EditTextPreference(ctx).apply { key = "bpfBridgePort"; setTitle(R.string.bpf_bridge_port); setDefaultValue("9899"); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SwitchPreference(ctx).apply { key = "appendHttpProxy"; setTitle(R.string.append_http_proxy); setSummary(R.string.append_http_proxy_sum); setDefaultValue(false) })
        addPreference(SwitchPreference(ctx).apply { key = "allowAccess"; setTitle(R.string.allow_access); setSummary(R.string.allow_access_sum) })
    }
    category(R.string.cat_tethering) {
        addPreference(EditTextPreference(ctx).apply { key = "rootTunInterfaces"; setTitle(R.string.root_tun_interfaces); setSummary(R.string.root_tun_interfaces_sum) })
        addPreference(EditTextPreference(ctx).apply { key = "rootTunBypassRuleSets"; setTitle(R.string.root_tun_bypass_rulesets); setSummary(R.string.root_tun_bypass_rulesets_sum) })
        addPreference(EditTextPreference(ctx).apply { key = "rootIgnoredInterfaces"; setTitle(R.string.root_ignored_interfaces); setSummary(R.string.root_ignored_interfaces_sum) })
        addPreference(SwitchPreference(ctx).apply { key = "tetherWifi"; setTitle(R.string.tether_wifi); setDefaultValue(false) })
        addPreference(SwitchPreference(ctx).apply { key = "tetherBluetooth"; setTitle(R.string.tether_bluetooth); setDefaultValue(false) })
        addPreference(SwitchPreference(ctx).apply { key = "tetherUsb"; setTitle(R.string.tether_usb); setDefaultValue(false) })
        addPreference(SwitchPreference(ctx).apply { key = "tetherEthernet"; setTitle(R.string.tether_ethernet); setDefaultValue(false) })
        addPreference(EditTextPreference(ctx).apply { key = "tetherCustom"; setTitle(R.string.tether_custom); setSummary(R.string.tether_custom_sum) })
    }
    category(R.string.root_settings) {
        addPreference(SwitchPreference(ctx).apply { key = "rootFlowMetadata"; setTitle(R.string.root_flow_metadata); setSummary(R.string.root_flow_metadata_sum); setDefaultValue(false) })
        addPreference(SwitchPreference(ctx).apply { key = "rootEbpfMatcher"; setTitle(R.string.root_ebpf_matcher); setSummary(R.string.root_ebpf_matcher_sum); setDefaultValue(true) })
        addPreference(SwitchPreference(ctx).apply { key = "rootDisableSystemIpv6"; setTitle(R.string.root_disable_ipv6); setSummary(R.string.root_disable_ipv6_sum); setDefaultValue(true) })
        addPreference(SwitchPreference(ctx).apply { key = "rootHevMultiQueue"; setTitle(R.string.root_hev_multiqueue); setDefaultValue(true) })
        addPreference(SwitchPreference(ctx).apply { key = "rootHevTcpFastOpen"; setTitle(R.string.root_hev_tfo); setDefaultValue(true) })
        addPreference(Preference(ctx).apply { key = "serviceControl"; setTitle(R.string.service_control); setSummary(R.string.service_control_sum) })
        addPreference(SwitchPreference(ctx).apply { key = "startOnBoot"; setTitle(R.string.start_on_boot); setSummary(R.string.start_on_boot_sum); setDefaultValue(false) })
        addPreference(Preference(ctx).apply { key = "rootCoreUpdate"; setTitle(R.string.root_core_update) })
        addPreference(Preference(ctx).apply { key = "rootCoreImport"; setTitle(R.string.root_core_import) })
        addPreference(Preference(ctx).apply { key = "rootCoreRollback"; setTitle(R.string.root_core_rollback) })
        addPreference(Preference(ctx).apply { key = "rootCoreBundled"; setTitle(R.string.root_core_bundled) })
    }
    category(R.string.cat_security) {
        addPreference(UrlTestPreference(ctx).apply { key = "connectionTestURL"; setTitle(R.string.connection_test_url); setDefaultValue("http://cp.cloudflare.com/"); summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SwitchPreference(ctx).apply { key = "networkChangeResetConnections"; setTitle(R.string.network_change_reset_connections); setDefaultValue(true) })
        addPreference(SwitchPreference(ctx).apply { key = "wakeResetConnections"; setTitle(R.string.wake_reset_connections) })
        addPreference(SwitchPreference(ctx).apply { key = "globalAllowInsecure"; setTitle(R.string.global_allow_insecure) })
        addPreference(SwitchPreference(ctx).apply { key = "allowInsecureOnRequest"; setTitle(R.string.allow_insecure_on_request_sum) })
        addPreference(SimpleMenuPreference(ctx).apply { key = "appTLSVersion"; setTitle(R.string.app_tls_version); setEntries(R.array.app_tls_version); setEntryValues(R.array.app_tls_version); setDefaultValue("1.2"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
    }
    category(R.string.cat_advanced) {
        addPreference(LongClickListPreference(ctx).apply { key = "logLevel"; setTitle(R.string.log_level); setEntries(R.array.log_level); setEntryValues(R.array.int_array_5); setDefaultValue("2"); summaryProvider = ListPreference.SimpleSummaryProvider.getInstance() })
        addPreference(SwitchPreference(ctx).apply { key = "enableClashAPI"; setTitle(R.string.enable_clash_api); setSummary(R.string.enable_clash_api_summary) })
        addPreference(EditConfigPreference(ctx).apply { key = "globalCustomConfig"; setTitle(R.string.custom_config) })
    }
    return screen
}
