package com.laobai.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ModelTraceActivity : Activity() {
    private lateinit var traceList: LinearLayout
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_trace)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        traceList = findViewById(R.id.traceList)
        emptyView = findViewById(R.id.traceEmpty)
        findViewById<Button>(R.id.traceBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        renderSession()
    }

    private fun renderSession() {
        traceList.removeAllViews()
        val session = ModelTraceStore.latestSession(this)
        val entries = session?.entries.orEmpty()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.traceSubtitle).text = if (session == null) {
            "运行一次工作流后，可在这里逐条查看模型输入和原始输出。"
        } else {
            "${session.demoCase.displayName} · ${formatTime(session.startedAtMs)} · ${entries.size} 次调用"
        }
        entries.forEachIndexed { index, entry -> traceList.addView(traceCard(index, entry)) }
    }

    private fun traceCard(index: Int, entry: ModelTraceEntry): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(dp(1), getColor(R.color.panel_border))
                cornerRadius = dp(14).toFloat()
            }
        }

        container.addView(TextView(this).apply {
            text = "${index + 1}. ${entry.title}"
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        }, matchWrap())

        container.addView(TextView(this).apply {
            text = buildString {
                append(entry.source.displayName)
                append("\n模型：${entry.modelName}")
                if (entry.backend.isNotBlank()) append("\n运行：${entry.backend}")
                if (entry.elapsedMs > 0L) append(" · ${entry.elapsedMs} ms")
                append("\n状态：${entry.status}")
            }
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(7), 0, dp(12))
        }, matchWrap())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(traceButton("查看输入") { showTraceDialog(entry, showInput = true) })
        buttons.addView(traceButton("查看输出") { showTraceDialog(entry, showInput = false) })
        container.addView(buttons, matchWrap())

        return container.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun traceButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_red))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44),
        ).apply { marginStart = dp(8) }
    }

    private fun showTraceDialog(entry: ModelTraceEntry, showInput: Boolean) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        var decodedBitmap: Bitmap? = null
        if (showInput) {
            entry.screenshotPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { screenshot ->
                    decodedBitmap = decodePreview(screenshot)
                    decodedBitmap?.let { bitmap ->
                        content.addView(ImageView(this).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            contentDescription = "本次端侧 VQA 输入截图"
                            background = GradientDrawable().apply {
                                setColor(Color.rgb(245, 246, 248))
                                cornerRadius = dp(8).toFloat()
                            }
                        }, matchWrap())
                    }
                }
        }
        content.addView(TextView(this).apply {
            text = if (showInput) entry.inputText else entry.outputText
            textSize = 12f
            setTextColor(getColor(R.color.text_primary))
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(0, if (decodedBitmap != null) dp(12) else 0, 0, dp(8))
        }, matchWrap())

        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${entry.title} · ${if (showInput) "输入" else "输出"}")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnDismissListener { decodedBitmap?.recycle() }
        dialog.show()
    }

    private fun decodePreview(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1_200 || bounds.outHeight / sample > 2_400) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun formatTime(timestampMs: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestampMs))

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ModelTraceActivity::class.java)
    }
}
