package app.ceyu

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager

class CaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: Overlay? = null
    private var wanted = false
    private var recognizing = false
    private var alive = true
    private var width = 0
    private var height = 0
    private var captureEpoch = 0
    private val listener: () -> Unit = {
        if (Session.paused) { wanted = false; captureEpoch++ }
        overlay?.refresh()
        getSystemService(NotificationManager::class.java).notify(71, notification())
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { stopSelf(); Session.cancel() }
    }
    override fun onCreate() {
        super.onCreate(); Session.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture", "屏幕共享状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示共享状态和停止操作，不显示聊天内容"; setShowBadge(false); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        Session.subscribe(listener)
    }
    private fun notification(): Notification {
        val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_ceyu).setContentTitle("侧语 · 屏幕共享已开启")
            .setContentText(if (Session.paused) "分析已暂停" else if (Session.silent) "建议已静默 · 按需读取" else "按需读取 · 点击停止可结束共享")
            .setContentIntent(home).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, if (Session.paused) "恢复分析" else "暂停分析", pause).build())
            .addAction(Notification.Action.Builder(null, "停止共享", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> { finish(); return START_NOT_STICKY }
            "pause" -> { Session.togglePause(); return START_NOT_STICKY }
        }
        if (projection != null) return START_NOT_STICKY
        try {
            val grant = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("grant", Intent::class.java)
                else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("grant")
            requireNotNull(grant) { "屏幕授权已失效，请重新开始" }
            startForeground(71, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent!!.getIntExtra("code", Activity.RESULT_CANCELED), grant)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf(); Session.cancel() }
                override fun onCapturedContentResize(w: Int, h: Int) { if (w > 0 && h > 0 && (w != width || h != height)) resize(w, h) }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    if (!isVisible) { wanted = false; captureEpoch++; Session.cancel() }
                }
            }, handler)
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            resize(bounds.width(), bounds.height())
            Session.capturing = true; Session.publish()
            overlay = Overlay(this, ::requestFrame, ::finish, ::emergency).also { it.show() }
        } catch (e: Exception) { Session.fail(e.message ?: "屏幕共享启动失败"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun resize(w: Int, h: Int) {
        width = w; height = h; captureEpoch++; wanted = false
        reader?.close()
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (!wanted || recognizing || Session.paused || getSystemService(KeyguardManager::class.java).isKeyguardLocked) { image.close(); return@setOnImageAvailableListener }
                wanted = false; recognizing = true
                val epoch = captureEpoch
                val expected = Session.title
                var bitmap: Bitmap? = null
                try {
                    val plane = image.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (padded !== bitmap) padded.recycle()
                } catch (_: Exception) { recognizing = false; Session.fail("无法读取屏幕图像") }
                finally { image.close() }
                val frame = bitmap ?: return@setOnImageAvailableListener
                if (alive && epoch == captureEpoch && expected == Session.title) {
                    Session.fail("当前构建已完成屏幕授权和取帧；中文 OCR 适配将在下一构建接入。请先粘贴或分享聊天文字。")
                }
                frame.recycle(); recognizing = false; if (alive) overlay?.refresh()
            }, handler)
        }
        val density = resources.configuration.densityDpi
        if (display == null) display = projection?.createVirtualDisplay("CeyuCapture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        else { display?.resize(w, h, density); display?.surface = reader!!.surface }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            resize(bounds.width(), bounds.height())
        }
        overlay?.reposition()
    }
    private fun requestFrame() {
        if (Session.paused || Session.busy || recognizing || wanted) return
        if (Session.title.isBlank()) { Session.fail("请先设置目标聊天昵称"); return }
        overlay?.hideOutput()
        val epoch = ++captureEpoch
        handler.postDelayed({ if (alive && epoch == captureEpoch && !Session.paused) wanted = true }, 450)
        handler.postDelayed({
            if (alive && epoch == captureEpoch && wanted) { wanted = false; Session.fail("暂时没有新画面，请返回聊天后重试") }
        }, 5000)
    }
    private fun finish() {
        Session.cancel()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("finishSession", true))
        stopSelf()
    }
    private fun emergency() { Session.clear(); Session.publish(); stopSelf() }
    override fun onDestroy() {
        alive = false; captureEpoch++; handler.removeCallbacksAndMessages(null)
        Session.unsubscribe(listener); unregisterReceiver(screenOff)
        overlay?.close(); reader?.close(); display?.release(); projection?.stop()
        Session.capturing = false; Session.cancel(); Session.publish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
