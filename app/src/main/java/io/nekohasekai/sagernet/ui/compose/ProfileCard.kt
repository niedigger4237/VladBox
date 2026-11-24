package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R

@Composable
fun ProfileCard(
    name: String,
    protocol: String,
    address: String,
    status: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    traffic: String = "",
    actionsVisible: Boolean = true,
    editEnabled: Boolean = true,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onStatus: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) lerp(colors.surface, colors.primary, 0.20f) else lerp(colors.surface, colors.onSurface, 0.07f))
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (selected) Icon(painterResource(R.drawable.ic_profile_selected), null,
                    Modifier.size(24.dp), tint = colors.primary)
            }
            if (address.isNotEmpty() || traffic.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = colors.onSurface)
                    if (traffic.isNotEmpty()) Text(traffic, fontSize = 11.sp,
                        color = colors.onSurface)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(protocol, Modifier.background(colors.secondaryContainer, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 12.sp, color = colors.onSecondaryContainer)
                if (status.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(status, Modifier.weight(1f).then(if (onStatus != null) Modifier.clickable(onClick = onStatus) else Modifier),
                        fontSize = 12.sp, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (status.isEmpty()) Spacer(Modifier.weight(1f))
                if (actionsVisible) {
                    IconButton(onClick = onEdit, enabled = editEnabled, modifier = Modifier.size(44.dp)) {
                        Icon(painterResource(R.drawable.ic_image_edit), androidx.compose.ui.res.stringResource(R.string.edit), Modifier.size(22.dp), tint = if (editEnabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.38f))
                    }
                    IconButton(onClick = onMore, modifier = Modifier.size(44.dp)) {
                        Icon(painterResource(R.drawable.ic_profile_overflow), androidx.compose.ui.res.stringResource(R.string.share), Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun LightProfilePreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        ProfileCard("OPLUS_CH_450", "AmneziaWG", "192.0.2.10:51820", "24 ms", true, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DarkProfilePreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        ProfileCard("OPLUS_CH_450", "AmneziaWG", "192.0.2.10:51820", "24 ms", true, {}, {}, {})
    }
}
