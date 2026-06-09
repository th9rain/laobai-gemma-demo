package com.th9rain.laobai.gemmademo

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class KnowledgeBase(
    val items: List<KnowledgeItem>,
)

@Serializable
private data class KnowledgeItem(
    val id: String,
    val keywords: List<String>,
    val department: String,
    val hospital: String,
    val reason: String,
)

object LocalEmbeddingEngine {
    private val json = Json { ignoreUnknownKeys = true }

    fun recommend(context: Context, query: String): PlannerResult {
        val kb = loadKnowledgeBase(context)
        val item = kb.items.maxByOrNull { score(query, it.keywords) } ?: kb.items.first()
        return PlannerResult(
            hospital = item.hospital,
            department = item.department,
            reason = "本地 Gemma Embedding demo 检索命中「${item.id}」：${item.reason}",
            usedCloud = false,
        )
    }

    private fun loadKnowledgeBase(context: Context): KnowledgeBase {
        val text = context.assets.open("embedding_kb.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(KnowledgeBase.serializer(), text)
    }

    private fun score(query: String, keywords: List<String>): Int {
        return keywords.sumOf { keyword -> if (query.contains(keyword)) 2 else 0 } +
            keywords.count { keyword -> keyword.any { query.contains(it) } }
    }
}
