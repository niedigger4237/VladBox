package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteProfiles
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.SagerNet
import androidx.compose.foundation.lazy.items
import io.nekohasekai.sagernet.fmt.ManagedDnsRules
import io.nekohasekai.sagernet.fmt.ManagedDnsRules.Rule
import io.nekohasekai.sagernet.ui.compose.HubTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsRulesActivity : ThemedActivity() {
    companion object {
        const val EXTRA_IP_RULE_NAME = "dns.ip.rule.name"
        const val EXTRA_IP_RULE_VALUES = "dns.ip.rule.values"
        const val EXTRA_IP_PROFILE = "dns.ip.profile"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HubTheme { Screen() } }
    }

    @Composable private fun Screen() {
        var rules by remember { mutableStateOf<List<Rule>>(emptyList()) }
        var source by remember { mutableStateOf("") }
        var profileId by remember { mutableStateOf("") }
        var profileName by remember { mutableStateOf("") }
        var modern by remember { mutableStateOf(false) }
        var remote by remember { mutableStateOf(false) }
        var bootstrapAvailable by remember { mutableStateOf(false) }
        var installedSrs by remember { mutableStateOf<List<String>>(emptyList()) }
        var installedGeoIp by remember { mutableStateOf<List<String>>(listOf("geoip:private")) }
        var ipWizard by remember { mutableStateOf(false) }
        var routingSources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var copyPicker by remember { mutableStateOf(false) }
        var ready by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var editor by remember { mutableStateOf<Rule?>(null) }
        var deleting by remember { mutableStateOf<Rule?>(null) }
        LaunchedEffect(Unit) {
            try {
                withContext(Dispatchers.IO) {
                    val id = RouteProfiles.selectedId()
                    val json = DataStore.activeDnsRulesJson
                    val name = RouteProfiles.all().firstOrNull { it.id == id }?.name.orEmpty()
                    val parsed = ManagedDnsRules.decode(json)
                    val modernMode = DataStore.dnsEngineMode == "modern"
                    val useRemote = DataStore.dnsMain != "direct_only"
                    val useBootstrap = DataStore.dnsBootstrapPreset != "system"
                    val sets = SagerNet.application.externalAssets.listFiles().orEmpty()
                        .filter { it.isFile && it.length() > 0 && it.name.matches(Regex("geosite-[A-Za-z0-9._!-]+[.]srs")) }
                        .map { "geosite:" + it.name.removePrefix("geosite-").removeSuffix(".srs") }.distinct().sorted()
                    val geoIp = (SagerNet.application.externalAssets.listFiles().orEmpty()
                        .filter { it.isFile && it.length() > 0 && it.name.matches(Regex("geoip-[A-Za-z0-9._!-]+[.]srs")) }
                        .map { "geoip:" + it.name.removePrefix("geoip-").removeSuffix(".srs") } + "geoip:private").distinct().sorted()
                    val sources = SagerDatabase.rulesDao.allRules().filter { it.domains.isNotBlank() }
                        .map { it.displayName() to it.domains }
                    withContext(Dispatchers.Main) {
                        bootstrapAvailable = useBootstrap; installedSrs = sets; routingSources = sources
                        source = json; profileId = id; profileName = name
                        rules = parsed; modern = modernMode; remote = useRemote; ready = true
                        installedGeoIp = geoIp
                        if (intent.hasExtra(EXTRA_IP_RULE_VALUES)) {
                            if (intent.getStringExtra(EXTRA_IP_PROFILE) != id) error = "Routing profile changed. Reopen the IP rule."
                            else if (!modernMode) error = "IP response matching requires Modern DNS."
                            else ipWizard = true
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Cannot load DNS rules" }
        }
        fun save(next: List<Rule>) {
            if (!ready || busy) return
            busy = true
            error = null
            lifecycleScope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        val value = ManagedDnsRules.encode(next)
                        RouteProfiles.saveActiveDnsRules(profileId, source, value)
                        value
                    }
                    source = json; rules = next; editor = null; deleting = null; ipWizard = false
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Unable to save DNS rules" }
                finally { busy = false }
            }
        }
        val activeIds = remember(rules, remote, bootstrapAvailable) {
            ManagedDnsRules.effective(rules, remote, bootstrapAvailable).map { it.id }.toSet()
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }, enabled = !busy) { Text("Back") }
                    Column(Modifier.weight(1f)) {
                        Text("DNS Rules", style = MaterialTheme.typography.titleLarge)
                        Text(profileName, style = MaterialTheme.typography.labelMedium)
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(if (modern) "Explicit rules run before automatic DNS routing rules. Evaluate continues; matching final actions stop processing."
                            else "Legacy mode: these rules are saved but not applied.", style = MaterialTheme.typography.bodyMedium)
                        if (!remote) Text("Remote DNS rules are paused in Direct Only mode.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        if (!ready && error == null) CircularProgressIndicator()
                        Button(onClick = { editor = Rule() }, enabled = ready && !busy && rules.size < 256,
                            modifier = Modifier.fillMaxWidth()) { Text("+ Add DNS rule") }
                        OutlinedButton(onClick = { copyPicker = true }, enabled = ready && !busy && rules.size < 256 && routingSources.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) { Text("Copy domains from Routing Rule") }
                        if (ready && rules.isEmpty()) Text("No explicit rules. Automatic DNS routing is unchanged.")
                    }
                    itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                        val sets = ManagedDnsRules.ruleSets(rule)
                                        val geoIp = ManagedDnsRules.responseRuleSets(rule)
                                        if (geoIp.isNotEmpty()) {
                                            Text("Response GeoIP: ${geoIp.joinToString()}", style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                            if (geoIp.any { it !in installedGeoIp }) Text("Missing GeoIP SRS", color = MaterialTheme.colorScheme.error)
                                        }
                                        if (rule.domains.isNotBlank()) Text(rule.domains, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                        if (sets.isNotEmpty()) {
                                            Text("Geosite SRS: ${sets.size}", style = MaterialTheme.typography.bodySmall)
                                            if (sets.any { it !in installedSrs }) Text("Missing SRS assets", color = MaterialTheme.colorScheme.error)
                                        }
                                        if (rule.domains.isBlank() && sets.isEmpty()) Text("No domain restriction", style = MaterialTheme.typography.bodySmall)
                                        val actionLabel = when (ManagedDnsRules.operation(rule)) {
                                            "evaluate" -> "Evaluate · ${ManagedDnsRules.targetLabel(rule.target)}"
                                            "respond" -> "Respond"
                                            else -> ManagedDnsRules.targetLabel(rule.target)
                                        }
                                        Text(actionLabel + if (rule.queries.isBlank()) "" else " · " + rule.queries)
                                        rules.firstOrNull { it.id == ManagedDnsRules.responseId(rule) }?.let {
                                            Text("Match response: ${it.name}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        ManagedDnsRules.tuningSummary(rule).takeIf { it.isNotEmpty() }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (rule.enabled && (!modern || rule.id !in activeIds)) Text("Paused", color = MaterialTheme.colorScheme.error)
                                    }
                                    Switch(checked = rule.enabled, onCheckedChange = { enabled ->
                                        save(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                                    }, enabled = !busy)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(enabled = !busy && index > 0, onClick = {
                                        save(rules.toMutableList().apply { add(index - 1, removeAt(index)) })
                                    }) { Text("↑") }
                                    TextButton(enabled = !busy && index < rules.lastIndex, onClick = {
                                        save(rules.toMutableList().apply { add(index + 1, removeAt(index)) })
                                    }) { Text("↓") }
                                    TextButton(enabled = !busy, onClick = { editor = rule }) { Text("Edit") }
                                    TextButton(enabled = !busy, onClick = { deleting = rule }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (ipWizard) {
            var initialTarget by remember { mutableStateOf("dns-direct") }
            var matchedTarget by remember { mutableStateOf("dns-local") }
            var wizardError by remember { mutableStateOf<String?>(null) }
            val available = ManagedDnsRules.targets.filter { (tag, _) ->
                (tag != "dns-remote" || remote) && (tag != "dns-bootstrap" || bootstrapAvailable)
            }
            val name = intent.getStringExtra(EXTRA_IP_RULE_NAME).orEmpty().ifBlank { "Destination IP" }
            val input = intent.getStringExtra(EXTRA_IP_RULE_VALUES).orEmpty()
            AlertDialog(onDismissRequest = { if (!busy) ipWizard = false },
                title = { Text("DNS by destination IP") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(input, style = MaterialTheme.typography.bodySmall)
                        Choice("Evaluate DNS", initialTarget, available.filter { it.first != "block" }) { initialTarget = it }
                        Choice("DNS on IP match", matchedTarget, available) { matchedTarget = it }
                        Text("Creates two independent DNS rules for A/AAAA queries, after existing explicit DNS rules. On mismatch, normal DNS processing continues. Matching queries may require a second lookup. The traffic rule is unchanged; edits to it are not synced.")
                        (wizardError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(enabled = !busy && rules.size <= 254, onClick = {
                    try {
                        val pair = ManagedDnsRules.fromDestinationIp(name, input, initialTarget, matchedTarget)
                        val missing = pair.flatMap { ManagedDnsRules.responseRuleSets(it) }.filterNot { it in installedGeoIp }
                        require(missing.isEmpty()) { "Install missing GeoIP SRS in Assets: ${missing.joinToString()}" }
                        wizardError = null
                        save(rules + pair)
                    } catch (e: Exception) { wizardError = e.message ?: "Invalid IP rule" }
                }) { Text("Create DNS rules") } },
                dismissButton = { TextButton(enabled = !busy, onClick = { ipWizard = false }) { Text("Cancel") } })
        }
        if (copyPicker) {
            AlertDialog(onDismissRequest = { copyPicker = false }, title = { Text("Copy domain conditions") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copies domains and Geosite SRS only. IP, app, transport, logical mode and outbound are not copied. Review the new rule before saving.")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(routingSources) { _, (name, domains) ->
                            TextButton(onClick = {
                                try {
                                    editor = ManagedDnsRules.copyDomainConditions(name, domains)
                                    copyPicker = false; error = null
                                } catch (e: Exception) { error = e.message ?: "Cannot copy domains"; copyPicker = false }
                            }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { copyPicker = false }) { Text("Cancel") } })
        }
        editor?.let { original ->
            key(original.id) {
                val index = rules.indexOfFirst { it.id == original.id }
                val priorEvaluations = (if (index < 0) rules else rules.take(index))
                    .filter { ManagedDnsRules.operation(it) == "evaluate" }
                RuleDialog(original, priorEvaluations, installedSrs, installedGeoIp, bootstrapAvailable, busy, error, onDismiss = { if (!busy) { editor = null; error = null } }) { updated ->
                    val next = if (rules.any { it.id == updated.id }) rules.map { if (it.id == updated.id) updated else it } else rules + updated
                    save(next)
                }
            }
        }
        deleting?.let { rule ->
            AlertDialog(onDismissRequest = { if (!busy) deleting = null },
                title = { Text("Delete ${rule.name}?") },
                text = { Text(error ?: "Rules that reference this Evaluate must be removed or changed first.") },
                confirmButton = { TextButton(enabled = !busy, onClick = { save(rules.filter { it.id != rule.id }) }) { Text("Delete") } },
                dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancel") } })
        }
    }

    @Composable private fun RuleDialog(rule: Rule, evaluations: List<Rule>, installedSrs: List<String>,
        installedGeoIp: List<String>, bootstrapAvailable: Boolean, busy: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
        var name by remember { mutableStateOf(rule.name) }
        var domains by remember { mutableStateOf(rule.domains) }
        var selectedSrs by remember { mutableStateOf(ManagedDnsRules.ruleSets(rule).toSet()) }
        var srsPicker by remember { mutableStateOf(false) }
        var queries by remember { mutableStateOf(rule.queries) }
        var match by remember { mutableStateOf(rule.match) }
        var target by remember { mutableStateOf(rule.target) }
        var operation by remember { mutableStateOf(ManagedDnsRules.operation(rule)) }
        var logicalMode by remember { mutableStateOf(rule.logicalMode.orEmpty()) }
        var responseFrom by remember { mutableStateOf(ManagedDnsRules.responseId(rule)) }
        var responseCode by remember { mutableStateOf(rule.responseCode.orEmpty()) }
        var responseIp by remember { mutableStateOf(rule.responseIp.orEmpty()) }
        var responseCidrs by remember { mutableStateOf(rule.responseCidrs.orEmpty()) }
        var responseSrs by remember { mutableStateOf(ManagedDnsRules.responseRuleSets(rule).toSet()) }
        var geoIpPicker by remember { mutableStateOf(false) }
        var queryTimeout by remember { mutableStateOf(rule.queryTimeout.orEmpty()) }
        var rewriteTtl by remember { mutableStateOf(rule.rewriteTtl?.toString().orEmpty()) }
        var cacheMode by remember { mutableStateOf(rule.cacheMode.orEmpty()) }
        var removeClientSubnet by remember { mutableStateOf(rule.removeClientSubnet == true) }
        var advanced by remember { mutableStateOf(ManagedDnsRules.tuningSummary(rule).isNotEmpty()) }
        val performsQuery = operation == "evaluate" || (operation == "route" && target != "block")
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("DNS Rule") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Rule name") }, singleLine = true)
                OutlinedTextField(domains, { domains = it }, label = { Text("Domains") },
                    supportingText = { Text("One per line or comma-separated. Prefixes: full:, domain:, regexp:, keyword:") })
                OutlinedButton(onClick = { srsPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Select Geosite SRS · ${selectedSrs.size}")
                }
                if (selectedSrs.isNotEmpty()) Text(selectedSrs.sorted().joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                val missing = selectedSrs.filterNot { it in installedSrs }
                if (missing.isNotEmpty()) Text("Missing SRS: ${missing.joinToString()}. Install in Assets before use.", color = MaterialTheme.colorScheme.error)
                Text("Domain match (unprefixed entries)")
                Row {
                    FilterChip(selected = match == "suffix", onClick = { match = "suffix" }, label = { Text("Suffix") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = match == "exact", onClick = { match = "exact" }, label = { Text("Exact") })
                }
                OutlinedTextField(queries, { queries = it }, label = { Text("Query types (optional)") },
                    supportingText = { Text("A, AAAA, HTTPS, TXT, MX…") })
                if (responseFrom.isEmpty() && operation != "respond") {
                    val policy = io.nekohasekai.sagernet.fmt.LogicalRulePolicy
                    Choice("Match logic", logicalMode, policy.modes.zip(policy.labels)) { logicalMode = it }
                    if (logicalMode.isNotEmpty()) Text("Combine domains and query types. NOT negates the AND group.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Choice("Action", operation, listOf("route" to "Route / Block", "evaluate" to "Evaluate", "respond" to "Respond")) {
                    operation = it
                    if (it == "respond") logicalMode = ""
                    if (it == "evaluate") {
                        responseFrom = ""; responseCode = ""; responseIp = ""; responseCidrs = ""; responseSrs = emptySet()
                        if (target == "block") target = "dns-direct"
                    }
                }
                if (operation != "respond") {
                    val servers = ManagedDnsRules.targets.filter { operation == "route" || it.first != "block" }
                        .map { if (it.first == "dns-bootstrap" && !bootstrapAvailable) it.first to "Bootstrap DNS (not configured)" else it }
                    Choice("DNS target", target, servers) { target = it }
                    if (target == "dns-local") Text("Uses the system resolver; Android Private DNS or network settings may affect the upstream.", style = MaterialTheme.typography.bodySmall)
                    if (target == "dns-bootstrap" && !bootstrapAvailable) Text("Paused until a separate Bootstrap DNS is configured.", style = MaterialTheme.typography.bodySmall)
                }
                if (operation == "evaluate") Text("Query the server and retain its response for later rules.")
                else {
                    val refs = evaluations.map { it.id to (it.name + if (it.enabled) "" else " (disabled)") }
                    Choice("Match response", responseFrom, listOf("" to "None") + refs) {
                        responseFrom = it
                        if (it.isNotEmpty()) logicalMode = ""
                        if (it.isEmpty()) { responseCode = ""; responseIp = ""; responseCidrs = ""; responseSrs = emptySet() }
                    }
                    if (responseFrom.isNotEmpty()) {
                        Choice("Response code", responseCode, listOf("" to "Any") + ManagedDnsRules.responseCodes.map { it to it }) { responseCode = it }
                        Choice("Response IP", responseIp, listOf("" to "IP / CIDR / GeoIP filter", "any" to "Contains an IP", "private" to "Private IP")) {
                            responseIp = it
                            if (it.isNotEmpty()) { responseCidrs = ""; responseSrs = emptySet() }
                        }
                        if (responseIp.isEmpty()) {
                            OutlinedTextField(responseCidrs, { responseCidrs = it },
                                label = { Text("Response IP / CIDR (optional)") },
                                supportingText = { Text("IPv4 or IPv6, one per line or comma-separated") })
                            OutlinedButton(onClick = { geoIpPicker = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Select response GeoIP · ${responseSrs.size}")
                            }
                            if (responseSrs.isNotEmpty()) Text(responseSrs.sorted().joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                            val missingGeoIp = responseSrs.filterNot { it in installedGeoIp }
                            if (missingGeoIp.isNotEmpty()) Text("Missing GeoIP SRS: ${missingGeoIp.joinToString()}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (operation == "respond") Text("Return the selected Evaluate response. No new DNS request.")
                }
                if (performsQuery) {
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "Hide query options" else "Advanced query options")
                    }
                    if (advanced) {
                        Choice("Query timeout", queryTimeout, listOf("" to "Global default") +
                            ManagedDnsRules.queryTimeouts.map { it to it }) { queryTimeout = it }
                        OutlinedTextField(rewriteTtl, { rewriteTtl = it }, label = { Text("Rewrite TTL (seconds)") },
                            singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            supportingText = { Text("Empty: preserve server TTL. 0: advertise zero TTL.") })
                        Choice("DNS cache", cacheMode, listOf("" to "Global default", "no_optimistic" to "Disable optimistic cache",
                            "no_cache" to "Disable cache")) { cacheMode = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Remove EDNS Client Subnet")
                                Text("Suppress client subnet in outgoing DNS requests", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = removeClientSubnet, onCheckedChange = { removeClientSubnet = it })
                        }
                    }
                } else if (operation == "respond") {
                    Text("Query options belong to the referenced Evaluate rule.", style = MaterialTheme.typography.bodySmall)
                }
                (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                try {
                    val ttl = if (performsQuery && rewriteTtl.isNotBlank()) {
                        requireNotNull(rewriteTtl.trim().toLongOrNull()) { "TTL must be an integer" }
                    } else null
                    val updated = rule.copy(name = name.trim(), domains = domains, queries = queries, match = match, target = target,
                        operation = operation, responseFrom = responseFrom, responseCode = responseCode,
                        responseIp = responseIp, responseCidrs = responseCidrs,
                        queryTimeout = queryTimeout.takeIf { performsQuery && it.isNotEmpty() },
                        rewriteTtl = ttl, cacheMode = cacheMode.takeIf { performsQuery && it.isNotEmpty() },
                        removeClientSubnet = true.takeIf { performsQuery && removeClientSubnet },
                        logicalMode = logicalMode.takeIf { it.isNotEmpty() && responseFrom.isEmpty() && operation != "respond" },
                        srs = selectedSrs.sorted().takeIf { it.isNotEmpty() },
                        responseSrs = responseSrs.sorted().takeIf { responseFrom.isNotEmpty() && it.isNotEmpty() })
                    ManagedDnsRules.validate(updated); error = null; onSave(updated)
                } catch (e: Exception) { error = e.message ?: "Invalid DNS rule" }
            }) { Text("Save") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
        if (srsPicker) {
            SrsPicker("Geosite SRS", installedSrs, selectedSrs, { srsPicker = false }) {
                selectedSrs = it; srsPicker = false
            }
        }
        if (geoIpPicker) {
            SrsPicker("Response GeoIP", installedGeoIp, responseSrs, { geoIpPicker = false }) {
                responseSrs = it; geoIpPicker = false
            }
        }
    }

    @Composable private fun SrsPicker(title: String, installed: List<String>, initial: Set<String>, dismiss: () -> Unit, apply: (Set<String>) -> Unit) {
        var search by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(initial) }
        val entries = (installed + initial).distinct().sorted().filter { it.contains(search, ignoreCase = true) }
        AlertDialog(onDismissRequest = dismiss, title = { Text("$title · ${selected.size}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("Search") })
                if (entries.isEmpty()) Text("No matching installed sets. Install them in Assets.")
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(entries, key = { it }) { tag ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = tag in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + tag else selected - tag
                            })
                            Text(tag + if (tag !in installed) " (missing)" else "")
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { apply(selected) }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }

    @Composable private fun Choice(label: String, selected: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(options.firstOrNull { it.first == selected }?.second ?: "Select")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (value, title) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; change(value) })
                    }
                }
            }
        }
    }
}
