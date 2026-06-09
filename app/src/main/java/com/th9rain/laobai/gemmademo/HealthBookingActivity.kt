package com.th9rain.laobai.gemmademo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class HealthBookingActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var questionView: TextView
    private lateinit var startButton: android.widget.Button
    private lateinit var answerButton: android.widget.Button
    private lateinit var hospitalInput: EditText
    private lateinit var departmentInput: EditText
    private lateinit var dateInput: EditText
    private lateinit var materialsInput: EditText

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(AgentBroadcasts.EXTRA_PAGE) != AgentBroadcasts.PAGE_HEALTH) return
            statusView.text = intent.getStringExtra(AgentBroadcasts.EXTRA_STATUS).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = demoRoot()
        root.addView(demoTitle("北京医院挂号助手", R.id.health_title))
        root.addView(demoDescription("Trigger 模式：用户说“我胃不舒服，帮我挂号”。老白会问询、检索本地知识库，可选调用云端 planner，然后操作挂号页。"))
        statusView = statusBox("等待用户 Trigger 或无障碍服务接管...", R.id.health_status_text)
        root.addView(statusView)
        root.addView(sectionLabel("用户触发语"))
        root.addView(demoInput("我胃不舒服，帮我挂号", R.id.health_symptom_input).apply {
            setText("我胃不舒服，帮我挂号")
        })
        startButton = primaryButton("开始问询", R.id.health_start_button).apply {
            setOnClickListener { showQuestion() }
        }
        root.addView(startButton)
        questionView = statusBox("端侧问询会显示在这里", R.id.health_question_text).apply {
            visibility = View.GONE
        }
        answerButton = primaryButton("回答：两天了，有点恶心，帮我挂号", R.id.health_answer_button).apply {
            visibility = View.GONE
            setOnClickListener { showBookingFields() }
        }
        root.addView(questionView)
        root.addView(answerButton)
        root.addView(sectionLabel("模拟挂号页"))
        hospitalInput = demoInput("医院", R.id.health_hospital_input)
        departmentInput = demoInput("科室", R.id.health_department_input)
        dateInput = demoInput("时间", R.id.health_date_input)
        materialsInput = demoInput("准备材料", R.id.health_materials_input)
        root.addView(hospitalInput)
        root.addView(departmentInput)
        root.addView(dateInput)
        root.addView(materialsInput)
        root.addView(dangerButton("确认挂号 / 支付 / 验证码（安全守卫会停在这里）", R.id.health_confirm_button))
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

    private fun showQuestion() {
        statusView.text = "端侧问询：先确认持续时间和危险信号，不做诊断。"
        startButton.visibility = View.GONE
        questionView.text = "您这个不舒服有多久了？有没有发热、呕吐、胸痛这些情况？"
        questionView.visibility = View.VISIBLE
        answerButton.visibility = View.VISIBLE
    }

    private fun showBookingFields() {
        questionView.visibility = View.GONE
        answerButton.visibility = View.GONE
        statusView.text = "已收到回答，等待老白 planner 生成挂号计划..."
    }
}
