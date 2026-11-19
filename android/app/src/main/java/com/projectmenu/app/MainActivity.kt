package com.projectmenu.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.FileInputStream

class MainActivity : ComponentActivity() {

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST/"
    }

    private lateinit var webView: WebView
    private lateinit var storage: Storage

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingSave: PendingSave? = null

    private class PendingSave(val id: String, val text: String)

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback
        fileCallback = null
        val data = res.data
        val uris = mutableListOf<Uri>()
        if (res.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        cb?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingPermission
        pendingPermission = null
        if (req != null) {
            if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
        }
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val save = pendingSave
        pendingSave = null
        if (save == null) return@registerForActivityResult
        val uri = res.data?.data
        if (res.resultCode != RESULT_OK || uri == null) {
            notifySave(save.id, false)
            return@registerForActivityResult
        }
        Thread {
            val ok = try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(save.text.toByteArray(Charsets.UTF_8)) } != null
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Could not save file", Toast.LENGTH_LONG).show()
                notifySave(save.id, ok)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(filesDir)

        // Edge-to-edge; insets are applied as padding to the container below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this)
        val statusScrim = View(this)
        val container = FrameLayout(this)
        webView = WebView(this)
        container.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(statusScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        setContentView(root)
        applyColors(root, statusScrim)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            container.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            statusScrim.layoutParams = (statusScrim.layoutParams as FrameLayout.LayoutParams).also { it.height = bars.top }
            statusScrim.requestLayout()
            WindowInsetsCompat.CONSUMED
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true // needed so picked content:// files can be read by <input type=file>
            setSupportMultipleWindows(false)
            setSupportZoom(false)
        }
        webView.addJavascriptInterface(Bridge(), "PMAndroid")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(function(){try{return !!(window.pmHandleBack && window.pmHandleBack());}catch(e){return false;}})()") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })

        // Rotation/resize is handled via configChanges, so this only runs on a fresh
        // start or after a system theme change (which must recreate so the WebView
        // picks up the new DayNight theme). All data is persisted natively.
        webView.loadUrl(ORIGIN)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Match the page palette: header surface behind the status bar, page bg elsewhere. */
    private fun applyColors(root: View, statusScrim: View) {
        val night = isNight()
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.page_bg))
        statusScrim.setBackgroundColor(ContextCompat.getColor(this, R.color.page_surface))
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.page_bg))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    private fun notifySave(id: String, ok: Boolean) {
        val safeId = id.replace(Regex("[^0-9A-Za-z_-]"), "")
        webView.evaluateJavascript("window.__pmSaveDone && window.__pmSaveDone('$safeId', $ok)", null)
    }

    // ---------------------------------------------------------------- bridge

    inner class Bridge {
        @android.webkit.JavascriptInterface fun getData(): String = storage.getData()
        @android.webkit.JavascriptInterface fun putData(text: String?): String = storage.putData(text ?: "")
        @android.webkit.JavascriptInterface fun uploadBegin(name: String?): String = storage.uploadBegin(name ?: "file")
        @android.webkit.JavascriptInterface fun uploadChunk(id: String?, b64: String?): Boolean =
            id != null && storage.uploadChunk(id, b64 ?: "")
        @android.webkit.JavascriptInterface fun uploadEnd(id: String?): String = storage.uploadEnd(id ?: "")
        @android.webkit.JavascriptInterface fun uploadAbort(id: String?) { if (id != null) storage.uploadAbort(id) }
        @android.webkit.JavascriptInterface fun deleteMedia(name: String?): String = storage.deleteMedia(name ?: "")

        @android.webkit.JavascriptInterface
        fun saveTextFile(id: String?, filename: String?, mime: String?, text: String?) {
            val sid = id ?: return
            runOnUiThread {
                pendingSave?.let { notifySave(it.id, false) }
                pendingSave = PendingSave(sid, text ?: "")
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mime.isNullOrBlank()) "text/plain" else mime
                    putExtra(Intent.EXTRA_TITLE, filename ?: "export.txt")
                }
                try {
                    createDocument.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    pendingSave = null
                    notifySave(sid, false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- WebViewClient

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.host == HOST) {
                val path = uri.path ?: "/"
                if (path.startsWith("/media/")) {
                    openMediaExternally(path.removePrefix("/media/"))
                    return true
                }
                return false
            }
            openExternal(uri)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (uri.host != HOST) return null // external images/links load normally
            val path = uri.path ?: "/"
            return try {
                when {
                    path == "/" || path == "/index.html" -> indexResponse()
                    path == "/pm-android.js" -> assetResponse("pm-android.js", "application/javascript")
                    path.startsWith("/media/") -> mediaResponse(path.removePrefix("/media/"), request.requestHeaders)
                    else -> textResponse(404, "Not Found", "not found")
                }
            } catch (e: Exception) {
                textResponse(500, "Internal Server Error", e.message ?: "error")
            }
        }
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "mailto" && scheme != "tel") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMediaExternally(name: String) {
        val f = storage.mediaFile(name) ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeFor(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun baseHeaders() = mutableMapOf("Cache-Control" to "no-cache")

    private fun textResponse(code: Int, reason: String, text: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, baseHeaders(), ByteArrayInputStream(text.toByteArray()))

    private fun indexResponse(): WebResourceResponse {
        val html = assets.open("index.html").use { it.readBytes().toString(Charsets.UTF_8) }
        val tag = "<script src=\"pm-android.js\"></script>"
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val out = if (m != null) html.substring(0, m.range.last + 1) + tag + html.substring(m.range.last + 1) else tag + html
        return WebResourceResponse("text/html", "utf-8", 200, "OK", baseHeaders(), ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
    }

    private fun assetResponse(name: String, mime: String): WebResourceResponse =
        WebResourceResponse(mime, "utf-8", 200, "OK", baseHeaders(), assets.open(name))

    private fun mediaResponse(name: String, reqHeaders: Map<String, String>): WebResourceResponse {
        val f = storage.mediaFile(name) ?: return textResponse(404, "Not Found", "not found")
        val size = f.length()
        val mime = mimeFor(f.name)
        val range = reqHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        var start = 0L
        var end = size - 1
        var status = 200
        if (range != null) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                } else if (b.isNotEmpty()) {
                    start = maxOf(0L, size - b.toLong())
                }
                if (start > end || start >= size) {
                    val h = baseHeaders().apply { put("Content-Range", "bytes */$size") }
                    return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", h, ByteArrayInputStream(ByteArray(0)))
                }
                status = 206
            }
        }
        val length = end - start + 1
        val headers = baseHeaders().apply {
            put("Accept-Ranges", "bytes")
            put("Content-Length", length.toString())
            if (status == 206) put("Content-Range", "bytes $start-$end/$size")
        }
        val input = FileInputStream(f)
        var skipped = 0L
        while (skipped < start) {
            val n = input.skip(start - skipped)
            if (n <= 0) break
            skipped += n
        }
        return WebResourceResponse(mime, null, status, if (status == 206) "Partial Content" else "OK", headers, LimitedInputStream(input, length))
    }

    private class LimitedInputStream(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = super.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }

        override fun available(): Int = minOf(super.available().toLong(), left).toInt()
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "webm" -> "audio/webm"
            "m4a" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    // ---------------------------------------------------------------- WebChromeClient

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val mimes = linkedSetOf<String>()
            var any = false
            for (raw in params.acceptTypes.flatMap { it.split(',') }) {
                val t = raw.trim().lowercase()
                if (t.isEmpty()) continue
                if (t.startsWith(".")) {
                    val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.substring(1))
                    if (m != null) mimes.add(m) else any = true
                    // Files saved as JSON are often reported as text or binary by providers
                    if (t == ".json") { mimes.add("text/plain"); mimes.add("application/octet-stream") }
                } else if (t.contains('/')) {
                    mimes.add(t)
                }
            }
            if (mimes.contains("application/json")) { mimes.add("text/plain"); mimes.add("application/octet-stream") }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                if (!any && mimes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                fileChooser.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                callback.onReceiveValue(null)
                true
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio || request.origin.host != HOST) {
                    request.deny()
                    return@runOnUiThread
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingPermission?.deny()
                    pendingPermission = request
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermission === request) pendingPermission = null
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            val input = EditText(this@MainActivity).apply {
                setText(defaultValue ?: "")
                setSingleLine()
                selectAll()
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(this@MainActivity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }
}
