package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService

@Composable
fun ConnectionPower(state: BaseService.State, onClick: () -> Unit) =
    ConnectionPower(state, onClick, tonalRing = false)

@Composable
fun ConnectionPower(state: BaseService.State, onClick: () -> Unit, tonalRing: Boolean) {
    val busy = state == BaseService.State.Connecting || state == BaseService.State.Stopping
    val connected = state == BaseService.State.Connected
    val waiting = state == BaseService.State.Waiting
    val colors = MaterialTheme.colorScheme
    val tint = when {
        tonalRing && connected -> colors.tertiary
        tonalRing && (busy || waiting) -> colors.secondary
        tonalRing -> colors.primary
        connected -> Color(0xFFA5D5B1)
        busy || waiting -> Color(0xFFF9A825)
        else -> colors.primary
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (tonalRing) Box(Modifier.fillMaxSize().background(tint.copy(alpha = .09f), CircleShape).border(1.dp, tint.copy(alpha = .25f), CircleShape))
        Surface(
            onClick = onClick,
            enabled = state.canStop || state == BaseService.State.Stopped,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = if (tonalRing) tint.copy(alpha = .15f) else tint,
            border = if (tonalRing) BorderStroke(2.dp, Brush.sweepGradient(listOf(tint, colors.secondary, colors.tertiary, tint))) else null,
            contentColor = if (tonalRing) tint else if (busy || connected) Color.Black else colors.onPrimary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(when {
                    (connected && !tonalRing) || waiting -> R.drawable.ic_service_stop
                    busy -> R.drawable.ic_service_wait
                    else -> R.drawable.ic_service_play
                }), stringResource(if (state.canStop) R.string.stop else R.string.connect), Modifier.size(30.dp))
            }
        }
    }
}
