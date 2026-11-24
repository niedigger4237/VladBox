package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService

@Composable
fun ConnectionPower(state: BaseService.State, onClick: () -> Unit) {
    val busy = state == BaseService.State.Connecting || state == BaseService.State.Stopping
    val connected = state == BaseService.State.Connected
    val colors = MaterialTheme.colorScheme
    val tint = when {
        connected -> Color(0xFFA5D5B1)
        busy -> Color(0xFFF9A825)
        else -> colors.primary
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = state.canStop || state == BaseService.State.Stopped,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = tint,
            contentColor = if (busy || connected) Color.Black else colors.onPrimary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(when {
                    connected -> R.drawable.ic_service_stop
                    busy -> R.drawable.ic_service_wait
                    else -> R.drawable.ic_service_play
                }), stringResource(if (state.canStop) R.string.stop else R.string.connect), Modifier.size(30.dp))
            }
        }
    }
}
