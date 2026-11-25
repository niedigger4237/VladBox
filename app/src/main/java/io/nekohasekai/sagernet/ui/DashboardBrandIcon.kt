package io.nekohasekai.sagernet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.caverock.androidsvg.SVG

private val serviceDomains = listOf(
    "vk" to listOf("vk.com", "vk.ru", "vkontakte.ru", "vkuseraudio.net", "vkuser.net"),
    "mail-ru" to listOf("mail.ru", "mycdn.me"),
    "battle-net" to listOf("battle.net", "blizzard.com", "blzstatic.cn"),
    "qobuz" to listOf("qobuz.com"),
    "rutube" to listOf("rutube.ru"),
    "messenger" to listOf("messenger.com", "m.me"),
    "youtube" to listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com"),
    "gmail" to listOf("mail.google.com", "gmail.com"),
    "google-drive" to listOf("drive.google.com", "drivefrontend-pa.clients6.google.com", "docs.google.com"),
    "google-photos" to listOf("photos.google.com"),
    "google-maps" to listOf("maps.google.com", "maps.googleapis.com"),
    "google-play" to listOf("play.google.com", "android.clients.google.com"),
    "google-gemini" to listOf("gemini.google.com"),
    "google" to listOf("google.com", "googleapis.com", "gstatic.com", "googleusercontent.com"),
    "telegram" to listOf("telegram.org", "t.me", "telegram.me"),
    "discord" to listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net"),
    "github" to listOf("github.com", "githubusercontent.com", "githubassets.com"),
    "spotify" to listOf("spotify.com", "scdn.co", "spotifycdn.com"),
    "cloudflare" to listOf("cloudflare.com", "cloudflare-dns.com", "cloudflare.net"),
    "twitch" to listOf("twitch.tv", "ttvnw.net", "jtvnw.net"),
    "netflix" to listOf("netflix.com", "nflxvideo.net", "nflximg.net"),
    "reddit" to listOf("reddit.com", "redd.it", "redditmedia.com"),
    "whatsapp" to listOf("whatsapp.com", "whatsapp.net"),
    "instagram" to listOf("instagram.com", "cdninstagram.com"),
    "steam" to listOf("steampowered.com", "steamcommunity.com", "steamstatic.com"),
    "dropbox" to listOf("dropbox.com", "dropboxusercontent.com"),
    "app-store" to listOf("apps.apple.com", "itunes.apple.com"),
    "apple" to listOf("apple.com", "icloud.com"),
    "tiktok" to listOf("tiktok.com", "tiktokcdn.com"),
    "x" to listOf("x.com", "twitter.com", "twimg.com"),
    "playstation" to listOf("playstation.com", "playstation.net"),
    "epic-games" to listOf("epicgames.com"),
    "soundcloud" to listOf("soundcloud.com", "sndcdn.com"),
    "deezer" to listOf("deezer.com", "dzcdn.net"),
    "tidal" to listOf("tidal.com"),
    "yandex" to listOf("yandex.ru", "yandex.com", "ya.ru", "yastatic.net"),
    "microsoft-onedrive" to listOf("onedrive.live.com", "onedrive.com"),
    "microsoft-outlook" to listOf("outlook.com", "outlook.office.com", "outlook.live.com"),
    "microsoft-teams" to listOf("teams.microsoft.com", "teams.live.com"),
    "microsoft" to listOf("microsoft.com", "windows.com", "microsoftonline.com"),
    "chatgpt" to listOf("chatgpt.com"),
    "openai" to listOf("openai.com", "oaistatic.com", "oaiusercontent.com"),
    "claude-icon" to listOf("claude.ai", "anthropic.com"),
    "proton-vpn" to listOf("protonvpn.com"),
    "proton-mail" to listOf("proton.me", "protonmail.com"),
    "amazon" to listOf("amazon.com", "amazonaws.com"),
    "aliexpress" to listOf("aliexpress.com", "alicdn.com"),
    "zoom" to listOf("zoom.us", "zoom.com"),
    "signal" to listOf("signal.org"),
    "viber" to listOf("viber.com"),
    "facebook" to listOf("facebook.com", "fbcdn.net"),
    "gitlab" to listOf("gitlab.com"),
    "docker" to listOf("docker.com", "docker.io"),
    "tailscale" to listOf("tailscale.com"),
    "jellyfin" to listOf("jellyfin.org"),
    "plex" to listOf("plex.tv"),
    "adguard-home" to listOf("adguard.com", "adguard-dns.io"),
    "wireguard" to listOf("wireguard.com"),
    "openvpn" to listOf("openvpn.net"),
    "nextcloud" to listOf("nextcloud.com"),
    "firefox" to listOf("mozilla.org", "firefox.com"),
    "chrome" to listOf("chrome.com", "chrome.google.com"),
    "brave" to listOf("brave.com"),
    "opera" to listOf("opera.com"),
    "bing" to listOf("bing.com"),
    "duckduckgo" to listOf("duckduckgo.com"),
    "wikipedia" to listOf("wikipedia.org", "wikimedia.org"),
    "nvidia" to listOf("nvidia.com"),
    "amd" to listOf("amd.com")
)
private val serviceBitmaps = LruCache<String, Bitmap>(80)

@Composable
internal fun DashboardBrandIcon(host: String, fallback: @Composable () -> Unit) {
    val domain = host.lowercase(java.util.Locale.ROOT).trimEnd('.')
    val asset = remember(domain) {
        serviceDomains.flatMap { (name, domains) -> domains.map { name to it } }
            .filter { (_, suffix) -> domain == suffix || domain.endsWith("." + suffix) }
            .maxByOrNull { it.second.length }?.first
    }
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, asset) {
        value = null
        if (asset != null) value = withContext(Dispatchers.IO) {
            serviceBitmaps.get(asset) ?: runCatching {
                val svg = SVG.getFromAsset(context.assets, "dashboard-icons/$asset.svg")
                val image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(Canvas(image), android.graphics.RectF(0f, 0f, 96f, 96f))
                serviceBitmaps.put(asset, image)
                image
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image == null) fallback()
    else Image(remember(image) { image.asImageBitmap() }, contentDescription = null, modifier = Modifier.size(26.dp))
}
