package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** Раздел «Cloudflare WARP» — генератор конфигов (регистрация напрямую, без прокси). */
class WarpFragment : WarpGenFragment("warp.html", R.string.menu_warp)

/** Раздел «Proton VPN» — конвертер WireGuard-конфигов Proton в AmneziaWG и др. */
class ProtonFragment : WarpGenFragment("proton.html", R.string.menu_proton)

abstract class WarpGenFragment(private val page: String, private val titleRes: Int) :
    ToolbarFragment(R.layout.layout_webview) {

    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingSave: Pair<Int, List<Pair<String, String>>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        chooser?.onReceiveValue(it.toTypedArray()); chooser = null
    }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val p = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult done(p.first, true, "0")
        runOnDefaultDispatcher {
            val ok = runCatching {
                requireContext().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(p.second[0].second.toByteArray()) }
            }.isSuccess
            done(p.first, ok, if (ok) "1" else "Не удалось записать файл")
        }
    }
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val p = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult done(p.first, true, "0")
        runOnDefaultDispatcher {
            val ok = runCatching {
                val dir = DocumentFile.fromTreeUri(requireContext(), uri)!!
                for ((name, text) in p.second) {
                    val f = dir.createFile("text/plain", name) ?: error("create")
                    requireContext().contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
            }.isSuccess
            done(p.first, ok, if (ok) "${p.second.size}" else "Не удалось записать файлы")
        }
    }

    private val http by lazy {
        // Прямое соединение: никаких прокси, только api.cloudflareclient.com
        OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    private fun done(id: Int, ok: Boolean, payload: String) {
        val q = JSONArray().put(payload).toString().let { it.substring(1, it.length - 1) } // JSON-строка
        web.post { web.evaluateJavascript("window.__wbDone($id,$ok,$q)", null) }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(titleRes)
        web = LayoutWebviewBinding.bind(view).webview
        web.setBackgroundColor(0xFF0A0A0F.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView?, cb: ValueCallback<Array<Uri>>, p: FileChooserParams?
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = cb
                pickFiles.launch("*/*")
                return true
            }
        }
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/warpgen/$page")
    }

    override fun onDestroyView() {
        chooser?.onReceiveValue(null); chooser = null
        web.removeJavascriptInterface("Android")
        super.onDestroyView()
    }

    inner class Bridge {
        @JavascriptInterface
        fun request(id: Int, method: String, endpoint: String, body: String, token: String) {
            runOnDefaultDispatcher {
                try {
                    val rb = Request.Builder()
                        .url("https://api.cloudflareclient.com/v0i1909051800/$endpoint")
                        .header("User-Agent", "CloudflareWarp/1.0")
                        .method(method, if (method == "GET") null else
                            (body.ifEmpty { "{}" }).toRequestBody("application/json".toMediaType()))
                    if (token.isNotEmpty()) rb.header("Authorization", "Bearer $token")
                    http.newCall(rb.build()).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        if (r.isSuccessful) done(id, true, text)
                        else done(id, false, "HTTP ${r.code}: ${text.take(160)}")
                    }
                } catch (e: Exception) {
                    done(id, false, "Failed to fetch: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("config", text))
        }

        @JavascriptInterface
        fun saveFiles(id: Int, json: String) {
            val arr = JSONArray(json)
            val files = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("fileName") to o.getString("text") } }
            if (files.isEmpty()) return done(id, true, "0")
            web.post {
                pendingSave = id to files
                if (files.size == 1) createDoc.launch(files[0].first) else openTree.launch(null)
            }
        }

        @JavascriptInterface
        fun importConfigs(json: String) {
            val arr = JSONArray(json)
            runOnDefaultDispatcher {
                var count = 0
                var error: String? = null
                val targetId = DataStore.selectedGroupForImport()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    try {
                        val name = o.getString("fileName").substringBeforeLast('.')
                        for (bean in RawUpdater.parseWireGuard(o.getString("text"))) {
                            bean.name = name
                            ProfileManager.createProfile(targetId, bean)
                            count++
                        }
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
                onMainDispatcher {
                    DataStore.editingGroup = targetId
                    val msg = if (count > 0) getString(R.string.warpgen_imported, count) else "Ошибка импорта: $error"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
