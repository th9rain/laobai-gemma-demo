package com.th9rain.laobai.gemmademo

import android.os.Bundle
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
            DemoHome()
        }
    }
}

@Composable
private fun DemoHome() {
    var formState by remember {
        mutableStateOf(AlwaysOnFormWorkflow.initialState())
    }
    var healthState by remember {
        mutableStateOf(HealthBookingWorkflow.initialState())
    }
    var apiKey by remember { mutableStateOf("") }
    var cloudEnabled by remember { mutableStateOf(false) }
    var gemmaLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header()
        GemmaStatusCard(
            gemmaLoaded = gemmaLoaded,
            onToggle = { gemmaLoaded = it },
            cloudEnabled = cloudEnabled,
            apiKey = apiKey,
            onCloudToggle = { cloudEnabled = it },
            onApiKeyChange = { apiKey = it },
        )
        FormDemoCard(
            state = formState,
            onOpenForm = { formState = AlwaysOnFormWorkflow.openForm(formState) },
            onConfirm = { formState = AlwaysOnFormWorkflow.confirmFill(formState) },
            onReset = { formState = AlwaysOnFormWorkflow.reset() },
        )
        HealthDemoCard(
            state = healthState,
            onTriggerChange = { healthState = healthState.copy(triggerText = it) },
            onTrigger = { healthState = HealthBookingWorkflow.trigger(healthState.triggerText) },
            onPlan = {
                scope.launch {
                    healthState = healthState.copy(stage = HealthStage.Planning)
                    healthState = HealthBookingWorkflow.plan(
                        context = context,
                        state = healthState,
                        cloudConfig = CloudPlannerConfig(apiKey = apiKey, enabled = cloudEnabled),
                    )
                }
            },
            onReset = { healthState = HealthBookingWorkflow.reset() },
        )
        FooterNote()
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "老白 Gemma Demo",
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "面向老人手机操作的端侧 workflow 演示 APK：主动填表 + 触发式看病挂号。",
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = Color(0xFF4D574F),
        )
    }
}

@Composable
private fun GemmaStatusCard(
    gemmaLoaded: Boolean,
    onToggle: (Boolean) -> Unit,
    cloudEnabled: Boolean,
    apiKey: String,
    onCloudToggle: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
) {
    DemoCard(title = "本地 Gemma 状态", icon = Icons.Filled.Memory) {
        StatusLine(
            label = if (gemmaLoaded) "Gemma 端侧模型：已加载" else "Gemma 端侧模型：未加载",
            detail = if (gemmaLoaded) {
                "演示会显示为本地 Gemma workflow 驱动。"
            } else {
                "当前仍可跑通固定 workflow；Gemma 权重可通过 Release 附件或本地导入。"
            },
            positive = gemmaLoaded,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("录屏时显示 Gemma 已加载", fontSize = 15.sp)
            Switch(checked = gemmaLoaded, onCheckedChange = onToggle)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("演示云端 Planner API", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("可选。不开启时，两条 demo 都走本地 workflow。", fontSize = 12.sp, color = Color(0xFF66706A))
            }
            Switch(checked = cloudEnabled, onCheckedChange = onCloudToggle)
        }
        if (cloudEnabled) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ark API Key，不会写入仓库") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}

@Composable
private fun FormDemoCard(
    state: FormDemoState,
    onOpenForm: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
) {
    DemoCard(title = "Demo 1：Always-on 固定表单", icon = Icons.Filled.Description) {
        Text(
            "北京市朝阳区社区智慧课堂报名表",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF26312C),
        )
        FormPreview(state.fields)
        if (state.promptVisible) {
            AlertBox(
                title = "老白提醒",
                text = "我看到这是报名表，要不要帮您填写常用信息？"
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onOpenForm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("模拟打开表单")
            }
            OutlinedButton(onClick = onReset) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
            }
        }
        if (state.promptVisible) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("同意，帮我填写")
            }
        }
        if (state.stoppedBeforeSubmit) {
            AlertBox(
                title = "已停在提交前",
                text = "老白不会自动点击「提交报名」。请老人自己确认后再手动提交。",
                highRisk = true,
            )
        }
        StepTimeline(state.steps)
    }
}

@Composable
private fun HealthDemoCard(
    state: HealthDemoState,
    onTriggerChange: (String) -> Unit,
    onTrigger: () -> Unit,
    onPlan: () -> Unit,
    onReset: () -> Unit,
) {
    DemoCard(title = "Demo 2：Trigger 看病挂号", icon = Icons.Filled.HealthAndSafety) {
        OutlinedTextField(
            value = state.triggerText,
            onValueChange = onTriggerChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("用户触发语") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onTrigger,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Trigger")
            }
            OutlinedButton(onClick = onReset) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
            }
        }
        if (state.stage == HealthStage.Asking) {
            AlertBox(title = "端侧问询", text = state.question)
            Button(
                onClick = onPlan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("回答：两天了，有点恶心，帮我挂号")
            }
        }
        if (state.stage == HealthStage.Planning) {
            AlertBox(title = "正在规划", text = "本地 Gemma workflow 正在检索知识库并生成挂号计划。")
        }
        if (state.bookingPageOpened) {
            BookingPreview(state)
        }
        StepTimeline(state.steps)
    }
}

@Composable
private fun FormPreview(fields: List<FormField>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF8FAF8))
            .border(1.dp, Color(0xFFD9E0DA), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        fields.forEach { field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    field.label,
                    modifier = Modifier.width(88.dp),
                    fontSize = 14.sp,
                    color = Color(0xFF5D665F),
                )
                Text(
                    field.value.ifBlank { "待填写" },
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (field.autofilled) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (field.autofilled) MaterialTheme.colorScheme.primary else Color(0xFF9AA39D),
                )
                if (field.autofilled) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        TextButton(onClick = {}, enabled = false, modifier = Modifier.align(Alignment.End)) {
            Text("提交报名")
        }
    }
}

@Composable
private fun BookingPreview(state: HealthDemoState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF8FAF8))
            .border(1.dp, Color(0xFFD9E0DA), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusLine(
            label = state.cloudPlannerStatus,
            detail = if (state.cloudPlannerUsed) "云端只参与演示规划，端侧仍执行固定安全 workflow。" else "知识库使用内置 embedding demo asset。",
            positive = true,
        )
        Text("模拟挂号页", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        DetailRow("医院", state.hospital)
        DetailRow("科室", state.department)
        DetailRow("时间", "明天上午")
        DetailRow("材料", "身份证、医保卡、既往病历")
        Text(
            state.planReason,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = Color(0xFF5D665F),
        )
        AlertBox(
            title = "已停在确认前",
            text = "老白不会自动确认挂号、支付或处理验证码。",
            highRisk = true,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.width(56.dp), color = Color(0xFF5D665F), fontSize = 14.sp)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepTimeline(steps: List<DemoStep>) {
    if (steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Workflow 轨迹", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(riskColor(step.risk)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(step.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(step.detail, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF5D665F))
                }
            }
        }
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
        Icon(
            if (positive) Icons.Filled.CheckCircle else Icons.Filled.Cloud,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.primary else Color(0xFF9A6A00),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, color = Color(0xFF66706A), lineHeight = 17.sp)
        }
    }
}

@Composable
private fun AlertBox(title: String, text: String, highRisk: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highRisk) Color(0xFFFFEBE7) else Color(0xFFEAF1FA))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Filled.Security,
            contentDescription = null,
            tint = if (highRisk) Color(0xFFB13A23) else Color(0xFF355E9B),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF3C4540))
        }
    }
}

@Composable
private fun FooterNote() {
    Text(
        "隐私边界：原始页面、身份证、手机号、验证码、病历原文不上传。本 APK 为比赛录屏 demo，使用固定 workflow 展示端侧 Agent 能力。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = Color(0xFF66706A),
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

private fun riskColor(risk: RiskLevel): Color {
    return when (risk) {
        RiskLevel.Low -> Color(0xFF235B4E)
        RiskLevel.Medium -> Color(0xFFD19B2A)
        RiskLevel.High -> Color(0xFFB13A23)
    }
}
