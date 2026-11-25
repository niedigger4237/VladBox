package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.compose.*
import kotlinx.coroutines.delay

class DashboardFragment : ToolbarFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val activity = requireActivity() as MainActivity
        val model = ViewModelProvider(activity)[ConnectionViewModel::class.java]
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HubTheme {
                    val ui by model.state.collectAsState()
                    val connected = ui.serviceState.connected
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { activity.displayFragmentWithId(R.id.nav_configuration) }) {
                                Canvas(Modifier.size(24.dp)) {
                                    val c = androidx.compose.ui.graphics.Color.Gray
                                    drawLine(c, Offset(size.width*.2f,size.height*.5f), Offset(size.width*.85f,size.height*.5f), 2.dp.toPx())
                                    drawLine(c, Offset(size.width*.2f,size.height*.5f), Offset(size.width*.5f,size.height*.2f), 2.dp.toPx())
                                    drawLine(c, Offset(size.width*.2f,size.height*.5f), Offset(size.width*.5f,size.height*.8f), 2.dp.toPx())
                                }
                            }
                            Text("Dashboard", style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Connection overview", style = MaterialTheme.typography.headlineSmall)
                            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(painterResource(if (connected) R.drawable.ic_dashboard_connected else R.drawable.ic_dashboard_idle), null, tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(ui.status.ifBlank { "Not connected" }, style = MaterialTheme.typography.headlineSmall)
                                    Text(ui.mode, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("Live traffic", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                DashboardMetric("Download", ui.rx, Modifier.weight(1f))
                                DashboardMetric("Upload", ui.tx, Modifier.weight(1f))
                            }
                            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Connection test", style = MaterialTheme.typography.titleMedium)
                                    Text(ui.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(onClick = { activity.binding.stats.testConnection() }, enabled = connected) { Text("Test connection") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, modifier: Modifier) {
    OutlinedCard(modifier, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.trimStart('▲', '▼', ' ').ifBlank { "0 B/s" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
