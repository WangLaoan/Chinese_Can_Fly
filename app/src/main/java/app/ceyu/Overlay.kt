package app.ceyu

import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONObject

class Overlay(private val context: Context, private val capture: () -> Unit, private val finish: () -> Unit, private val emergency: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val voice = EarVoice(context)
    private val handle = View(context)
    private val params = WindowManager.LayoutParams(dp(28), dp(42), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE, android.graphics.PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.RIGHT
    }
    private var panel: View? = null
    private var tip: TextView? = null
    private var lastResult: JSONObject? = null
    private var pinned = false
    private var closed = false
    private var lastUp = 0L
    private val singleTap = Runnable { if (!closed) capture() }
    private val hideTip = Runnable { if (!pinned) removeTip() }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun bg(color: String, radius: Float = 6f) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(radius.toInt()).toFloat() }
    fun show() {
        handle.background = android.graphics.drawable.InsetDrawable(bg("#99566A62", 3f), dp(14), dp(5), dp(4), dp(5))
        handle.contentDescription = "侧语：单击读取，向内滑动展开"
        reposition()
        wm.addView(handle, params)
        var startX = 0f; var startY = 0f; var baseY = 0; var downAt = 0L; var ended = false
        var movedHorizontally = false; var movedVertically = false
        val longExit = Runnable { ended = true; emergency() }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; baseY = params.y; downAt = android.os.SystemClock.uptimeMillis(); ended = false
                    movedHorizontally = false; movedVertically = false
                    handler.postDelayed(longExit, 3000)
                }
                MotionEvent.ACTION_MOVE -> {
                    movedHorizontally = movedHorizontally || startX - event.rawX > dp(35)
                    movedVertically = movedVertically || kotlin.math.abs(event.rawY - startY) > dp(10)
                    if (movedVertically && !movedHorizontally) {
                        handler.removeCallbacks(longExit)
                        params.y = (baseY.toFloat() + event.rawY - startY).toInt().coerceIn(dp(40), wm.currentWindowMetrics.bounds.height() - dp(90))
                        runCatching { wm.updateViewLayout(handle, params) }
                    }
                    if (movedHorizontally) handler.removeCallbacks(longExit)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longExit)
                    val elapsed = android.os.SystemClock.uptimeMillis() - downAt
                    if (!ended) {
                        when {
                            movedHorizontally -> openPanel()
                            movedVertically -> Session.action("settings", JSONObject().put("handleY", params.y.toDouble() / wm.currentWindowMetrics.bounds.height()))
                            elapsed >= 1000 -> Session.setSilent(false)
                            android.os.SystemClock.uptimeMillis() - lastUp < 320 -> { handler.removeCallbacks(singleTap); Session.setSilent(true); lastUp = 0 }
                            else -> { lastUp = android.os.SystemClock.uptimeMillis(); handler.postDelayed(singleTap, 320) }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longExit)
            }; true
        }
    }
    fun reposition() {
        params.y = (wm.currentWindowMetrics.bounds.height() * Session.settings().optDouble("handleY", 0.82)).toInt()
        if (handle.isAttachedToWindow) runCatching { wm.updateViewLayout(handle, params) }
    }
    fun refresh() {
        if (closed) return
        if (Session.silent || Session.paused) { hideOutput(); voice.stop(); lastResult = Session.result; return }
        if (lastResult !== Session.result && Session.result != null) {
            lastResult = Session.result
            val result = Session.result!!
            val channel = Session.settings().optString("channel", "text")
            if (channel != "ear") showTip(result.optString("action"))
            if (channel != "text") voice.speak(result.optString("action"))
        }
    }
    private fun showTip(value: String) {
        removeTip()
        tip = TextView(context).apply {
            text = value.take(16); textSize = 13f; setTextColor(Color.WHITE); background = bg("#DE35413C")
            setPadding(dp(12), dp(9), dp(12), dp(9)); maxWidth = dp(230)
            setOnClickListener { pinned = !pinned; if (!pinned) removeTip() }
            setOnLongClickListener { openPanel(); true }
        }
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.RIGHT or Gravity.TOP; x = dp(24); y = params.y - dp(8) }
        runCatching { wm.addView(tip, p) }
        handler.postDelayed(hideTip, Session.settings().optInt("duration", 3) * 1000L)
    }
    private fun removeTip() { handler.removeCallbacks(hideTip); tip?.let { runCatching { wm.removeView(it) } }; tip = null; pinned = false }
    fun hideOutput() { removeTip(); panel?.let { runCatching { wm.removeView(it) } }; panel = null; voice.stop() }
    private fun openPanel() {
        hideOutput()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(16)); background = bg("#FCFEFD") }
        fun label(text: String, size: Float = 14f) { list.addView(TextView(context).apply { this.text = text; textSize = size; setTextColor(Color.parseColor("#293B34")); setPadding(0, dp(8), 0, dp(8)) }) }
        fun action(text: String, callback: () -> Unit) {
            list.addView(Button(context).apply {
                this.text = text; textSize = 13f; isAllCaps = false; minHeight = dp(44)
                setOnClickListener { callback() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        label("侧语 · " + if (Session.paused) "已暂停" else if (Session.silent) "已静默" else "线上会话", 17f)
        val titleInput = EditText(context).apply {
            hint = "聊天窗口顶部显示的昵称"
            setText(Session.title)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        list.addView(titleInput, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        val contextInput = EditText(context).apply {
            hint = "需要时补充几句上下文（可留空）"
            setText(Session.background)
            textSize = 14f
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        list.addView(contextInput, LinearLayout.LayoutParams(-1, dp(92)).apply { bottomMargin = dp(4) })
        action("保存对象与上下文") {
            Session.action("draft", JSONObject().put("title", titleInput.text.toString()).put("background", contextInput.text.toString()))
            Session.inform("对象和上下文已暂存")
            hideOutput()
            openPanel()
        }
        if (!Session.silent) {
            if (Session.busy) label("正在分析…")
            if (Session.error.isNotBlank()) label(Session.error)
            Session.result?.let { r ->
                label(r.optString("action"), 19f); label(r.optString("interpretation"))
                val replies = r.optJSONArray("replies")
                if (replies != null) for (i in 0 until replies.length()) {
                    val reply = replies.getJSONObject(i)
                    label(reply.optString("text"))
                    action("复制 · " + reply.optString("style")) {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("侧语回复", reply.optString("text")))
                        hideOutput()
                    }
                }
            }
        }
        action("读取当前聊天") { hideOutput(); capture() }
        action(if (Session.paused) "恢复分析" else "暂停分析") { Session.togglePause(); openPanel() }
        action(if (Session.silent) "恢复提示" else "静默提示") { Session.toggleSilent(); hideOutput() }
        action("背景、通道与档案") { hideOutput(); context.startActivity(Intent(context, MainActivity::class.java).putExtra("openSettings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        action("结束并选择保存") { finish() }
        action("关闭") { hideOutput() }
        panel = ScrollView(context).apply { addView(list) }
        val bounds = wm.currentWindowMetrics.bounds
        val p = WindowManager.LayoutParams(minOf(dp(320), bounds.width() - dp(36)), minOf(dp(580), bounds.height() - dp(120)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_SECURE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; x = dp(16) }
        runCatching { wm.addView(panel, p) }
    }
    fun close() { closed = true; handler.removeCallbacksAndMessages(null); hideOutput(); voice.close(); runCatching { wm.removeView(handle) } }
}
