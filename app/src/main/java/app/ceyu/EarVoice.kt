package app.ceyu

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

class EarVoice(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var generation = 0
    private var player: MediaPlayer? = null
    private var pending: File? = null
    private val tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
    private fun isHeadset(type: Int) = type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET)
    private fun headset() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isHeadset(it.type) }
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { if (removedDevices.any { isHeadset(it.type) }) stop() }
    }
    init { audio.registerAudioDeviceCallback(callback, main) }
    fun speak(text: String) {
        stop()
        if (!ready || headset() == null) { Session.inform("耳机播报未就绪；请连接耳机并确认系统中文语音可用"); return }
        val current = generation
        val file = File(context.cacheDir, "speech-$current.wav"); pending = file
        if (tts.setLanguage(Locale.SIMPLIFIED_CHINESE) < TextToSpeech.LANG_AVAILABLE) { Session.inform("系统缺少中文语音"); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { main.post { file.delete(); if (current == generation) Session.inform("语音合成失败") } }
            override fun onDone(id: String?) { main.post {
                val device = headset()
                if (current != generation || device == null || Session.silent || Session.paused) { file.delete(); return@post }
                runCatching {
                    player = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        setDataSource(file.absolutePath)
                        check(setPreferredDevice(device)) { "耳机路由不可用" }
                        setVolume(0.35f, 0.35f)
                        addOnRoutingChangedListener({ routing ->
                            if (routing.routedDevice?.let { !isHeadset(it.type) } == true) stop()
                        }, main)
                        setOnCompletionListener { stop() }
                        setOnErrorListener { _, _, _ -> stop(); true }
                        prepare(); start()
                    }
                }.onFailure { stop(); Session.inform("耳机播放失败，已停止播报") }
            } }
        })
        tts.synthesizeToFile(text.take(80), null, file, "$current")
    }
    fun stop() { generation++; tts.stop(); player?.release(); player = null; pending?.delete(); pending = null }
    fun close() { stop(); tts.shutdown(); audio.unregisterAudioDeviceCallback(callback) }
}
