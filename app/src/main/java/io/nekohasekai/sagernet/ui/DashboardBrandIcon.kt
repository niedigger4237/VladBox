package io.nekohasekai.sagernet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.caverock.androidsvg.SVG

private val serviceDomains = listOf(
    "pinterest" to listOf("pinterest.com", "pinimg.com"),
    "snapchat" to listOf("snapchat.com", "sc-cdn.net"),
    "threads" to listOf("threads.net", "threads.com"),
    "mega-nz" to listOf("mega.nz", "mega.io"),
    "mediafire" to listOf("mediafire.com"),
    "bandcamp" to listOf("bandcamp.com", "bcbits.com"),
    "crunchyroll" to listOf("crunchyroll.com"),
    "disney-plus" to listOf("disneyplus.com", "disney-plus.net"),
    "bluesky" to listOf("bsky.app", "bsky.social"),
    "linkedin" to listOf("linkedin.com", "licdn.com"),
    "xbox" to listOf("xbox.com", "xboxlive.com"),
    "minecraft" to listOf("minecraft.net", "mojang.com"),
    "roblox" to listOf("roblox.com", "rbxcdn.com"),
    "apple-music" to listOf("music.apple.com"),
    "youtube-music" to listOf("music.youtube.com"),
    "deepseek" to listOf("deepseek.com"),
    "grok" to listOf("grok.com", "x.ai"),
    "perplexity" to listOf("perplexity.ai"),
    "mistral-ai" to listOf("mistral.ai"),
    "microsoft-copilot" to listOf("copilot.microsoft.com", "copilot.com"),
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
    "yandex-music" to listOf("music.yandex.ru", "music.yandex.com", "music.yandex.kz", "music.yandex.by", "music.yandex.uz"),
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
private val serviceBitmaps = LruCache<String, Bitmap>(96)
private val serviceSuffixes = serviceDomains.flatMap { (name, domains) ->
    domains.map { name to it }
}.sortedByDescending { it.second.length }

private fun normalizeBrand(source: Bitmap, dark: Boolean): Bitmap? {
    val w = source.width
    val h = source.height
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    var left = w
    var top = h
    var right = -1
    var bottom = -1
    var visible = 0
    var lowContrast = 0
    for (y in 0 until h) for (x in 0 until w) {
        val p = pixels[y * w + x]
        if (android.graphics.Color.alpha(p) < 32) continue
        left = minOf(left, x)
        top = minOf(top, y)
        right = maxOf(right, x)
        bottom = maxOf(bottom, y)
        visible++
        val light = (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 + android.graphics.Color.blue(p) * 114) / 1000
        if (if (dark) light < 65 else light > 210) lowContrast++
    }
    if (visible == 0) return null
    val result = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    val needsBacking = lowContrast.toFloat() / visible > .65f
    if (needsBacking) {
        paint.color = if (dark) android.graphics.Color.WHITE else 0xFF202124.toInt()
        canvas.drawRoundRect(0f, 0f, 96f, 96f, 20f, 20f, paint)
    }
    val width = right - left + 1
    val height = bottom - top + 1
    val scale = (if (needsBacking) 76f else 92f) / maxOf(width, height)
    val dw = width * scale
    val dh = height * scale
    canvas.drawBitmap(source, android.graphics.Rect(left, top, right + 1, bottom + 1),
        android.graphics.RectF((96f-dw)/2, (96f-dh)/2, (96f+dw)/2, (96f+dh)/2), paint)
    return result
}

@Composable
internal fun DashboardBrandIcon(host: String, fallback: @Composable () -> Unit) {
    val domain = host.lowercase(java.util.Locale.ROOT).trimEnd('.')
    val asset = remember(domain) {
        serviceSuffixes.firstOrNull { (_, suffix) -> domain == suffix || domain.endsWith("." + suffix) }?.first
    }
    val context = LocalContext.current.applicationContext
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer.let { it.luminance() < .4f }
    val bitmap by produceState<Bitmap?>(null, asset, dark) {
        value = null
        if (asset != null) value = withContext(Dispatchers.IO) {
            val cacheKey = "$asset:$dark"
            serviceBitmaps.get(cacheKey) ?: runCatching {
                val svg = SVG.getFromAsset(context.assets, "dashboard-icons/$asset.svg")
                val image = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(Canvas(image), android.graphics.RectF(0f, 0f, 192f, 192f))
                normalizeBrand(image, dark)?.also { serviceBitmaps.put(cacheKey, it) }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image == null) fallback()
    else Image(remember(image) { image.asImageBitmap() }, contentDescription = null, modifier = Modifier.size(26.dp))
}
