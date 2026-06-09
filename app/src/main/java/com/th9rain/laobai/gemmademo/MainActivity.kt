package com.th9rain.laobai.gemmademo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LaoBaiApp()
        }
    }
}

private val AppLightColors = lightColorScheme(
    primary = Color(0xFF235B4E),
    secondary = Color(0xFFD9A441),
    tertiary = Color(0xFF4F67A5),
    background = Color(0xFFF4F7F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7ECE7),
    onPrimary = Color.White,
    onSecondary = Color(0xFF1F1B13),
    onBackground = Color(0xFF1D1F1C),
    onSurface = Color(0xFF1D1F1C),
)

@Composable
fun LaoBaiApp() {
    MaterialTheme(colorScheme = AppLightColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HomeScreen()
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(AgentPrefs.FILE, Context.MODE_PRIVATE)
    }
    var cloudEnabled by remember { mutableStateOf(prefs.getBoolean(AgentPrefs.KEY_CLOUD_ENABLED, false)) }
    var apiKey by remember { mutableStateOf(prefs.getString(AgentPrefs.KEY_ARK_API_KEY, "").orEmpty()) }
    var accessibilityEnabled by remember { mutableStateOf(context.isLaoBaiAccessibilityEnabled()) }

    LaunchedEffect(cloudEnabled, apiKey) {
        prefs.edit()
            .putBoolean(AgentPrefs.KEY_CLOUD_ENABLED, cloudEnabled)
            .putString(AgentPrefs.KEY_ARK_API_KEY, apiKey)
            .apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header()
        DemoCard(title = "1. 启用手机操作能力", icon = Icons.Filled.AccessibilityNew) {
            StatusLine(
                label = if (accessibilityEnabled) "无障碍服务：已开启" else "无障碍服务：未开启",
                detail = "这是 APK 真正操作手机 UI 的核心能力。启用后，老白能读取当前页面节点、填写输入框、点击安全按钮，并在提交/支付前停止。",
                positive = accessibilityEnabled,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("去系统设置启用")
                }
                OutlinedButton(onClick = { accessibilityEnabled = context.isLaoBaiAccessibilityEnabled() }) {
                    Text("刷新")
                }
            }
        }
        DemoCard(title = "2. 模型与 Planner", icon = Icons.Filled.Memory) {
            StatusLine(
                label = "端侧 Gemma：模型槽 + 本地工作流兜底",
                detail = "当前演示执行由本地 workflow 和 embedding 知识库保证可跑；后续可替换为 Gemma LiteRT / MediaPipe 权重，并显示真实加载状态。",
                positive = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用 Ark 云端 planner 替身", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("只在 Trigger 挂号规划时发送脱敏摘要；Key 保存在本机，不提交到仓库。", fontSize = 12.sp, color = Color(0xFF66706A))
                }
                Switch(checked = cloudEnabled, onCheckedChange = { cloudEnabled = it })
            }
            if (cloudEnabled) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ark API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }
        DemoCard(title = "3. 两个可录屏 Demo", icon = Icons.Filled.Security) {
            Text(
                "先启用无障碍服务，再打开下面页面。老白会在真实 Android 页面里执行输入和点击，不再只是首页里的状态切换。",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFF4D574F),
            )
            Button(
                onClick = { context.startActivity(Intent(context, FormDemoActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Demo 1：Always-on 自动填表")
            }
            Button(
                onClick = { context.startActivity(Intent(context, HealthBookingActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Filled.HealthAndSafety, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Demo 2：Trigger 看病挂号")
            }
        }
        FooterNote()
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "老白 Gemma Agent",
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "面向老人手机操作的端侧 Agent 演示 APK：Always-on 填表 + Trigger 看病挂号。",
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = Color(0xFF4D574F),
        )
    }
}

@Composable
private fun DemoCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, detail: String, positive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (positive) Color(0xFFEAF4EF) else Color(0xFFFFF5DF))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (positive) MaterialTheme.colorScheme.primary else Color(0xFFD19B2A))
                .border(1.dp, Color.White, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, color = Color(0xFF66706A), lineHeight = 17.sp)
        }
    }
}

@Composable
private fun FooterNote() {
    Text(
        "隐私边界：原始页面、身份证、完整手机号、验证码、病历原文默认不上云。云端 planner 只接收脱敏症状摘要和本地建议，用于演示复杂规划。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = Color(0xFF66706A),
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

private fun Context.isLaoBaiAccessibilityEnabled(): Boolean {
    val expected = "$packageName/${LaoBaiAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    if (enabled.isNullOrBlank()) return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    return splitter.any { it.equals(expected, ignoreCase = true) }
}
