package com.hassouni.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hassouni.tv.R
import com.hassouni.tv.data.*
import androidx.compose.ui.viewinterop.AndroidView
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private enum class Tab(val label: String, val icon: ImageVector) {
    STUDIO("المعاينة", Icons.Default.Sensors), QUALITY("الجودة", Icons.Default.Tune),
    OVERLAY("الطبقات", Icons.Default.Layers), STATS("التحليلات", Icons.Default.Analytics), SETTINGS("الإعدادات", Icons.Default.Settings)
}

@Composable
fun AppRoot(vm: MainViewModel, onWelcomeComplete: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.STUDIO) }
    var previewFullscreen by remember { mutableStateOf(false) }
    val previewShape by vm.previewShape.collectAsStateWithLifecycle()
    val captureDiagnostics by vm.capture.diagnostics.collectAsStateWithLifecycle()
    val previewRatio = when (previewShape) {
        PreviewShape.AUTO -> if (captureDiagnostics.previewWidth > 0 && captureDiagnostics.previewHeight > 0) captureDiagnostics.previewWidth.toFloat() / captureDiagnostics.previewHeight.toFloat() else 16f / 9f
        PreviewShape.LANDSCAPE_16_9 -> 16f / 9f
        PreviewShape.PORTRAIT_9_16 -> 9f / 16f
        PreviewShape.SQUARE_1_1 -> 1f
    }
    // TVSHERE_WELCOME_PATCH_V1: session-only; never persisted across app launches.
    var showWelcome by remember { mutableStateOf(true) }
    if (showWelcome) {
        WelcomeScreen {
            showWelcome = false
            onWelcomeComplete()
        }
        return
    }
    if (previewFullscreen) {
        FullscreenPreview(vm, previewRatio) { previewFullscreen = false }
        return
    }
    val msg by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.dismissMessage() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.values().forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = Violet.copy(.22f)))
                }
            }
        }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TVSHERE", color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("LIVE CAPTURE STUDIO", color = Violet, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    Box(Modifier.size(38.dp).clip(CircleShape).background(NeonBrush), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                androidx.compose.animation.Crossfade(tab, label = "tab") { currentTab ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (currentTab) {
                            Tab.STUDIO -> StudioScreen(vm) { previewFullscreen = true }
                            Tab.QUALITY -> QualityScreen(vm)
                            Tab.OVERLAY -> OverlayScreen(vm)
                            Tab.STATS -> StatsScreen(vm)
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(s: Long) = "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)

@Composable
private fun StudioScreen(vm: MainViewModel, onFullscreen: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sig by vm.signal.collectAsStateWithLifecycle()
    val error by vm.capture.error.collectAsStateWithLifecycle()
    val diagnostics by vm.capture.diagnostics.collectAsStateWithLifecycle()
    val t by vm.target.collectAsStateWithLifecycle()
    val previewShape by vm.previewShape.collectAsStateWithLifecycle()
    val sigColor = when (sig) { SignalState.CONNECTED -> Ok; SignalState.WEAK -> Warn; else -> Danger }

    Text("استوديو البث", style = MaterialTheme.typography.titleLarge, fontSize = 27.sp, color = MaterialTheme.colorScheme.onSurface)
    Text("معاينة HDMI مباشرة وتحكم كامل بوجهة الإرسال.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    PreviewShapeSelector(previewShape) { vm.setPreviewShape(it) }
    // TVSHERE_SCENE_EDITOR_V1
    SceneEditorSection(vm)
    val previewRatio = when (previewShape) {
        PreviewShape.AUTO -> if (diagnostics.previewWidth > 0 && diagnostics.previewHeight > 0) diagnostics.previewWidth.toFloat() / diagnostics.previewHeight.toFloat() else 16f / 9f
        PreviewShape.LANDSCAPE_16_9 -> 16f / 9f
        PreviewShape.PORTRAIT_9_16 -> 9f / 16f
        PreviewShape.SQUARE_1_1 -> 1f
    }
    // نسبة الإطار محلية للمعاينة فقط؛ أبعاد RTMP تبقى من إعداد الجودة.
    Box(Modifier.fillMaxWidth().aspectRatio(previewRatio).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx) },
            modifier = Modifier.fillMaxSize(),
            update = { vm.attachPreview(it) }
        )
        if (sig != SignalState.CONNECTED) {
            Text(
                if (sig == SignalState.WEAK) "تعذّر فتح كارت الالتقاط" else "وصّل الكارت بالهاتف عبر OTG",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        IconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(Color.Black.copy(.62f))) {
            Icon(Icons.Default.Fullscreen, "ملء الشاشة", tint = MaterialTheme.colorScheme.onSurface)
        }
        Text("مشاهدة فقط — دون وجهة خارجية", color = MaterialTheme.colorScheme.onSurface.copy(.82f), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(.55f)).padding(horizontal = 10.dp, vertical = 5.dp))
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(sigColor))
            Spacer(Modifier.width(8.dp))
            Text("حالة كارت الالتقاط: ${sig.label}", Modifier.weight(1f))
            if (sig == SignalState.CONNECTED) Text("READY", color = Ok, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        error?.let { Text(it, color = Warn, fontSize = 12.sp) }
        if (sig != SignalState.CONNECTED) {
            NeonButton("إعادة فحص كارت الالتقاط", Modifier.fillMaxWidth().padding(top = 8.dp), Magenta) {
                vm.retryCapture()
            }
        }
    }
    CaptureDiagnosticsCard(diagnostics)
    val isStreaming by vm.broadcast.streaming.collectAsStateWithLifecycle()
    val connected by vm.broadcast.connected.collectAsStateWithLifecycle()
    val streamStatus by vm.broadcast.status.collectAsStateWithLifecycle()
    val audioAvailable by vm.broadcast.audioAvailable.collectAsStateWithLifecycle()
    val audioMessage by vm.broadcast.audioMessage.collectAsStateWithLifecycle()
    val audio by vm.audioS.collectAsStateWithLifecycle()
    val requestAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleStream() else vm.message.value = "يلزم إذن الصوت ليتمكن Android من قراءة مدخل USB الصوتي."
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("محرك Aram / Hassouni RTMP", Modifier.weight(1f), color = Cyan, fontWeight = FontWeight.Bold)
            Text(if (connected) "LIVE" else if (isStreaming) "CONNECTING" else "READY", color = if (connected) Ok else if (isStreaming) Warn else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        Text(streamStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        NeonButton(if (isStreaming) "إيقاف البث" else "بدء البث المباشر", Modifier.fillMaxWidth().padding(top = 10.dp), if (isStreaming) Magenta else Cyan) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (isStreaming || granted) vm.toggleStream() else requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("وجهة البث", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("اضغط شعار المنصة لفتح تطبيقها، ثم ارجع لإدخال بيانات الجلسة.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            Platform.values().forEach { p ->
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                        .background(if (t.platform == p) Violet.copy(.18f) else MaterialTheme.colorScheme.onSurface.copy(.035f))
                        .clickable {
                            vm.setTarget(t.copy(platform = p))
                            if (p == Platform.CUSTOM) vm.message.value = "أدخل بيانات خادم RTMP المخصص."
                            else vm.message.value = if (openPlatformApp(context, p)) "تم فتح ${p.label}. اتبع دليل الإعداد في صفحة الإعدادات ثم ارجع إلى Tvshere." else "لم نعثر على تطبيق ${p.label} مثبتًا على الهاتف."
                        }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PlatformLogo(p, Modifier.size(38.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(when (p) { Platform.TELEGRAM -> "Telegram"; Platform.TIKTOK -> "TikTok"; Platform.CUSTOM -> "RTMP" },
                        color = if (t.platform == p) Cyan else MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    if (t.platform == p) Text("محدد", color = Violet, fontSize = 9.sp)
                }
            }
        }
        OutlinedTextField(t.url, { vm.setTarget(t.copy(url = it)) }, Modifier.fillMaxWidth(), label = { Text("رابط الخادم (RTMP / RTMPS)") }, placeholder = { Text(t.platform.hint) }, singleLine = true)
        OutlinedTextField(t.key, { vm.setTarget(t.copy(key = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("مفتاح البث (Stream Key)") }, singleLine = true,
            visualTransformation = if (t.showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton({ vm.setTarget(t.copy(showKey = !t.showKey)) }) { Icon(if (t.showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } })
        Text("يجب إدخال بيانات جلسة البث الحالية التي تعطيها المنصة. لا تُنشئ هذه البيانات تلقائيًا ولا تُرسل إلى أي طرف آخر.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الصوت عبر USB / HDMI", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (audioAvailable) Ok else Warn))
        }
        Text(audioMessage, color = if (audioAvailable) Ok else Warn, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("كتم الصوت", Modifier.weight(1f)); Switch(audio.tvMuted, { vm.setAudio(audio.copy(tvMuted = it)) })
        }
        Text("مستوى الصوت: ${(audio.tvVolume * 100).toInt()}%")
        Slider(audio.tvVolume, { vm.setAudio(audio.copy(tvVolume = it)) }, valueRange = 0f..1.5f)
        Text("مصدر الصوت USB فقط؛ لا يستخدم التطبيق ميكروفون الهاتف كبديل.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
    // TVSHERE_CAPABILITIES_SECTION_PATCH_V1
    CapabilitiesSection()
}

@Composable
private fun QualityScreen(vm: MainViewModel) {
    val q by vm.quality.collectAsStateWithLifecycle()
    Text("إعدادات الجودة", style = MaterialTheme.typography.titleLarge)
    Text("تُطبّق هذه القيم عند بدء البث التالي. تغييرها أثناء البث يتطلب إيقافه أولاً.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("دقة تلقائية (720p احترازية)", Modifier.weight(1f)); Switch(q.autoRes, { vm.setQuality(q.copy(autoRes = it)) }) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Res.values().forEach { r -> FilterChip(q.res == r && !q.autoRes, { vm.setQuality(q.copy(res = r, autoRes = false, bitrateKbps = minOf(q.bitrateKbps, r.maxKbps))) }, { Text(r.label, fontSize = 11.sp) }) } }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("معدل الإطارات"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(60, 30, 24).forEach { f -> FilterChip(q.fps == f, { vm.setQuality(q.copy(fps = f)) }, { Text("$f fps") }) } }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        val maximumBitrate = (if (q.autoRes) Res.P720 else q.res).maxKbps.coerceAtLeast(300).toFloat()
        val visibleBitrate = q.bitrateKbps.coerceIn(300, maximumBitrate.toInt())
        Text("معدل البت: $visibleBitrate kbps")
        Slider(visibleBitrate.toFloat(), { vm.setQuality(q.copy(bitrateKbps = it.toInt().coerceIn(300, maximumBitrate.toInt()))) }, valueRange = 300f..maximumBitrate)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("تكيّف معدل البت عند الازدحام/فقد الإطارات", Modifier.weight(1f), fontSize = 12.sp); Switch(q.adaptive, { vm.setQuality(q.copy(adaptive = it)) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("وضع التأخير المنخفض جداً", Modifier.weight(1f)); Switch(q.ultraLowLatency, { vm.setQuality(q.copy(ultraLowLatency = it)) }) }
    }
}

@Composable
private fun OverlayScreen(vm: MainViewModel) {
    val o by vm.overlayS.collectAsStateWithLifecycle()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { u -> vm.setOverlay(o.copy(watermarkUri = u?.toString())) }
    Text("استوديو الطبقات", style = MaterialTheme.typography.titleLarge)
    Text("تُركّب الطبقات على خرج الفيديو عبر مرشح رسومي قبل الترميز، وتظهر في المعاينة والبث.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    GlassCard(Modifier.fillMaxWidth()) {
        Text("الشعار (Watermark)", style = MaterialTheme.typography.titleMedium)
        NeonButton(if (o.watermarkUri == null) "اختيار شعار" else "تغيير الشعار") { pick.launch("image/*") }
        Text("الشفافية"); Slider(o.watermarkOpacity, { vm.setOverlay(o.copy(watermarkOpacity = it)) })
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("شريط نتيجة المباراة", Modifier.weight(1f), fontWeight = FontWeight.Bold); Switch(o.scoreEnabled, { vm.setOverlay(o.copy(scoreEnabled = it)) }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(o.home, { vm.setOverlay(o.copy(home = it)) }, Modifier.weight(1f), label = { Text("المضيف") }, singleLine = true)
            OutlinedTextField(o.away, { vm.setOverlay(o.copy(away = it)) }, Modifier.weight(1f), label = { Text("الضيف") }, singleLine = true)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonButton("−", color = Magenta) { vm.setOverlay(o.copy(homeScore = maxOf(0, o.homeScore - 1))) }
            Text("${o.homeScore}", fontSize = 20.sp); NeonButton("+") { vm.setOverlay(o.copy(homeScore = o.homeScore + 1)) }
            Spacer(Modifier.weight(1f))
            NeonButton("−", color = Magenta) { vm.setOverlay(o.copy(awayScore = maxOf(0, o.awayScore - 1))) }
            Text("${o.awayScore}", fontSize = 20.sp); NeonButton("+") { vm.setOverlay(o.copy(awayScore = o.awayScore + 1)) }
        }
        OutlinedTextField(o.minute, { vm.setOverlay(o.copy(minute = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("الدقيقة") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("شريط نصي متحرك (Ticker)", Modifier.weight(1f), fontWeight = FontWeight.Bold); Switch(o.tickerEnabled, { vm.setOverlay(o.copy(tickerEnabled = it)) }) }
        OutlinedTextField(o.tickerText, { vm.setOverlay(o.copy(tickerText = it)) }, Modifier.fillMaxWidth(), label = { Text("نص التنبيه للمتابعين") })
    }
}

@Composable
private fun StatCard(title: String, value: String, color: Color, modifier: Modifier = Modifier, progress: Float? = null) {
    GlassCard(modifier) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        progress?.let { LinearProgressIndicator(progress = it.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = color) }
    }
}

@Composable
private fun StatsScreen(vm: MainViewModel) {
    val s by vm.stats.collectAsStateWithLifecycle()
    Text("مراقبة موارد الهاتف", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("المعالج CPU", "${s.cpu}%", if (s.cpu > 80) Danger else Violet, Modifier.weight(1f), s.cpu / 100f)
        StatCard("ذاكرة التطبيق RAM", "${s.ramMb} MB", Magenta, Modifier.weight(1f))
    }
    val temperatureText = if (s.tempC >= 0f) "%.1f °C".format(s.tempC) else "غير متاح"
    StatCard("حرارة البطارية", temperatureText,
        if (s.tempC > 42) Danger else if (s.tempC > 38) Warn else Ok,
        Modifier.fillMaxWidth(), if (s.tempC >= 0f) (s.tempC / 50f) else null)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("معدل إرسال البث", if (s.bitrateKbps > 0) "${s.bitrateKbps} kbps" else "—", Cyan, Modifier.weight(1f))
        StatCard("إطارات مفقودة", "${s.dropped}", if (s.dropped > 0) Warn else Ok, Modifier.weight(1f))
    }
    StatCard("مدة البث", fmt(s.elapsedSec), Magenta, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("إطارات فيديو مرسلة", s.sentVideoFrames.toString(), Cyan, Modifier.weight(1f))
        StatCard("إطارات صوت مرسلة", s.sentAudioFrames.toString(), Magenta, Modifier.weight(1f))
    }
    StatCard("البيانات المرسلة", "%.2f MB".format(s.bytesSent / (1024f * 1024f)), Violet, Modifier.fillMaxWidth())
    Text("عدد المشاهدين لا توفره واجهة RTMP العامة؛ لن نعرض رقمًا تقديريًا على أنه حقيقي.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

@Composable
private fun PhaseNotice(message: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("تنبيه الإصدار التجريبي", color = Warn, fontWeight = FontWeight.Bold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.message.value = if (granted) "تم السماح بإشعارات Tvshere." else "لم يُمنح إذن الإشعارات. يمكنك تفعيله من إعدادات Android."
    }
    val notificationAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    Text("الإعدادات", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Text("كل ما تحتاجه لضبط تجربة الالتقاط والبث.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

    DeveloperCard {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ro_gy"))) }
            .onFailure { vm.message.value = "تعذر فتح Telegram على هذا الجهاز." }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الإشعارات", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (notificationAllowed) "مسموح" else "غير مفعّل", color = if (notificationAllowed) Ok else Warn, fontSize = 11.sp)
        }
        Text("إشعارات نظام Android عند اكتشاف كارت الالتقاط، بدء الاتصال، فشل البث أو انقطاعه، وتغيّر حالة صوت USB.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text("قنوات USB والصوت والبث مستقلة؛ يمكنك التحكم بكل فئة من صفحة إشعارات التطبيق في Android.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        NeonButton(if (notificationAllowed) "فتح إعدادات إشعارات التطبيق" else "السماح بإشعارات التطبيق", Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }.onFailure { vm.message.value = "افتح إعدادات Android ثم الإشعارات ثم Tvshere." }
        }
    }

    val darkTheme = LocalDarkTheme.current
    val setDarkTheme = LocalSetDarkTheme.current
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("المظهر", style = MaterialTheme.typography.titleMedium)
                Text(if (darkTheme) "الوضع الداكن" else "الوضع الفاتح", color = Violet, fontSize = 11.sp)
            }
            Switch(checked = darkTheme, onCheckedChange = setDarkTheme)
        }
        Text("يُحفظ اختيارك على هذا الجهاز ويُطبّق على واجهات التطبيق.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }

    Text("دليل إعداد وجهات البث", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Text("افتح المنصة من البطاقة، استخرج بيانات جلستك، ثم عد إلى شاشة الاستوديو والصق العنوان والمفتاح.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    PlatformGuideCard(
        platform = Platform.TELEGRAM,
        steps = listOf(
            "افتح القناة التي تديرها أو المجموعة المطلوبة في Telegram.",
            "من صفحة القناة اختر Live Stream، أو اختر Video Chat للمجموعة.",
            "اختر البث باستخدام تطبيق خارجي / Stream with… لعرض Server URL وStream Key.",
            "انسخ العنوان والمفتاح، ثم ارجع إلى Tvshere وألصقهما في حقول وجهة البث.",
            "بعد بدء الإرسال من Tvshere، ارجع إلى Telegram وأكّد بدء البث إذا ظهر زر Connect أو Start Streaming."
        ),
        note = "تحتاج إلى صلاحية مناسبة في القناة أو المجموعة. احتفظ بمفتاح البث سرّيًا.",
        onOpen = { openPlatformApp(ctx, Platform.TELEGRAM) }
    )
    PlatformGuideCard(
        platform = Platform.TIKTOK,
        steps = listOf(
            "افتح TikTok واضغط زر + ثم انتقل إلى LIVE.",
            "ابحث عن Stream Key أو خيار البث من الكمبيوتر / برنامج بث، إذا كان متاحًا لحسابك.",
            "افتح إعداد البث وأنشئ جلسة جديدة، ثم انسخ Server URL وStream Key.",
            "ارجع إلى Tvshere والصق بيانات الجلسة ثم ابدأ البث."
        ),
        note = "خيار RTMP أو Stream Key غير متاح لكل الحسابات أو المناطق. قد تتغير البيانات من جلسة إلى أخرى؛ أنشئ بيانات جديدة لكل بث ولا تشارك المفتاح.",
        onOpen = { openPlatformApp(ctx, Platform.TIKTOK) }
    )
    PlatformGuideCard(
        platform = Platform.CUSTOM,
        steps = listOf(
            "افتح لوحة تحكم مزود خدمة البث أو الخادم الذي تملكه.",
            "أنشئ وجهة RTMP/RTMPS وانسخ عنوان الخادم ومفتاح البث.",
            "الصق البيانات في حقول Tvshere ثم اختبر الاتصال قبل مشاركة الرابط مع جمهورك."
        ),
        note = "يجب أن يبدأ عنوان الخادم بـ rtmp:// أو rtmps://، ويظل مفتاح البث خاصًا بك.",
        onOpen = null
    )

    BroadcastSafetyGuide()
    GlassCard(Modifier.fillMaxWidth()) {
        Text("حول Tvshere", style = MaterialTheme.typography.titleMedium, color = Cyan)
        Text("حسوني Tv — إصدار 1.1.0 RC1", color = MaterialTheme.colorScheme.onSurface)
        Text("التطبيق يرسل إلى وجهة RTMP واحدة في المرة الواحدة. توفر المنصة لمفتاح البث وشروط الأهلية يخضعان لسياسات المنصة نفسها.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}
