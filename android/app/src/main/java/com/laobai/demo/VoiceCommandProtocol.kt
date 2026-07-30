package com.laobai.demo

import android.content.Context
import android.content.Intent
import android.os.SystemClock

enum class VoiceWorkflowCommand(val wireValue: String) {
    TRIGGER("TRIGGER"),
    ALWAYS_ON("ALWAYS_ON"),
}

/**
 * Package-scoped protocol between the short-lived voice activity and the
 * accessibility service. The receiver must also require [INTERNAL_PERMISSION]
 * and, on API 33+, register with Context.RECEIVER_NOT_EXPORTED.
 */
object VoiceCommandProtocol {
    const val ACTION_WORKFLOW_COMMAND =
        "com.laobai.demo.action.VOICE_WORKFLOW_COMMAND"
    const val INTERNAL_PERMISSION =
        "com.laobai.demo.permission.INTERNAL_VOICE_COMMAND"
    const val EXTRA_COMMAND = "com.laobai.demo.extra.VOICE_COMMAND"
    const val EXTRA_PROTOCOL_VERSION = "com.laobai.demo.extra.PROTOCOL_VERSION"
    const val EXTRA_ISSUED_AT_ELAPSED_MS = "com.laobai.demo.extra.ISSUED_AT_ELAPSED_MS"
    const val PROTOCOL_VERSION = 1

    fun send(context: Context, command: VoiceWorkflowCommand) {
        val intent = Intent(ACTION_WORKFLOW_COMMAND)
            .setPackage(context.packageName)
            .putExtra(EXTRA_COMMAND, command.wireValue)
            .putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .putExtra(EXTRA_ISSUED_AT_ELAPSED_MS, SystemClock.elapsedRealtime())

        context.sendBroadcast(intent, INTERNAL_PERMISSION)
    }

    fun read(intent: Intent?): VoiceWorkflowCommand? {
        if (intent?.action != ACTION_WORKFLOW_COMMAND) return null
        if (intent.getIntExtra(EXTRA_PROTOCOL_VERSION, -1) != PROTOCOL_VERSION) return null
        val issuedAt = intent.getLongExtra(EXTRA_ISSUED_AT_ELAPSED_MS, -1L)
        val age = SystemClock.elapsedRealtime() - issuedAt
        if (issuedAt < 0L || age !in 0..MAX_COMMAND_AGE_MS) return null
        val value = intent.getStringExtra(EXTRA_COMMAND) ?: return null
        return VoiceWorkflowCommand.entries.firstOrNull { it.wireValue == value }
    }

    fun resolveTranscripts(transcripts: List<String>): VoiceWorkflowCommand? {
        val classifications = transcripts.map(::classifyTranscript)
        if (Classification.AMBIGUOUS in classifications) return null
        val commands = classifications.mapNotNull { it.command }.toSet()
        return commands.singleOrNull()
    }

    private fun classifyTranscript(transcript: String): Classification {
        val normalized = transcript
            .lowercase()
            .replace(IGNORED_CHARACTERS, "")
        val trigger = TRIGGER_TERMS.any(normalized::contains)
        val alwaysOn = ALWAYS_ON_TERMS.any(normalized::contains)
        return when {
            trigger && alwaysOn -> Classification.AMBIGUOUS
            trigger -> Classification.TRIGGER
            alwaysOn -> Classification.ALWAYS_ON
            else -> Classification.NONE
        }
    }

    private enum class Classification(val command: VoiceWorkflowCommand?) {
        TRIGGER(VoiceWorkflowCommand.TRIGGER),
        ALWAYS_ON(VoiceWorkflowCommand.ALWAYS_ON),
        AMBIGUOUS(null),
        NONE(null),
    }

    private val IGNORED_CHARACTERS = Regex("[\\s，。！？、,.!?；;：:\\-]+")
    private val TRIGGER_TERMS = listOf("预约挂号", "挂号", "我有点不舒服", "不舒服", "看医生", "看病")
    private val ALWAYS_ON_TERMS = listOf("填写表单", "帮我填", "填表")
    private const val MAX_COMMAND_AGE_MS = 10_000L
}
