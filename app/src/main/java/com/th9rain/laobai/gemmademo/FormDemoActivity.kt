package com.th9rain.laobai.gemmademo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class FormDemoActivity : ComponentActivity() {
    private lateinit var statusView: android.widget.TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(AgentBroadcasts.EXTRA_PAGE) != AgentBroadcasts.PAGE_FORM) return
            statusView.text = intent.getStringExtra(AgentBroadcasts.EXTRA_STATUS).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = demoRoot()
        root.addView(demoTitle("北京市朝阳区社区智慧课堂报名表", R.id.form_title))
        root.addView(demoDescription("这是一个真实 Android 页面。启用无障碍服务后，老白会主动识别表单并填写本地记忆中的虚拟资料。"))
        statusView = statusBox("等待老白 Always-on Sentinel 识别当前表单...")
        root.addView(statusView)
        root.addView(sectionLabel("报名信息"))
        root.addView(demoInput("姓名", R.id.form_name_input))
        root.addView(demoInput("年龄段", R.id.form_age_input))
        root.addView(demoInput("手机号", R.id.form_phone_input))
        root.addView(demoInput("居住区域", R.id.form_area_input))
        root.addView(demoInput("紧急联系人", R.id.form_contact_input))
        root.addView(demoInput("报名课程", R.id.form_course_input))
        root.addView(dangerButton("提交报名（安全守卫会停在这里）", R.id.form_submit_button))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(AgentBroadcasts.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }
}
