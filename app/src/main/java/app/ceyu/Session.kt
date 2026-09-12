package app.ceyu

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

object Session {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<() -> Unit>()
    private lateinit var vault: Vault
    private var initialized = false
    private var storageError = false
    private var data = JSONObject()
    var text = ""; private set
    var background = ""; private set
    var title = ""; private set
    var goal = "自然延续"; private set
    var tone = "自然"; private set
    var personId = ""; private set
    var result: JSONObject? = null; private set
    var busy = false; private set
    var silent = false; private set
    var paused = false; private set
    var capturing = false
    var error = ""; private set
    var notice = ""; private set
    var source = "手动输入"; private set
    private var generation = 0
    private var client: ModelClient? = null
    private var currentResultText = ""
    private var currentResultBackground = ""
    private var currentResultPerson = ""
    private var currentResultTitle = ""
    private var feedback = ""
    private var currentRecordId: String? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        vault = Vault(context.applicationContext)
        data = try { vault.read() ?: JSONObject() } catch (_: Exception) {
            storageError = true
            error = "本地加密档案无法读取。为保护原文件，已停止写入；请先导出原文件或重新安装前保留备份。"
            JSONObject()
        }
        if (!data.has("people")) data.put("people", JSONArray())
        if (!data.has("records")) data.put("records", JSONArray())
        if (!data.has("settings")) data.put("settings", JSONObject().put("endpoint", "https://api.deepseek.com")
            .put("model", "deepseek-chat").put("key", "").put("duration", 3).put("handleY", 0.82)
            .put("silentSave", false).put("channel", "text"))
    }
    fun settings(): JSONObject = data.getJSONObject("settings")
    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun publish() { listeners.toList().forEach { it() } }
    fun fail(message: String) { error = message; notice = ""; publish() }
    fun inform(message: String) { notice = message; error = ""; publish() }
    fun snapshot(): JSONObject {
        val safeSettings = JSONObject(settings().toString()).apply {
            put("keyConfigured", optString("key").isNotBlank()); remove("key")
        }
        return JSONObject().put("text", text).put("background", background).put("title", title)
            .put("goal", goal).put("tone", tone).put("personId", personId).put("result", result ?: JSONObject.NULL)
            .put("busy", busy).put("silent", silent).put("paused", paused).put("capturing", capturing)
            .put("error", error).put("notice", notice).put("source", source).put("feedback", feedback)
            .put("people", data.getJSONArray("people")).put("records", data.getJSONArray("records"))
            .put("settings", safeSettings)
    }
    private fun persist() {
        check(!storageError) { "加密存储不可用，未覆盖原文件" }
        vault.write(data)
    }
    fun updateDraft(p: JSONObject) {
        val newTitle = p.optString("title", title).take(100)
        val newPerson = p.optString("personId", personId)
        val newText = p.optString("text", text).take(Analysis.MAX_TEXT)
        val newBackground = p.optString("background", background).take(6000)
        if (newTitle != title || newPerson != personId || newText != text || newBackground != background) {
            invalidate()
            result = null; currentRecordId = null
        }
        title = newTitle; personId = newPerson; text = newText; background = newBackground
        goal = p.optString("goal", goal).take(40); tone = p.optString("tone", tone).take(40)
        error = ""; notice = ""
    }
    fun readScreen(value: String) {
        if (!capturing || paused) return
        invalidate()
        result = null; currentRecordId = null
        text = value.take(Analysis.MAX_TEXT); source = "屏幕 OCR（说话人待核对）"
        analyze()
    }
    fun share(value: String) {
        clear()
        text = value.take(Analysis.MAX_TEXT); source = "分享文本"; publish()
    }
    fun analyze() {
        if (busy || paused) return
        error = ""; notice = ""
        val linked = find("people", personId)
        val request = try { Analysis.request(text, background, goal, tone,
            linked?.optJSONArray("notes")?.toString() ?: "[]", settings().optString("model")) }
        catch (e: Exception) { fail(e.message ?: "内容不完整"); return }
        val key = settings().optString("key")
        if (key.isBlank()) { fail("请先在设置中填写模型 API 密钥。聊天不会自动上传。"); return }
        val endpoint = settings().optString("endpoint")
        val myGeneration = ++generation
        val textAtRequest = text; val bgAtRequest = background; val titleAtRequest = title; val personAtRequest = personId
        busy = true; publish()
        val taskClient = ModelClient(); client = taskClient
        worker.execute {
            val outcome = runCatching { taskClient.analyze(endpoint, key, request) }
            main.post {
                if (myGeneration != generation) return@post
                busy = false; client = null
                outcome.onSuccess {
                    result = it; currentRecordId = null; feedback = ""
                    currentResultText = textAtRequest; currentResultBackground = bgAtRequest
                    currentResultTitle = titleAtRequest; currentResultPerson = personAtRequest
                    if (silent && settings().optBoolean("silentSave")) {
                        runCatching { saveRecord(JSONObject().put("name", titleAtRequest).put("candidates", JSONArray()), false) }
                            .onFailure { e -> error = e.message ?: "静默记录保存失败" }
                    }
                }.onFailure { error = when(it) {
                    is java.net.SocketTimeoutException -> "分析超时，请稍后重试"
                    is java.io.IOException -> "网络连接失败，请检查网络和服务地址"
                    is org.json.JSONException -> "模型没有返回有效结构，请重试或更换模型"
                    else -> it.message ?: "分析失败，请重试"
                } }
                publish()
            }
        }
    }
    fun toggleSilent() { silent = !silent; publish() }
    fun setSilent(value: Boolean) { silent = value; publish() }
    fun togglePause() { paused = !paused; if (paused) invalidate(); publish() }
    private fun invalidate() { generation++; client?.cancel(); client = null; busy = false }
    fun cancel() { invalidate(); publish() }
    fun clear() {
        invalidate(); result = null; text = ""; background = ""; title = ""; personId = ""
        silent = false; paused = false; feedback = ""; source = "手动输入"; error = ""; notice = ""; currentRecordId = null
        currentResultText = ""; currentResultBackground = ""; currentResultPerson = ""; currentResultTitle = ""
    }
    private fun find(key: String, id: String): JSONObject? {
        val list = data.getJSONArray(key)
        for (i in 0 until list.length()) if (list.getJSONObject(i).optString("id") == id) return list.getJSONObject(i)
        return null
    }
    private fun remove(key: String, id: String) {
        val list = data.getJSONArray(key)
        for (i in list.length() - 1 downTo 0) if (list.getJSONObject(i).optString("id") == id) list.remove(i)
    }
    private fun saveRecord(p: JSONObject, savePerson: Boolean = true) {
        val analysis = result ?: error("请先生成建议")
        val recordId = currentRecordId ?: UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val record = JSONObject().put("id", recordId).put("at", now).put("title", currentResultTitle.ifBlank { "未命名对话" })
            .put("text", currentResultText).put("background", currentResultBackground).put("result", JSONObject(analysis.toString()))
            .put("personId", currentResultPerson).put("silent", silent).put("feedback", feedback)
        if (savePerson) {
            var person = find("people", p.optString("personId", currentResultPerson))
            val name = p.optString("name", currentResultTitle).trim().take(100)
            if (person == null && name.isNotEmpty()) {
                person = JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
                    .put("avatar", "").put("account", "").put("notes", JSONArray()).put("history", JSONArray()).put("at", now)
                data.getJSONArray("people").put(person)
            }
            person?.let {
                record.put("personId", it.getString("id"))
                val notes = it.getJSONArray("notes")
                val candidates = p.optJSONArray("candidates") ?: JSONArray()
                for (i in 0 until minOf(candidates.length(), 12)) {
                    val candidate = candidates.getJSONObject(i)
                    if (candidate.optString("text").isBlank()) continue
                    notes.put(JSONObject().put("text", candidate.optString("text").take(500))
                        .put("kind", if (candidate.optString("kind") == "事实") "事实" else "推测")
                        .put("evidence", candidate.optString("evidence").take(500)).put("at", now).put("source", recordId))
                }
            }
        }
        remove("records", recordId)
        data.getJSONArray("records").put(record)
        persist(); currentRecordId = recordId
    }
    fun action(name: String, p: JSONObject = JSONObject()) {
        try {
            when (name) {
                "draft" -> updateDraft(p)
                "analyze" -> { updateDraft(p); analyze(); return }
                "cancel" -> cancel()
                "silent" -> toggleSilent()
                "pause" -> togglePause()
                "clear" -> clear()
                "save" -> { saveRecord(p); clear(); notice = "记录已加密保存" }
                "settings" -> {
                    val s = settings()
                    val endpoint = Analysis.endpoint(p.optString("endpoint", s.optString("endpoint")))
                    val model = p.optString("model", s.optString("model")).trim().take(120)
                    require(model.isNotEmpty()) { "模型名称不能为空" }
                    s.put("endpoint", endpoint).put("model", model)
                    if (p.has("key") && p.optString("key").isNotEmpty()) s.put("key", p.optString("key").trim())
                    if (p.optBoolean("clearKey")) s.put("key", "")
                    for (field in listOf("silentSave", "channel")) if (p.has(field)) s.put(field, p.get(field))
                    if (p.has("duration")) s.put("duration", p.optInt("duration").coerceIn(2, 8))
                    if (p.has("handleY")) s.put("handleY", p.optDouble("handleY").coerceIn(0.1, 0.9))
                    persist(); notice = "设置已保存"
                }
                "feedback" -> { feedback = p.optString("value").take(100); notice = "反馈已记录；可再次点击撤销" }
                "deleteRecord" -> { remove("records", p.getString("id")); persist() }
                "deletePerson" -> {
                    val id = p.getString("id")
                    remove("people", id)
                    val records = data.getJSONArray("records")
                    for (i in records.length() - 1 downTo 0) if (records.getJSONObject(i).optString("personId") == id) records.remove(i)
                    if (personId == id) { invalidate(); personId = ""; result = null }
                    persist()
                }
                "editPerson" -> {
                    val person = find("people", p.getString("id")) ?: error("人物不存在")
                    val newName = p.optString("name").trim().take(100)
                    require(newName.isNotEmpty()) { "昵称不能为空" }
                    if (newName != person.optString("name")) person.getJSONArray("history").put(JSONObject()
                        .put("name", person.optString("name")).put("at", Instant.now().toString()))
                    person.put("name", newName).put("account", p.optString("account").take(200))
                    val notes = JSONArray()
                    for (line in p.optString("notes").take(14000).lines().filter { it.isNotBlank() }) {
                        notes.put(JSONObject().put("text", line.take(500)).put("kind", "用户笔记").put("at", Instant.now().toString()))
                    }
                    person.put("notes", notes); persist()
                }
                "deleteAll" -> {
                    clear(); data.put("people", JSONArray()).put("records", JSONArray()); persist(); notice = "人物和记录已删除"
                }
            }
        } catch (e: Exception) { error = e.message ?: "操作失败" }
        publish()
    }
    fun saveAvatar(id: String, encoded: String) {
        val person = find("people", id) ?: return
        person.getJSONArray("history").put(JSONObject().put("avatar", person.optString("avatar")).put("at", Instant.now().toString()))
        person.put("avatar", encoded); persist(); publish()
    }
    fun archive(): JSONObject = JSONObject().put("version", 1).put("people", data.getJSONArray("people")).put("records", data.getJSONArray("records"))
    fun importArchive(archive: JSONObject) {
        Analysis.validateArchive(archive)
        for (key in listOf("people", "records")) {
            val items = archive.getJSONArray(key)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (find(key, item.getString("id")) == null) data.getJSONArray(key).put(item)
            }
        }
        persist(); inform("加密备份已导入；已有条目保持不变")
    }
}
