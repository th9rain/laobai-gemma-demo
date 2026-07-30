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
    private lateinit var progress: ProgressBar
    private var recognizer: SpeechRecognizer? = null
    private var systemRecognizerActive = false
    private var terminal = false
    private var permissionRequestActive = false

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
        progress = findViewById(R.id.voiceProgress)
        findViewById<Button>(R.id.voiceCancel).setOnClickListener {
            finishWithoutCommand(R.string.voice_cancelled, finishDelayMs = 0)
        }

        systemRecognizerActive = savedInstanceState?.getBoolean(STATE_SYSTEM_RECOGNIZER) == true
        terminal = savedInstanceState?.getBoolean(STATE_TERMINAL) == true
        permissionRequestActive = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUEST) == true
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
            startEmbeddedRecognizer()
        } else {
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
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startEmbeddedRecognizer()
        } else {
            showStatus(R.string.voice_permission_denied_fallback)
            handler.postDelayed({ launchSystemRecognizer() }, FALLBACK_MESSAGE_MS)
        }
    }

    private fun startEmbeddedRecognizer() {
        if (terminal) return
        showStatus(R.string.voice_preparing)

        val created = createPreferredRecognizer()
        if (created == null) {
            showStatus(R.string.voice_recognizer_fallback)
            handler.postDelayed({ launchSystemRecognizer() }, FALLBACK_MESSAGE_MS)
            return
        }

        recognizer = created.first
        created.first.setRecognitionListener(this)
        showStatus(
            if (created.second) R.string.voice_listening_on_device
            else R.string.voice_listening,
        )
        progress.visibility = View.VISIBLE
        clearRecognitionTimeouts()
        handler.postDelayed(timeout, LISTEN_TIMEOUT_MS)

        runCatching { created.first.startListening(recognitionIntent()) }
            .onFailure {
                cleanupEmbeddedRecognizer()
                showStatus(R.string.voice_recognizer_fallback)
                handler.postDelayed({ launchSystemRecognizer() }, FALLBACK_MESSAGE_MS)
            }
    }

    private fun createPreferredRecognizer(): Pair<SpeechRecognizer, Boolean>? {
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }
                .getOrDefault(false)
        if (onDeviceAvailable) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }
                .getOrNull()
                ?.let { return it to true }
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        return runCatching { SpeechRecognizer.createSpeechRecognizer(this) }
            .getOrNull()
            ?.let { it to false }
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_ZH_CN)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun launchSystemRecognizer() {
        if (terminal || systemRecognizerActive) return
        clearRecognitionTimeouts()
        cleanupEmbeddedRecognizer()

        val intent = recognitionIntent().putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            getString(R.string.voice_system_prompt),
        )
        try {
            systemRecognizerActive = true
            startActivityForResult(intent, REQUEST_SYSTEM_RECOGNIZER)
        } catch (_: ActivityNotFoundException) {
            systemRecognizerActive = false
            finishWithoutCommand(R.string.voice_recognizer_unavailable)
        } catch (_: SecurityException) {
            systemRecognizerActive = false
            finishWithoutCommand(R.string.voice_recognizer_unavailable)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SYSTEM_RECOGNIZER || terminal) return
        systemRecognizerActive = false
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
        val command = VoiceCommandProtocol.resolveTranscripts(transcripts)
        if (command == null) {
            val hasRecognizedWords = transcripts.any { it.isNotBlank() }
            finishWithoutCommand(
                if (hasRecognizedWords) R.string.voice_ambiguous
                else R.string.voice_no_match,
            )
            return
        }

        terminal = true
        clearRecognitionTimeouts()
        cleanupEmbeddedRecognizer()
        VoiceCommandProtocol.send(this, command)
        showStatus(
            when (command) {
                VoiceWorkflowCommand.TRIGGER -> R.string.voice_trigger_received
                VoiceWorkflowCommand.ALWAYS_ON -> R.string.voice_always_on_received
            },
        )
        progress.visibility = View.GONE
        handler.postDelayed({ finish() }, RESULT_MESSAGE_MS)
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

    private fun cleanupEmbeddedRecognizer() {
        recognizer?.let { active ->
            runCatching { active.cancel() }
            runCatching { active.destroy() }
        }
        recognizer = null
    }

    private fun clearRecognitionTimeouts() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(processingTimeout)
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
        clearRecognitionTimeouts()
        cleanupEmbeddedRecognizer()
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            showStatus(R.string.voice_permission_denied_fallback)
            handler.postDelayed({ launchSystemRecognizer() }, FALLBACK_MESSAGE_MS)
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

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SYSTEM_RECOGNIZER, systemRecognizerActive)
        outState.putBoolean(STATE_TERMINAL, terminal)
        outState.putBoolean(STATE_PERMISSION_REQUEST, permissionRequestActive)
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

    companion object {
        private const val REQUEST_RECORD_AUDIO = 4101
        private const val REQUEST_SYSTEM_RECOGNIZER = 4102
        private const val LANGUAGE_ZH_CN = "zh-CN"
        private const val MAX_RESULTS = 3
        private const val LISTEN_TIMEOUT_MS = 7_500L
        private const val PROCESSING_TIMEOUT_MS = 8_000L
        private const val FALLBACK_MESSAGE_MS = 550L
        private const val RESULT_MESSAGE_MS = 1_000L
        private const val STATE_SYSTEM_RECOGNIZER = "system_recognizer_active"
        private const val STATE_TERMINAL = "terminal"
        private const val STATE_PERMISSION_REQUEST = "permission_request_active"
    }
}
