package com.th9rain.laobai.gemmademo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ArkCloudPlanner {
    private const val endpoint = "https://ark.cn-beijing.volces.com/api/v3/responses"
    private const val model = "doubao-seed-1-8-251228"

    suspend fun plan(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("model", model)
                .put(
                    "input",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_text")
                                        .put("text", prompt)
                                )
                            )
                    )
                )
                .toString()

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) {
                error("Ark API ${connection.responseCode}: $response")
            }
            extractText(response).ifBlank { response.take(300) }
        }
    }

    private fun extractText(raw: String): String {
        val json = JSONObject(raw)
        val output = json.optJSONArray("output") ?: return ""
        val chunks = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) chunks.add(text)
            }
        }
        return chunks.joinToString("\n")
    }
}
