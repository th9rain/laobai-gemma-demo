package com.laobai.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ModelTraceSource(val displayName: String) {
    CLOUD_PLANNER_REPLAY("云侧 Planner · 历史回放"),
    EDGE_VQA("端侧 Gemma · 真实 VQA"),
}

data class ModelTraceEntry(
    val id: String = UUID.randomUUID().toString(),
    val source: ModelTraceSource,
    val title: String,
    val modelName: String,
    val inputText: String,
    val outputText: String,
    val screenshotPath: String? = null,
    val elapsedMs: Long = 0L,
    val backend: String = "",
    val status: String = "完成",
    val timestampMs: Long = System.currentTimeMillis(),
)

data class ModelTraceSession(
    val id: String,
    val demoCase: DemoCase,
    val startedAtMs: Long,
    val entries: List<ModelTraceEntry>,
)

/**
 * Persists the latest workflow's model calls in app-private storage. Prompts and
 * raw outputs are intentionally kept local; screenshot paths never leave the
 * device. Starting a new workflow replaces the previous session and its images.
 */
object ModelTraceStore {
    private const val TRACE_DIRECTORY = "model_traces"
    private const val TRACE_FILE = "latest.json"
    private const val IMAGE_DIRECTORY = "images"
    private const val MAX_ENTRIES = 96
    private val lock = Any()

    fun startSession(context: Context, demoCase: DemoCase): String = synchronized(lock) {
        val directory = traceDirectory(context)
        val imageDirectory = File(directory, IMAGE_DIRECTORY)
        imageDirectory.listFiles()?.forEach { file -> runCatching { file.delete() } }
        if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
            throw IllegalStateException("无法创建模型调用截图目录")
        }

        val sessionId = UUID.randomUUID().toString()
        writeSession(
            context,
            ModelTraceSession(
                id = sessionId,
                demoCase = demoCase,
                startedAtMs = System.currentTimeMillis(),
                entries = emptyList(),
            ),
        )
        sessionId
    }

    fun append(context: Context, sessionId: String, entry: ModelTraceEntry) {
        synchronized(lock) {
            val session = readSession(context) ?: return
            if (session.id != sessionId) return
            writeSession(
                context,
                session.copy(entries = (session.entries + entry).takeLast(MAX_ENTRIES)),
            )
        }
    }

    fun latestSession(context: Context): ModelTraceSession? = synchronized(lock) {
        readSession(context)
    }

    fun hasEntries(context: Context): Boolean = latestSession(context)?.entries?.isNotEmpty() == true

    fun newScreenshotFile(context: Context, sessionId: String, stage: String): File {
        val safeStage = stage.replace(Regex("[^A-Za-z0-9_-]+"), "-").take(48)
        val directory = File(traceDirectory(context), IMAGE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建模型调用截图目录")
        }
        return File(directory, "${sessionId.take(8)}-${System.currentTimeMillis()}-$safeStage.png")
    }

    private fun traceDirectory(context: Context): File =
        File(context.filesDir, TRACE_DIRECTORY).also { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("无法创建模型调用记录目录")
            }
        }

    private fun traceFile(context: Context): File = File(traceDirectory(context), TRACE_FILE)

    private fun writeSession(context: Context, session: ModelTraceSession) {
        val destination = traceFile(context)
        val temporary = File(destination.parentFile, "$TRACE_FILE.tmp")
        temporary.writeText(session.toJson().toString(), Charsets.UTF_8)
        if (!temporary.renameTo(destination)) {
            destination.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun readSession(context: Context): ModelTraceSession? {
        val file = traceFile(context)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)).toSession() }.getOrNull()
    }

    private fun ModelTraceSession.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("demoCase", demoCase.name)
        put("startedAtMs", startedAtMs)
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
    }

    private fun ModelTraceEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("source", source.name)
        put("title", title)
        put("modelName", modelName)
        put("inputText", inputText)
        put("outputText", outputText)
        put("screenshotPath", screenshotPath ?: JSONObject.NULL)
        put("elapsedMs", elapsedMs)
        put("backend", backend)
        put("status", status)
        put("timestampMs", timestampMs)
    }

    private fun JSONObject.toSession(): ModelTraceSession {
        val entriesJson = optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until entriesJson.length()) {
                entriesJson.optJSONObject(index)?.toEntry()?.let(::add)
            }
        }
        return ModelTraceSession(
            id = getString("id"),
            demoCase = DemoCase.valueOf(getString("demoCase")),
            startedAtMs = optLong("startedAtMs"),
            entries = entries,
        )
    }

    private fun JSONObject.toEntry(): ModelTraceEntry? = runCatching {
        ModelTraceEntry(
            id = getString("id"),
            source = ModelTraceSource.valueOf(getString("source")),
            title = optString("title"),
            modelName = optString("modelName"),
            inputText = optString("inputText"),
            outputText = optString("outputText"),
            screenshotPath = optString("screenshotPath").takeIf { it.isNotBlank() && it != "null" },
            elapsedMs = optLong("elapsedMs"),
            backend = optString("backend"),
            status = optString("status", "完成"),
            timestampMs = optLong("timestampMs"),
        )
    }.getOrNull()
}
