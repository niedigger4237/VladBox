package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.database.DataStore
import org.json.JSONObject

@Composable
fun CustomConfigProfileScreen(onSave: () -> Unit, onClose: () -> Unit, menuItems: List<Pair<String, () -> Unit>>) {
    var name by rememberSaveable { mutableStateOf(DataStore.profileName) }
    var outbound by rememberSaveable { mutableStateOf(DataStore.profileCacheStore.getBoolean("isOutboundOnly",false)) }
    var json by rememberSaveable { mutableStateOf(DataStore.serverConfig) }
    val parsed = remember(json) { runCatching { JSONObject(json) } }
    val error = when {
        json.isBlank() -> "Enter a JSON object."
        parsed.isFailure -> parsed.exceptionOrNull()?.message ?: "Invalid JSON"
        outbound && parsed.getOrNull()?.optString("type").isNullOrBlank() -> "Outbound requires a type."
        else -> null
    }
    val undo = remember { mutableStateListOf<String>() }
    val redo = remember { mutableStateListOf<String>() }
    fun update(value: String, record: Boolean = true) {
        if (value == json) return
        if (record) { undo.add(json); if (undo.size > 50) undo.removeAt(0); redo.clear() }
        json = value
        DataStore.serverConfig = value
    }
    val colors = MaterialTheme.colorScheme
    val highlight = remember(colors) {
        VisualTransformation { input ->
            val result = AnnotatedString.Builder(input.text)
            Regex(""""(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?|\b(?:true|false|null)\b""").findAll(input.text).forEach { token ->
                val color = if (token.value.startsWith("\"")) colors.primary else colors.tertiary
                result.addStyle(SpanStyle(color = color),token.range.first,token.range.last+1)
            }
            TransformedText(result.toAnnotatedString(),OffsetMapping.Identity)
        }
    }
    Column(Modifier.fillMaxSize()) {
        VladTopAppBar(title = "Custom Config",onNavigation = onClose,onBack = onClose,extraActions = {
            TextButton(onClick = onSave,enabled = error == null) { Text("Save") }
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) { Text("⋮") }
                DropdownMenu(expanded,{ expanded = false }) { menuItems.forEach { (label,action) -> DropdownMenuItem(text = { Text(label) },onClick = { expanded = false; action() }) } }
            }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SimpleField("Profile name",name) { name = it; DataStore.profileName = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !outbound,onClick = { outbound = false; DataStore.profileCacheStore.putBoolean("isOutboundOnly",false) },label = { Text("Full config") })
                FilterChip(selected = outbound,onClick = { outbound = true; DataStore.profileCacheStore.putBoolean("isOutboundOnly",true) },label = { Text("Outbound only") })
            }
            Text(if (outbound) "Single sing-box outbound object." else "Complete sing-box configuration.",style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { if (undo.isNotEmpty()) { redo.add(json); update(undo.removeAt(undo.lastIndex),false) } },enabled = undo.isNotEmpty()) { Text("Undo") }
                TextButton(onClick = { if (redo.isNotEmpty()) { undo.add(json); update(redo.removeAt(redo.lastIndex),false) } },enabled = redo.isNotEmpty()) { Text("Redo") }
                TextButton(onClick = { parsed.getOrNull()?.let { update(it.toString(2)) } },enabled = parsed.isSuccess) { Text("Format") }
            }
            Card(Modifier.fillMaxWidth()) {
                BasicTextField(value = json,onValueChange = { update(it) },modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(12.dp),textStyle = TextStyle(color = colors.onSurface,fontFamily = FontFamily.Monospace,fontSize = 13.sp),cursorBrush = SolidColor(colors.primary),visualTransformation = highlight,keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            }
            Text(error ?: "JSON object valid. Runtime compatibility is not checked.",color = if (error != null) colors.error else colors.primary,style = MaterialTheme.typography.bodySmall)
            Text("${json.lines().size} lines · ${json.length} characters",style = MaterialTheme.typography.bodySmall)
            Button(onClick = onSave,enabled = error == null,modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
        }
    }
}
