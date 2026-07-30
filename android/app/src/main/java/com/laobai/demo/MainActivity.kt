package com.laobai.demo

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        serviceStatus = findViewById(R.id.serviceStatus)

        findViewById<Button>(R.id.openAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.openAlwaysOn).setOnClickListener {
            startActivity(CaseActivity.createIntent(this, CaseActivity.CASE_ALWAYS_ON))
        }
        findViewById<Button>(R.id.openTrigger).setOnClickListener {
            startActivity(CaseActivity.createIntent(this, CaseActivity.CASE_TRIGGER))
        }
    }

    override fun onResume() {
        super.onResume()
        renderServiceStatus()
    }

    private fun renderServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        serviceStatus.text = getString(
            if (enabled) R.string.accessibility_enabled else R.string.accessibility_disabled,
        )
        serviceStatus.setTextColor(
            getColor(if (enabled) R.color.status_enabled else R.color.status_disabled),
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, LaoBaiAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        return enabled.split(':').any { component ->
            component.equals(expected, ignoreCase = true)
        }
    }
}
