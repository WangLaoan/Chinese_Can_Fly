package app.ceyu

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private var ready = false
    private var password = ""
    private var avatarPerson = ""
    private var capturePending = false
    private val listener: () -> Unit = { render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.addJavascriptInterface(Bridge(), "Ceyu")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun onPageFinished(view: WebView, url: String) { ready = true; render(); handleIntent(intent) }
        }
        web.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(web)
        Session.subscribe(listener)
        web.loadUrl("file:///android_asset/index.html")
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (ready) handleIntent(intent) }
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            Session.share(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            intent.action = null
        }
        if (intent.getBooleanExtra("finishSession", false)) {
            intent.removeExtra("finishSession")
            web.evaluateJavascript("window.openFinish && window.openFinish()", null)
        }
    }
    override fun onResume() {
        super.onResume()
        if (capturePending && Settings.canDrawOverlays(this)) { capturePending = false; requestCapture() }
        render()
    }
    private fun render() {
        if (!ready || isFinishing || isDestroyed) return
        val state = Session.snapshot().put("native", true).put("overlayGranted", Settings.canDrawOverlays(this))
        web.evaluateJavascript("window.receiveState(${JSONObject.quote(state.toString())})", null)
    }
    private fun requestCapture() {
        if (Session.title.isBlank()) { Session.fail("先填写当前聊天顶部显示的完整昵称，用于防止识别到其他对话"); return }
        if (Session.capturing) { Session.inform("屏幕共享已启动；切到目标聊天后点击侧边把手"); return }
        if (!Settings.canDrawOverlays(this)) {
            capturePending = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 50)
        }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 10)
    }
    inner class Bridge {
        @JavascriptInterface fun send(action: String, payload: String) {
            if (payload.length > 200000) return
            runOnUiThread {
                try {
                    val p = JSONObject(payload)
                    when (action) {
                        "capture" -> { Session.updateDraft(p); requestCapture() }
                        "stopCapture" -> { stopService(Intent(this@MainActivity, CaptureService::class.java)); Session.cancel() }
                        "finish", "emergency" -> {
                            stopService(Intent(this@MainActivity, CaptureService::class.java))
                            if (action == "emergency") { Session.clear(); Session.publish() } else Session.cancel()
                        }
                        "copy" -> {
                            val clip = ClipData.newPlainText("侧语回复", p.optString("text").take(2000))
                            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                            Session.inform("已复制，发送前请确认内容")
                        }
                        "export", "import" -> {
                            password = p.optString("password")
                            require(password.length >= 10) { "备份口令至少 10 个字符" }
                            val intent = Intent(if (action == "export") Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                            if (action == "export") intent.putExtra(Intent.EXTRA_TITLE, "ceyu-backup.ceyu")
                            startActivityForResult(intent, if (action == "export") 20 else 21)
                        }
                        "avatar" -> {
                            avatarPerson = p.getString("id")
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 30)
                        }
                        else -> Session.action(action, p)
                    }
                } catch (e: Exception) { Session.fail(e.message ?: "操作失败") }
            }
        }
    }
    @Deprecated("Activity result API used for framework-only activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            password = ""
            if (requestCode == 10) Session.inform("未开启屏幕共享；仍可粘贴或分享文字分析")
            return
        }
        if (requestCode == 10) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", resultCode).putExtra("grant", data))
            Session.inform("已开启共享。切到目标聊天，点击侧边把手读取；向内滑展开菜单。")
            return
        }
        val uri = data.data ?: return
        if (requestCode == 30) {
            runCatching {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                    decoder.setTargetSampleSize(4); decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val thumb = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                val bytes = ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
                if (thumb !== bitmap) thumb.recycle()
                bitmap.recycle()
                Session.saveAvatar(avatarPerson, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }.onFailure { Session.fail("图片无法读取") }
            return
        }
        val pass = password; password = ""
        val archive = Session.archive().toString()
        worker.execute {
            val outcome = runCatching {
                if (requestCode == 20) {
                    val encoded = Vault.export(JSONObject(archive), pass)
                    requireNotNull(contentResolver.openOutputStream(uri)).use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
                    null
                } else {
                    val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 24 * 1024 * 1024) { "备份文件过大" }
                            output.write(buffer, 0, count)
                        }; output.toByteArray()
                    }
                    Vault.import(String(bytes, Charsets.UTF_8), pass)
                }
            }
            runOnUiThread {
                outcome.onSuccess { if (it != null) Session.importArchive(it) else Session.inform("加密备份已导出，不包含 API 密钥") }
                    .onFailure { Session.fail(if (requestCode == 21) "导入失败：口令错误或备份损坏" else "导出失败，请检查文件位置") }
            }
        }
    }
    override fun onDestroy() {
        Session.unsubscribe(listener)
        web.removeJavascriptInterface("Ceyu"); web.destroy()
        worker.shutdown()
        super.onDestroy()
    }
}
