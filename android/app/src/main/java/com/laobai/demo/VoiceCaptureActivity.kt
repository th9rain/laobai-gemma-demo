package com.laobai.demo

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/**
 * A deliberately visible, short-lived microphone surface. Keeping capture in
 * an Activity makes the user gesture and foreground microphone use explicit on
 * recent Android versions; this class never starts a workflow directly.
 */
class VoiceCaptureActivity : Activity(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var planning: TextView
    private lateinit var progress: ProgressBar
    private var recognizer: SpeechRecognizer? = null
    private var systemRecognizerActive = false
    private var terminal = false
    private var permissionRequestActive = false
    private var activeRecognizerRoute: RecognizerRoute? = null
    private var defaultRecognizerFallbackAttempted = false
    private var recognizerFallbackScheduled = false
    private var latestPartialTranscript = ""

    private val timeout = Runnable {
        if (terminal || systemRecognizerActive) return@Runnable
        finishWithoutCommand(R.string.voice_timeout)
    }

    private val processingTimeout = Runnable {
        if (terminal || systemRecognizerActive) return@Runnable
        finishWithoutCommand(R.string.voice_recognition_failed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_capture)
        setFinishOnTouchOutside(false)

        status = findViewById(R.id.voiceStatus)
        transcript = findViewById(R.id.voiceTranscript)
        planning = findViewById(R.id.voicePlanning)
        progress = findViewById(R.id.voiceProgress)
        findViewById<Button>(R.id.voiceCancel).setOnClickListener {
            finishWithoutCommand(R.string.voice_cancelled, finishDelayMs = 0)
        }

        systemRecognizerActive = savedInstanceState?.getBoolean(STATE_SYSTEM_RECOGNIZER) == true
        terminal = savedInstanceState?.getBoolean(STATE_TERMINAL) == true
        permissionRequestActive = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUEST) == true
        defaultRecognizerFallbackAttempted =
            savedInstanceState?.getBoolean(STATE_DEFAULT_RECOGNIZER_FALLBACK) == true
        when {
            terminal -> finish()
            systemRecognizerActive -> {
                progress.visibility = View.GONE
                showStatus(R.string.voice_system_prompt)
            }
            else -> requestMicrophoneOrListen()
        }
    }

    private fun requestMicrophoneOrListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Log.i(
                TAG,
                "Microphone permission granted; starting embedded recognizer " +
                    "forceDefault=$defaultRecognizerFallbackAttempted",
            )
            startEmbeddedRecognizer(forceDefault = defaultRecognizerFallbackAttempted)
        } else {
            Log.i(TAG, "Requesting RECORD_AUDIO permission")
            showStatus(R.string.voice_requesting_permission)
            permissionRequestActive = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO || terminal) return
        permissionRequestActive = false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "RECORD_AUDIO permission result granted=$granted")
        if (granted) {
            startEmbeddedRecognizer(forceDefault = defaultRecognizerFallbackAttempted)
        } else {
            scheduleSystemRecognizerFallback(
                reason = "RECORD_AUDIO permission denied",
                messageRes = R.string.voice_permission_denied_fallback,
            )
        }
    }

    private fun startEmbeddedRecognizer(forceDefault: Boolean = false) {
        if (terminal) return
        recognizerFallbackScheduled = false
        showStatus(R.string.voice_preparing)

        val created = createPreferredRecognizer(forceDefault)
        if (created == null) {
            scheduleSystemRecognizerFallback(
                reason = "No embedded RecognitionService is available",
                messageRes = R.string.voice_recognizer_fallback,
            )
            return
        }

        recognizer = created.recognizer
        activeRecognizerRoute = created.route
        if (created.route == RecognizerRoute.DEFAULT) {
            defaultRecognizerFallbackAttempted = true
        }
        created.recognizer.setRecognitionListener(this)
        showStatus(
            if (created.route == RecognizerRoute.ON_DEVICE) R.string.voice_listening_on_device
            else R.string.voice_listening,
        )
        progress.visibility = View.VISIBLE
        clearRecognitionTimeouts()
        handler.postDelayed(timeout, LISTEN_TIMEOUT_MS)

        val preferOffline = created.route == RecognizerRoute.ON_DEVICE
        Log.i(
            TAG,
            "Starting embedded recognizer route=${created.route} preferOffline=$preferOffline",
        )
        runCatching {
            created.recognizer.startListening(recognitionIntent(preferOffline = preferOffline))
        }
            .onFailure { error ->
                Log.e(TAG, "startListening failed route=${created.route}", error)
                cleanupEmbeddedRecognizer()
                if (created.route == RecognizerRoute.ON_DEVICE) {
                    scheduleDefaultRecognizerFallback("on-device startListening threw")
                } else {
                    scheduleSystemRecognizerFallback(
                        reason = "default startListening threw",
                        messageRes = R.string.voice_recognizer_fallback,
                    )
                }
            }
    }

    private fun createPreferredRecognizer(forceDefault: Boolean): CreatedRecognizer? {
        if (!forceDefault && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val onDeviceAvailable =
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }
                    .onFailure { Log.w(TAG, "Unable to check on-device recognition availability", it) }
                    .getOrDefault(false)
            Log.i(TAG, "On-device recognition available=$onDeviceAvailable")
            if (onDeviceAvailable) {
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }
                    .onFailure { Log.w(TAG, "Unable to create on-device recognizer", it) }
                    .getOrNull()
                    ?.let { return CreatedRecognizer(it, RecognizerRoute.ON_DEVICE) }
            }
        }

        val defaultAvailable = runCatching { SpeechRecognizer.isRecognitionAvailable(this) }
            .onFailure { Log.w(TAG, "Unable to check default recognition availability", it) }
            .getOrDefault(false)
        Log.i(TAG, "Default recognition available=$defaultAvailable forceDefault=$forceDefault")
        if (!defaultAvailable) return null
        return runCatching { SpeechRecognizer.createSpeechRecognizer(this) }
            .onFailure { Log.w(TAG, "Unable to create default recognizer", it) }
            .getOrNull()
            ?.let { CreatedRecognizer(it, RecognizerRoute.DEFAULT) }
    }

    private fun recognitionIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_ZH_CN)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun scheduleDefaultRecognizerFallback(reason: String) {
        if (
            terminal ||
            systemRecognizerActive ||
            defaultRecognizerFallbackAttempted ||
            recognizerFallbackScheduled
        ) {
            Log.w(
                TAG,
                "Skipping default recognizer fallback reason=$reason " +
                    "terminal=$terminal systemActive=$systemRecognizerActive " +
                    "defaultAttempted=$defaultRecognizerFallbackAttempted " +
                    "fallbackScheduled=$recognizerFallbackScheduled",
            )
            return
        }

        defaultRecognizerFallbackAttempted = true
        recognizerFallbackScheduled = true
        Log.i(TAG, "Scheduling default RecognitionService fallback reason=$reason")
        showStatus(R.string.voice_recognizer_fallback)
        handler.postDelayed(
            {
                if (terminal) return@postDelayed
                recognizerFallbackScheduled = false
                startEmbeddedRecognizer(forceDefault = true)
            },
            FALLBACK_MESSAGE_MS,
        )
    }

    private fun scheduleSystemRecognizerFallback(
        reason: String,
        messageRes: Int,
    ) {
        if (terminal || systemRecognizerActive || recognizerFallbackScheduled) {
            Log.w(
                TAG,
                "Skipping external recognizer fallback reason=$reason " +
                    "terminal=$terminal systemActive=$systemRecognizerActive " +
                    "fallbackScheduled=$recognizerFallbackScheduled",
            )
            return
        }

        recognizerFallbackScheduled = true
        Log.i(TAG, "Scheduling external system recognizer fallback reason=$reason")
        showStatus(messageRes)
        handler.postDelayed(
            {
                if (terminal) return@postDelayed
                recognizerFallbackScheduled = false
                launchSystemRecognizer()
            },
            FALLBACK_MESSAGE_MS,
        )
    }

    private fun launchSystemRecognizer() {
        if (terminal || systemRecognizerActive) return
        recognizerFallbackScheduled = false
        clearRecognitionTimeouts()
        cleanupEmbeddedRecognizer()

        // Do not request offline-only recognition here. Many Xiaomi builds expose
        // a system recognition Activity without an installed zh-CN offline model.
        val intent = recognitionIntent(preferOffline = false).putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            getString(R.string.voice_system_prompt),
        )
        val resolvedActivity = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.let { "${it.packageName}/${it.name}" }
            ?: "none"
        Log.i(
            TAG,
            "Launching external system recognizer preferOffline=false resolved=$resolvedActivity",
        )
        try {
            systemRecognizerActive = true
            startActivityForResult(intent, REQUEST_SYSTEM_RECOGNIZER)
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "No external system recognizer Activity", error)
            systemRecognizerActive = false
            finishWithoutCommand(R.string.voice_recognizer_unavailable)
        } catch (error: SecurityException) {
            Log.e(TAG, "External system recognizer rejected launch", error)
            systemRecognizerActive = false
            finishWithoutCommand(R.string.voice_recognizer_unavailable)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SYSTEM_RECOGNIZER || terminal) return
        systemRecognizerActive = false
        Log.i(
            TAG,
            "External recognizer resultCode=$resultCode hasData=${data != null}",
        )
        if (resultCode != RESULT_OK) {
            finishWithoutCommand(R.string.voice_no_match)
            return
        }
        val transcripts = data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            .orEmpty()
        handleTranscripts(transcripts)
    }

    private fun handleTranscripts(transcripts: List<String>) {
        if (terminal) return
        val recognized = transcripts.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: latestPartialTranscript.trim()
        val candidates = buildList {
            addAll(transcripts.filter(String::isNotBlank))
            if (recognized.isNotBlank() && recognized !in this) add(recognized)
        }
        val command = VoiceCommandProtocol.resolveTranscripts(candidates)
        Log.i(
            TAG,
            "Recognition completed resultCount=${transcripts.size} resolvedCommand=${command?.wireValue}",
        )
        if (command == null) {
            val hasRecognizedWords = recognized.isNotBlank()
            if (hasRecognizedWords) showTranscript(recognized, partial = false)
            finishWithoutCommand(
                if (hasRecognizedWords) R.string.voice_ambiguous
                else R.string.voice_no_match,
            )
            return
        }

        terminal = true
        clearRecognitionTimeouts()
        cleanupEmbeddedRecognizer()
        showTranscript(recognized, partial = false)
        showPlanningSequence(command, recognized)
    }

    private fun finishWithoutCommand(
        messageRes: Int,
        finishDelayMs: Long = RESULT_MESSAGE_MS,
    ) {
        if (terminal) return
        terminal = true
        systemRecognizerActive = false
        permissionRequestActive = false
        clearRecognitionTimeouts()
        cleanupEmbeddedRecognizer()
        showStatus(messageRes)
        progress.visibility = View.GONE
        if (finishDelayMs == 0L) finish() else handler.postDelayed({ finish() }, finishDelayMs)
    }

    private fun showStatus(messageRes: Int) {
        status.setText(messageRes)
    }

    private fun showTranscript(text: String, partial: Boolean) {
        if (text.isBlank()) return
        transcript.text = if (partial) "$text…" else text
        transcript.setTextColor(getColor(R.color.text_primary))
    }

    private fun showPlanningSequence(
        command: VoiceWorkflowCommand,
        recognized: String,
    ) {
        planning.visibility = View.VISIBLE
        status.text = "正在理解您的需求…"
        progress.visibility = View.VISIBLE
        val frames = listOf(
            "✓ 语音已实时转写\n● 正在理解任务意图",
            "✓ 语音已实时转写\n✓ 已理解任务意图\n● 正在检索相关记忆与偏好",
            "✓ 语音已实时转写\n✓ 已理解任务意图\n✓ 已检索相关记忆与偏好\n● Gemma 32B 正在制定任务计划",
            "✓ 语音已实时转写\n✓ 已理解任务意图\n✓ 已检索相关记忆与偏好\n✓ Gemma 32B 已生成任务计划\n● 正在选择端侧执行能力",
        )
        frames.forEachIndexed { index, frame ->
            handler.postDelayed(
                { planning.text = frame },
                index * PLANNING_FRAME_MS,
            )
        }
        handler.postDelayed(
            {
                planning.text = frames.last().replace("● 正在选择端侧执行能力", "✓ 已选择端侧执行能力")
                showStatus(
                    when (command) {
                        VoiceWorkflowCommand.TRIGGER -> R.string.voice_trigger_received
                        VoiceWorkflowCommand.ALWAYS_ON -> R.string.voice_always_on_received
                    },
                )
                progress.visibility = View.GONE
                VoiceCommandProtocol.send(this, command, recognized)
                handler.postDelayed({ finish() }, RESULT_MESSAGE_MS)
            },
            frames.size * PLANNING_FRAME_MS,
        )
    }

    private fun cleanupEmbeddedRecognizer() {
        recognizer?.let { active ->
            runCatching { active.setRecognitionListener(null) }
            runCatching { active.cancel() }
            runCatching { active.destroy() }
        }
        recognizer = null
        activeRecognizerRoute = null
    }

    private fun clearRecognitionTimeouts() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(processingTimeout)
    }

    private fun speechErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS ->
            "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"

        else -> "UNKNOWN"
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (!terminal && !systemRecognizerActive) showStatus(R.string.voice_listening)
    }

    override fun onBeginningOfSpeech() {
        if (!terminal && !systemRecognizerActive) showStatus(R.string.voice_heard_speech)
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (!terminal && !systemRecognizerActive) {
            showStatus(R.string.voice_processing)
            handler.removeCallbacks(timeout)
            handler.postDelayed(processingTimeout, PROCESSING_TIMEOUT_MS)
        }
    }

    override fun onError(error: Int) {
        if (terminal || systemRecognizerActive) return
        val failedRoute = activeRecognizerRoute
        Log.w(
            TAG,
            "Recognition error route=$failedRoute code=$error name=${speechErrorName(error)}",
        )
        val shouldTryDefault =
            failedRoute == RecognizerRoute.ON_DEVICE &&
                !defaultRecognizerFallbackAttempted &&
                error != SpeechRecognizer.ERROR_NO_MATCH &&
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        clearRecognitionTimeouts()
        cleanupEmbeddedRecognizer()
        if (shouldTryDefault) {
            scheduleDefaultRecognizerFallback(
                "on-device error $error (${speechErrorName(error)})",
            )
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            scheduleSystemRecognizerFallback(
                reason = "embedded recognizer reported insufficient permissions",
                messageRes = R.string.voice_permission_denied_fallback,
            )
        } else if (
            failedRoute == RecognizerRoute.DEFAULT &&
            error != SpeechRecognizer.ERROR_NO_MATCH &&
            error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        ) {
            scheduleSystemRecognizerFallback(
                reason = "default recognizer error $error (${speechErrorName(error)})",
                messageRes = R.string.voice_recognizer_fallback,
            )
        } else {
            finishWithoutCommand(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_no_match

                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_network_error

                    else -> R.string.voice_recognition_failed
                },
            )
        }
    }

    override fun onResults(results: Bundle?) {
        if (terminal || systemRecognizerActive) return
        val transcripts = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        handleTranscripts(transcripts)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (terminal || systemRecognizerActive) return
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        if (partial.isBlank()) return
        latestPartialTranscript = partial
        showTranscript(partial, partial = true)
        status.text = "正在实时转写…"
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SYSTEM_RECOGNIZER, systemRecognizerActive)
        outState.putBoolean(STATE_TERMINAL, terminal)
        outState.putBoolean(STATE_PERMISSION_REQUEST, permissionRequestActive)
        outState.putBoolean(STATE_DEFAULT_RECOGNIZER_FALLBACK, defaultRecognizerFallbackAttempted)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (
            !terminal &&
            !systemRecognizerActive &&
            !permissionRequestActive &&
            !isChangingConfigurations
        ) {
            finishWithoutCommand(R.string.voice_cancelled, finishDelayMs = 0)
        }
        super.onStop()
    }

    override fun onDestroy() {
        terminal = true
        handler.removeCallbacksAndMessages(null)
        cleanupEmbeddedRecognizer()
        super.onDestroy()
    }

    private enum class RecognizerRoute {
        ON_DEVICE,
        DEFAULT,
    }

    private data class CreatedRecognizer(
        val recognizer: SpeechRecognizer,
        val route: RecognizerRoute,
    )

    companion object {
        private const val TAG = "LaoBaiVoice"
        private const val REQUEST_RECORD_AUDIO = 4101
        private const val REQUEST_SYSTEM_RECOGNIZER = 4102
        private const val LANGUAGE_ZH_CN = "zh-CN"
        private const val MAX_RESULTS = 3
        private const val LISTEN_TIMEOUT_MS = 15_000L
        private const val PROCESSING_TIMEOUT_MS = 8_000L
        private const val FALLBACK_MESSAGE_MS = 550L
        private const val RESULT_MESSAGE_MS = 1_000L
        private const val PLANNING_FRAME_MS = 600L
        private const val STATE_SYSTEM_RECOGNIZER = "system_recognizer_active"
        private const val STATE_TERMINAL = "terminal"
        private const val STATE_PERMISSION_REQUEST = "permission_request_active"
        private const val STATE_DEFAULT_RECOGNIZER_FALLBACK =
            "default_recognizer_fallback_attempted"
    }
}
