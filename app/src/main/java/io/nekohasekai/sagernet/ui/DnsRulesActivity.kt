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
import io.nekohasekai.sagernet.fmt.ManagedDnsRules
import io.nekohasekai.sagernet.fmt.ManagedDnsRules.Rule
import io.nekohasekai.sagernet.ui.compose.HubTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsRulesActivity : ThemedActivity() {
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
                    withContext(Dispatchers.Main) {
                        source = json; profileId = id; profileName = name
                        rules = parsed; modern = modernMode; remote = useRemote; ready = true
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
                    source = json; rules = next; editor = null; deleting = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Unable to save DNS rules" }
                finally { busy = false }
            }
        }
        val activeIds = remember(rules, remote) {
            ManagedDnsRules.effective(rules, remote).map { it.id }.toSet()
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
                        if (ready && rules.isEmpty()) Text("No explicit rules. Automatic DNS routing is unchanged.")
                    }
                    itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                        Text(rule.domains.ifBlank { "All domains" }, style = MaterialTheme.typography.bodySmall)
                                        val actionLabel = when (ManagedDnsRules.operation(rule)) {
                                            "evaluate" -> "Evaluate · ${rule.target}"
                                            "respond" -> "Respond"
                                            else -> rule.target
                                        }
                                        Text(actionLabel + if (rule.queries.isBlank()) "" else " · " + rule.queries)
                                        rules.firstOrNull { it.id == ManagedDnsRules.responseId(rule) }?.let {
                                            Text("Match response: ${it.name}", style = MaterialTheme.typography.bodySmall)
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
        editor?.let { original ->
            key(original.id) {
                val index = rules.indexOfFirst { it.id == original.id }
                val priorEvaluations = (if (index < 0) rules else rules.take(index))
                    .filter { ManagedDnsRules.operation(it) == "evaluate" }
                RuleDialog(original, priorEvaluations, busy, error, onDismiss = { if (!busy) { editor = null; error = null } }) { updated ->
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

    @Composable private fun RuleDialog(rule: Rule, evaluations: List<Rule>, busy: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
        var name by remember { mutableStateOf(rule.name) }
        var domains by remember { mutableStateOf(rule.domains) }
        var queries by remember { mutableStateOf(rule.queries) }
        var match by remember { mutableStateOf(rule.match) }
        var target by remember { mutableStateOf(rule.target) }
        var operation by remember { mutableStateOf(ManagedDnsRules.operation(rule)) }
        var responseFrom by remember { mutableStateOf(ManagedDnsRules.responseId(rule)) }
        var responseCode by remember { mutableStateOf(rule.responseCode.orEmpty()) }
        var responseIp by remember { mutableStateOf(rule.responseIp.orEmpty()) }
        var responseCidrs by remember { mutableStateOf(rule.responseCidrs.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("DNS Rule") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Rule name") }, singleLine = true)
                OutlinedTextField(domains, { domains = it }, label = { Text("Domains") },
                    supportingText = { Text("One domain per line or comma-separated") })
                Text("Domain match")
                Row {
                    FilterChip(selected = match == "suffix", onClick = { match = "suffix" }, label = { Text("Suffix") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = match == "exact", onClick = { match = "exact" }, label = { Text("Exact") })
                }
                OutlinedTextField(queries, { queries = it }, label = { Text("Query types (optional)") },
                    supportingText = { Text("A, AAAA, HTTPS, TXT, MX…") })
                Choice("Action", operation, listOf("route" to "Route / Block", "evaluate" to "Evaluate", "respond" to "Respond")) {
                    operation = it
                    if (it == "evaluate") {
                        responseFrom = ""; responseCode = ""; responseIp = ""; responseCidrs = ""
                        if (target == "block") target = "dns-direct"
                    }
                }
                if (operation != "respond") {
                    val servers = listOf("dns-direct" to "Direct DNS", "dns-remote" to "Remote DNS")
                    Choice("DNS target", target, if (operation == "route") servers + ("block" to "Block (NXDOMAIN)") else servers) { target = it }
                }
                if (operation == "evaluate") Text("Query the server and retain its response for later rules.")
                else {
                    val refs = evaluations.map { it.id to (it.name + if (it.enabled) "" else " (disabled)") }
                    Choice("Match response", responseFrom, listOf("" to "None") + refs) {
                        responseFrom = it
                        if (it.isEmpty()) { responseCode = ""; responseIp = ""; responseCidrs = "" }
                    }
                    if (responseFrom.isNotEmpty()) {
                        Choice("Response code", responseCode, listOf("" to "Any") + ManagedDnsRules.responseCodes.map { it to it }) { responseCode = it }
                        Choice("Response IP", responseIp, listOf("" to "Any / CIDR filter", "any" to "Contains an IP", "private" to "Private IP")) {
                            responseIp = it
                            if (it.isNotEmpty()) responseCidrs = ""
                        }
                        if (responseIp.isEmpty()) OutlinedTextField(responseCidrs, { responseCidrs = it },
                            label = { Text("Response IP / CIDR (optional)") },
                            supportingText = { Text("IPv4 or IPv6, one per line or comma-separated") })
                    }
                    if (operation == "respond") Text("Return the selected Evaluate response. No new DNS request.")
                }
                (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val updated = rule.copy(name = name.trim(), domains = domains, queries = queries, match = match, target = target,
                    operation = operation, responseFrom = responseFrom, responseCode = responseCode,
                    responseIp = responseIp, responseCidrs = responseCidrs)
                try { ManagedDnsRules.validate(updated); error = null; onSave(updated) }
                catch (e: Exception) { error = e.message ?: "Invalid DNS rule" }
            }) { Text("Save") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
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
