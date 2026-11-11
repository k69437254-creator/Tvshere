package com.hassouni.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hassouni.tv.capture.CaptureDiagnostics
import com.hassouni.tv.data.PreviewShape
import com.hassouni.tv.data.Platform
import com.hassouni.tv.ui.MainViewModel

@Composable
fun PlatformLogo(platform: Platform, modifier: Modifier = Modifier) {
    when (platform) {
        Platform.TELEGRAM -> Image(
            painter = painterResource(com.hassouni.tv.R.drawable.brand_telegram),
            contentDescription = "Telegram",
            modifier = modifier
        )
        Platform.TIKTOK -> Image(
            painter = painterResource(com.hassouni.tv.R.drawable.brand_tiktok),
            contentDescription = "TikTok",
            modifier = modifier
        )
        Platform.CUSTOM -> Canvas(modifier) {
            val u = size.minDimension / 24f
            drawCircle(Color(0xFF7654D8), radius = 12f * u, center = Offset(size.width / 2f, size.height / 2f))
            for (y in listOf(7f, 12f, 17f)) {
                drawRoundRect(Color.White, topLeft = Offset(6f * u, (y - 1f) * u),
                    size = androidx.compose.ui.geometry.Size(12f * u, 2f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * u))
            }
            drawCircle(Color(0xFFB8F0FF), 1.1f * u, Offset(8f * u, 7f * u))
            drawCircle(Color(0xFFB8F0FF), 1.1f * u, Offset(8f * u, 12f * u))
            drawCircle(Color(0xFFB8F0FF), 1.1f * u, Offset(8f * u, 17f * u))
        }
    }
}

@Composable
fun PreviewShapeSelector(value: PreviewShape, onChange: (PreviewShape) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("نسبة إطار المعاينة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text("يغيّر هذا الاختيار مساحة المشاهدة المحلية فقط؛ ولا يغيّر أبعاد RTMP.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreviewShape.values().toList().chunked(2).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.forEach { shape ->
                        FilterChip(
                            selected = value == shape,
                            onClick = { onChange(shape) },
                            label = { Text(shape.label, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FullscreenPreview(vm: MainViewModel, ratioValue: Float, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    BackHandler(enabled = true, onBack = onExit)
    val ratio = ratioValue.coerceIn(0.5f, 1.8f)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clickable { controlsVisible = !controlsVisible }) {
        val availableRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val frame = if (ratio >= availableRatio) Modifier.fillMaxWidth().aspectRatio(ratio)
            else Modifier.fillMaxHeight().aspectRatio(ratio)
        AndroidView(
            factory = { SurfaceView(it) },
            modifier = frame.align(Alignment.Center),
            update = { vm.attachPreview(it) }
        )
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                IconButton(onClick = onExit, modifier = Modifier.align(Alignment.TopStart).padding(14.dp).clip(CircleShape).background(Color.Black.copy(.60f))) {
                    Icon(Icons.Default.FullscreenExit, "إنهاء ملء الشاشة", tint = Color.White)
                }
                Text("اضغط لإظهار عناصر التحكم أو إخفائها · مشاهدة فقط", color = Color.White.copy(.85f), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp).clip(RoundedCornerShape(30.dp)).background(Color.Black.copy(.58f)).padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
fun CaptureDiagnosticsCard(data: CaptureDiagnostics) {
    fun stateColor(ok: Boolean) = if (ok) Ok else Warn
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الفحص الذكي للاتصال", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Info, null, tint = Violet)
        }
        DiagnosticLine("اكتشاف جهاز USB", data.usbDetected, stateColor(data.usbDetected))
        DiagnosticLine("واجهة فيديو UVC", data.uvcInterface == true, if (data.uvcInterface == false) Danger else stateColor(data.uvcInterface == true))
        DiagnosticLine("إذن وفتح جهاز USB", data.usbPermissionGranted == true, stateColor(data.usbPermissionGranted == true))
        DiagnosticLine("فتح كاميرا الالتقاط", data.cameraOpened, stateColor(data.cameraOpened))
        DiagnosticLine("استدعاء بدء المعاينة عاد دون استثناء", data.previewStartCallReturned, stateColor(data.previewStartCallReturned))
        if (data.supportedModes > 0) Text("أوضاع الفيديو المعلنة: ${data.supportedModes}", color = Cyan, fontSize = 11.sp)
        data.deviceName?.let { Text("الجهاز: $it", color = Color.LightGray, fontSize = 10.sp) }
        Text(data.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text("حدود الفحص: يثبت هذا المؤشر أن استدعاء startPreview عاد دون استثناء فقط؛ ولا يثبت وصول إطارات فيديو فعلية أو سلامة الكابل أو وجود إشارة HDMI فعالة.", color = Warn, fontSize = 10.sp)
    }
}

@Composable
private fun DiagnosticLine(label: String, passed: Boolean, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(if (passed) "تم" else "لم يثبت", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PlatformGuideCard(platform: Platform, steps: List<String>, note: String, onOpen: (() -> Boolean)?) {
    val launchMessage = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlatformLogo(platform, Modifier.size(38.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(when (platform) { Platform.TELEGRAM -> "Telegram Live"; Platform.TIKTOK -> "TikTok LIVE"; Platform.CUSTOM -> "خادم RTMP مخصص" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text("دليل الحصول على بيانات البث", color = Color.Gray, fontSize = 10.sp)
            }
        }
        steps.forEachIndexed { index, step ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Text("${index + 1}", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(20.dp))
                Text(step, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
            }
        }
        Text(note, color = Warn, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp))
        if (onOpen != null) {
            NeonButton("فتح ${if (platform == Platform.TELEGRAM) "Telegram" else "TikTok"}", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                launchMessage.value = if (onOpen()) "تم فتح التطبيق. أكمل إعداد البث ثم عد إلى Tvshere." else "التطبيق غير مثبت أو تعذر فتحه على هذا الجهاز."
            }
            launchMessage.value?.let { Text(it, color = if (it.startsWith("تم فتح")) Ok else Warn, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
fun BroadcastSafetyGuide() {
    val sections = listOf(
        "قبل بدء البث" to listOf(
            "راجع شروط البث المباشر وإرشادات المجتمع وأهلية حسابك؛ فقد تختلف حسب البلد ونوع الحساب.",
            "استخدم عنوانًا ووصفًا يعكسان المحتوى فعلًا، ولا تنتحل صفة شخص أو جهة.",
            "اختبر الصوت والصورة والاتصال قبل البث.",
            "احفظ Stream Key بسرية تامة، ولا تعرضه على الشاشة أو تشاركه."
        ),
        "أثناء البث: سلوك آمن" to listOf(
            "التزم بقواعد المنصة طوال البث، وأوقف أي مادة يظهر أنها تخالفها.",
            "لا تعرض بيانات شخصية أو محادثات خاصة أو صور أشخاص دون موافقة مناسبة.",
            "راقب التعليقات واستخدم أدوات الإشراف والحظر والإبلاغ عند الحاجة.",
            "تجنب الرسائل المزعجة والروابط المضللة وشراء المشاهدات أو التفاعل المصطنع."
        ),
        "ماذا يمكنك أن تشارك؟" to listOf(
            "قدّم شروحات وتجارب ومراجعات من إعدادك، أو محتوى تملك حق تصويره ونشره.",
            "استخدم صورك ومقاطعك وموسيقاك الأصلية، أو موادًا تملك ترخيصًا يسمح باستخدامها.",
            "احصل على إذن أصحاب الأعمال والمواد قبل عرضها.",
            "وضّح الإعلانات والرعاية عندما تتطلب قواعد المنصة أو القوانين ذلك."
        ),
        "محتوى يرفع مخاطر المخالفة" to listOf(
            "إعادة بث مباريات أو أفلام أو برامج أو موسيقى دون إذن أو ترخيص مناسب.",
            "التهديد أو التحريض أو المضايقة أو استهداف الأشخاص بمحتوى مخالف.",
            "انتحال الهوية والخداع والاحتيال والادعاءات المضللة.",
            "التحايل على قيود المنصة أو الالتفاف على عقوبة قائمة."
        ),
        "إذا تلقيت إنذارًا أو حظرًا" to listOf(
            "اقرأ إشعار المنصة وحدد المحتوى أو السلوك والقاعدة المذكورة.",
            "أوقف الجزء محل الاعتراض واحتفظ بنسخة من الإشعار وتاريخه.",
            "إذا كان القرار خاطئًا، استخدم مسار الاستئناف الرسمي وقدّم الأدلة ذات الصلة.",
            "لا تحاول تجاوز الحظر بحسابات أو مفاتيح أو وجهات بديلة."
        ),
        "إرشادات حسب وجهة البث" to listOf(
            "Telegram: تأكد من صلاحيتك لإدارة القناة أو المجموعة واحترم قواعد Telegram وحقوق المحتوى.",
            "TikTok: أهلية LIVE وميزات RTMP ومفاتيح البث تختلف حسب الحساب والمنطقة.",
            "RTMP مخصص: قبول الخادم للفيديو لا يعني أن المحتوى مسموح على المنصة النهائية."
        )
    )

    var expanded by remember { mutableStateOf(0) }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "دليل أمان البث وتجنب المخالفات",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                "إرشادات عملية قبل البث وأثناءه وبعد تلقي إنذار.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)
            )

            Text(
                "هذا الدليل للتوعية وتقليل المخاطر، ولا يضمن منع الإنذارات أو الحظر. المرجع النهائي هو أحدث قواعد المنصة وإشعاراتها الرسمية.",
                color = Warn,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            sections.forEachIndexed { index, section ->
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (expanded == index) -1 else index
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            section.first,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            if (expanded == index) "−" else "+",
                            color = Cyan,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (expanded == index) {
                        section.second.forEach { item ->
                            Text(
                                "•  $item",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    bottom = 9.dp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeveloperCard(onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF30215B), Color(0xFF161B31), Color(0xFF101522))))
        .border(1.dp, Color(0xFF9476F8).copy(.62f), RoundedCornerShape(24.dp)).padding(18.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(NeonBrush), contentAlignment = Alignment.Center) {
                    Text("AA", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("DEVELOPER", color = Color(0xFFC4B5FD), fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text("Aram Aoyal", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Tvshere · Product & Engineering", color = Color.LightGray, fontSize = 10.sp)
                }
            }
            Text("صناعة الأدوات التي تمنح الصورة طريقًا إلى العالم.", color = Color(0xFFE1D9FF), fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            NeonButton("التواصل مع المطوّر عبر Telegram", Modifier.fillMaxWidth().padding(top = 12.dp), Magenta, onOpen)
        }
    }
}

fun openPlatformApp(context: Context, platform: Platform): Boolean {
    val packages = when (platform) {
        Platform.TELEGRAM -> listOf("org.telegram.messenger")
        Platform.TIKTOK -> listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        Platform.CUSTOM -> emptyList()
    }
    for (packageName in packages) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: continue

        val launched = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)

        if (launched) return true
    }
    return false
}

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "welcome-glow")
    val glow by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.48f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "welcome-pulse"
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF09070B))) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFF304F).copy(alpha = glow), Color.Transparent)
                )
            )
        )
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until 34) {
                val x = ((i * 73) % 997) / 997f * size.width
                val y = ((i * 137) % 991) / 991f * size.height
                val radius = (1 + i % 3).toFloat() * 2.dp.toPx()
                drawCircle(
                    color = Color(0xFFFF8291).copy(alpha = 0.08f + glow * 0.12f),
                    radius = radius,
                    center = Offset(x, y)
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 25.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(900)) +
                    slideInVertically(animationSpec = tween(900)) { it / 12 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "TVSHERE",
                        color = Color(0xFFFF7185),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 5.sp
                    )
                    Spacer(Modifier.height(15.dp))
                    Text(
                        "حسوني Tv",
                        color = Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "حرية البث تبدأ من هنا",
                        color = Color(0xFFFF9AA8),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(
                        Modifier.padding(vertical = 22.dp)
                            .width(64.dp).height(2.dp)
                            .background(Color(0xFFFF526E))
                    )
                    Text(
                        "حسوني Tv تطبيق ذكي ومتطور، صُمّم لنقل وتوجيه البيانات بين المصادر الخارجية عبر بيئة الهاتف المستضيفة، ليمنحك القدرة على مشاركة البثوث الحية بصورة طبيعية عبر المنصات التي تختارها.",
                        color = Color(0xFFE5DDE0),
                        fontSize = 14.sp,
                        lineHeight = 25.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "من تطوير Aram Aoyal",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "نموذج أولي قابل للتحديث والتطوير المستمر.",
                        color = Color(0xFFB8AEB4),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    NeonButton(
                        "هيا نبدأ",
                        Modifier.fillMaxWidth().padding(top = 30.dp),
                        Color(0xFFFF526E),
                        onStart
                    )
                }
            }
        }
    }
}

@Composable
fun CapabilitiesSection() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "إمكانيات حسوني Tv",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "أنت تتحكم بالتجربة.",
            color = Color(0xFFFF7185),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "من إعداد البث إلى متابعة أداء الهاتف، صُمّم حسوني Tv ليمنحك تحكمًا مباشرًا في تجربة البث ومكوناتها.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 19.sp
        )
        CapabilityInfoCard(
            Icons.Default.Videocam,
            "وجهة البث",
            "اختر Telegram أو TikTok أو خادم RTMP مخصصًا، وأدخل بيانات جلسة البث التي توفرها المنصة.",
            Color(0xFFFF7185)
        )
        CapabilityInfoCard(
            Icons.Default.Tune,
            "إعداداتك، قرارك",
            "خصّص الدقة ومعدل الإطارات والبت والصوت والطبقات المتاحة، وفق احتياجات بثك.",
            Violet
        )
        CapabilityInfoCard(
            Icons.Default.Analytics,
            "إحصائيات البث",
            "تابع معدل الإرسال والإطارات المفقودة والبيانات المرسلة ومدة البث. عدد المشاهدين لا يظهر إلا إذا وفرته المنصة عبر واجهة مدعومة.",
            Cyan
        )
        CapabilityInfoCard(
            Icons.Default.Memory,
            "مراقبة موارد الهاتف",
            "يعرض التطبيق قياسات CPU وذاكرة عملية التطبيق وحرارة البطارية عندما يتيحها Android؛ وهذه ليست قراءة شاملة لحرارة جميع مكونات الهاتف.",
            Magenta
        )
        CapabilityInfoCard(
            Icons.Default.Sensors,
            "فحص USB ومصدر الفيديو",
            "يتحقق من اكتشاف USB وواجهة UVC وفتح كاميرا الالتقاط وعودة استدعاء بدء المعاينة دون استثناء. لا يثبت ذلك وصول إطارات فيديو فعلية أو سلامة الكابل أو وجود إشارة HDMI فعالة.",
            Warn
        )
    }
}

@Composable
private fun CapabilityInfoCard(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}



/** Crop, zoom and pan controls connected to the encoded GL pipeline. */
@Composable
fun SceneEditorSection(vm: MainViewModel) {
    val scene by vm.sceneS.collectAsState()

    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "محرر المشهد",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            "تُطبّق هذه التعديلات على الفيديو قبل الترميز، وتظهر في المعاينة والبث. تحفظ الإعدادات تلقائيًا.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )

        Text(
            "التكبير: %.2f×".format(scene.zoom),
            modifier = Modifier.padding(top = 10.dp)
        )

        Slider(
            value = scene.zoom,
            onValueChange = { vm.setScene(scene.copy(zoom = it)) },
            valueRange = 1f..3f
        )

        Text("التحريك الأفقي: %.0f%%".format(scene.panX * 100f))

        Slider(
            value = scene.panX,
            onValueChange = { vm.setScene(scene.copy(panX = it)) },
            valueRange = -1f..1f
        )

        Text("التحريك العمودي: %.0f%%".format(scene.panY * 100f))

        Slider(
            value = scene.panY,
            onValueChange = { vm.setScene(scene.copy(panY = it)) },
            valueRange = -1f..1f
        )

        OutlinedButton(
            onClick = { vm.resetScene() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("إعادة ضبط إطار المشهد")
        }

        Text(
            "المرحلة الحالية: إخراج أفقي وفق دقة الترميز المختارة. لا يغيّر هذا المحرر نسبة RTMP إلى 9:16 أو 1:1.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}
