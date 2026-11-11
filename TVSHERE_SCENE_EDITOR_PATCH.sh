#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

ROOT="$HOME/Tvshere"
cd "$ROOT"

MODELS="app/src/main/java/com/hassouni/tv/data/Models.kt"
CONTROLLER="app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt"
VIEWMODEL="app/src/main/java/com/hassouni/tv/ui/MainViewModel.kt"
SCREENS="app/src/main/java/com/hassouni/tv/ui/Screens.kt"
EXTRAS="app/src/main/java/com/hassouni/tv/ui/StudioExtras.kt"

FILES=("$MODELS" "$CONTROLLER" "$VIEWMODEL" "$SCREENS" "$EXTRAS")

for f in "${FILES[@]}"; do
    [[ -f "$f" ]] || { echo "ERROR: الملف غير موجود: $f"; exit 1; }
done

grep -q 'data class LiveStats(' "$MODELS" || {
    echo "ERROR: Models.kt لا يطابق البنية المتوقعة"; exit 1;
}
grep -q 'private var overlayRenderer: OverlayRenderer?' "$CONTROLLER" || {
    echo "ERROR: بنية BroadcastController غير متوقعة"; exit 1;
}
grep -q 'overlayRenderer = OverlayRenderer(chosenRes.w, chosenRes.h)' "$CONTROLLER" || {
    echo "ERROR: لم يُعثر على موضع إنشاء OverlayRenderer"; exit 1;
}
grep -q 'fun setOverlay(value: OverlayState)' "$VIEWMODEL" || {
    echo "ERROR: بنية MainViewModel غير متوقعة"; exit 1;
}
grep -q 'PreviewShapeSelector(previewShape)' "$SCREENS" || {
    echo "ERROR: لم يُعثر على موضع محرر الاستوديو"; exit 1;
}
grep -q '^package com.hassouni.tv.ui' "$EXTRAS" || {
    echo "ERROR: StudioExtras.kt غير متوقع"; exit 1;
}

if grep -q 'data class SceneSettings(' "$MODELS" ||
   grep -q 'TVSHERE_SCENE_EDITOR_V1' "$SCREENS"; then
    echo "ERROR: يبدو أن محرر المشاهد أُضيف سابقًا. لن أكرر التعديل."
    exit 1
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP="$HOME/Tvshere_backups/TVSHERE_PRE_SCENE_EDITOR_$STAMP"
mkdir -p "$BACKUP"

for f in "${FILES[@]}"; do
    cp -p "$f" "$BACKUP/"
done

rollback() {
    echo "ERROR: فشل التنفيذ. استعادة الملفات الأصلية..."
    for f in "${FILES[@]}"; do
        cp -p "$BACKUP/$(basename "$f")" "$f"
    done
    echo "تمت الاستعادة من: $BACKUP"
}
trap rollback ERR

python - <<'PY'
from pathlib import Path

root = Path.cwd()

def read(rel):
    return (root / rel).read_text(encoding="utf-8")

def write(rel, text):
    (root / rel).write_text(text, encoding="utf-8")

models = "app/src/main/java/com/hassouni/tv/data/Models.kt"
controller = "app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt"
viewmodel = "app/src/main/java/com/hassouni/tv/ui/MainViewModel.kt"
screens = "app/src/main/java/com/hassouni/tv/ui/Screens.kt"
extras = "app/src/main/java/com/hassouni/tv/ui/StudioExtras.kt"

# Models
s = read(models)
anchor = "data class LiveStats("
assert s.count(anchor) == 1
s += '''

/** Scene crop controls, independent from local preview framing. */
data class SceneSettings(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
)
'''
write(models, s)

# Broadcast controller
s = read(controller)

s = s.replace(
    "import com.hassouni.tv.data.QualitySettings",
    "import com.hassouni.tv.data.QualitySettings\\nimport com.hassouni.tv.data.SceneSettings",
    1
)

s = s.replace(
    "import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender",
    "import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender\\n"
    "import com.pedro.encoder.input.gl.render.filters.`object`.CropFilterRender",
    1
)

s = s.replace(
    "private var overlayRenderer: OverlayRenderer? = null",
    "private var overlayRenderer: OverlayRenderer? = null\\n"
    "    private var sceneFilter: CropFilterRender? = null\\n"
    "    private var selectedScene = SceneSettings()",
    1
)

anchor = '''    fun setAudio(settings: AudioSettings) {
        audioSource?.settings = settings
    }'''

replacement = '''    fun setAudio(settings: AudioSettings) {
        audioSource?.settings = settings
    }

    /**
     * Replace the crop filter through RootEncoder's GL action queue.
     * Never mutate a live CropFilterRender matrix directly from the UI thread.
     */
    fun setScene(settings: SceneSettings) {
        selectedScene = settings.copy(
            zoom = settings.zoom.coerceIn(1f, 3f),
            panX = settings.panX.coerceIn(-1f, 1f),
            panY = settings.panY.coerceIn(-1f, 1f)
        )

        val current = stream ?: return
        if (sceneFilter == null) return

        val next = createSceneFilter(selectedScene)

        runCatching {
            current.getGlInterface().setFilter(0, next)
            sceneFilter = next
        }.onFailure {
            reportFailure("تعذر تحديث قص المشهد: ${it.message ?: "خطأ رسومي"}")
        }
    }

    private fun createSceneFilter(settings: SceneSettings): CropFilterRender {
        val zoom = settings.zoom.coerceIn(1f, 3f)
        val cropWidth = 100f / zoom
        val cropHeight = 100f / zoom

        val travelX = 100f - cropWidth
        val travelY = 100f - cropHeight

        val offsetX =
            (travelX / 2f) * (1f + settings.panX.coerceIn(-1f, 1f))
        val offsetY =
            (travelY / 2f) * (1f + settings.panY.coerceIn(-1f, 1f))

        return CropFilterRender().apply {
            setCropArea(offsetX, offsetY, cropWidth, cropHeight)
        }
    }'''

assert s.count(anchor) == 1
s = s.replace(anchor, replacement, 1)

# Clear scene filter references whenever pipeline is released/recreated.
s = s.replace(
    "overlayFilter = null",
    "overlayFilter = null\\n        sceneFilter = null"
)

# Add crop filter before the existing overlay compositor.
anchor = "        overlayRenderer = OverlayRenderer(chosenRes.w, chosenRes.h)"
assert s.count(anchor) == 1

s = s.replace(
    anchor,
    "        sceneFilter = createSceneFilter(selectedScene)\\n"
    "        stream!!.getGlInterface().addFilter(0, sceneFilter!!)\\n"
    + anchor,
    1
)
write(controller, s)

# ViewModel
s = read(viewmodel)

s = s.replace(
    "import com.hassouni.tv.data.SignalState",
    "import com.hassouni.tv.data.SignalState\\n"
    "import com.hassouni.tv.data.SceneSettings",
    1
)

s = s.replace(
    "    private val monitor = SystemMonitor(app)",
    '''    private val monitor = SystemMonitor(app)

    private val scenePreferences = app.getSharedPreferences(
        "tvshere_preferences",
        android.content.Context.MODE_PRIVATE
    )

    private fun loadSceneSettings() = SceneSettings(
        zoom = scenePreferences.getFloat("scene_zoom", 1f).coerceIn(1f, 3f),
        panX = scenePreferences.getFloat("scene_pan_x", 0f).coerceIn(-1f, 1f),
        panY = scenePreferences.getFloat("scene_pan_y", 0f).coerceIn(-1f, 1f)
    )''',
    1
)

s = s.replace(
    "    val overlayS = MutableStateFlow(OverlayState())",
    "    val overlayS = MutableStateFlow(OverlayState())\\n"
    "    val sceneS = MutableStateFlow(loadSceneSettings())",
    1
)

s = s.replace(
    "    init {\\n        viewModelScope.launch {",
    "    init {\\n"
    "        broadcast.setScene(sceneS.value)\\n"
    "        viewModelScope.launch {",
    1
)

anchor = "    fun setOverlay(value: OverlayState) {"
assert s.count(anchor) == 1

replacement = '''    fun setScene(value: SceneSettings) {
        val safe = value.copy(
            zoom = value.zoom.coerceIn(1f, 3f),
            panX = value.panX.coerceIn(-1f, 1f),
            panY = value.panY.coerceIn(-1f, 1f)
        )

        sceneS.value = safe

        scenePreferences.edit()
            .putFloat("scene_zoom", safe.zoom)
            .putFloat("scene_pan_x", safe.panX)
            .putFloat("scene_pan_y", safe.panY)
            .apply()

        broadcast.setScene(safe)
    }

    fun resetScene() = setScene(SceneSettings())

    fun setOverlay(value: OverlayState) {'''

s = s.replace(anchor, replacement, 1)
write(viewmodel, s)

# Insert editor in StudioScreen.
s = read(screens)
anchor = "    PreviewShapeSelector(previewShape) { vm.setPreviewShape(it) }"
assert s.count(anchor) == 1

s = s.replace(
    anchor,
    anchor + "\\n"
    "    // TVSHERE_SCENE_EDITOR_V1\\n"
    "    SceneEditorSection(vm)",
    1
)
write(screens, s)

# Compose editor UI.
s = read(extras)

s = s.replace(
    "import androidx.compose.runtime.Composable",
    "import androidx.compose.runtime.Composable\\n"
    "import androidx.compose.runtime.collectAsState",
    1
)

s += '''

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
'''

write(extras, s)
PY

echo "========== POST PATCH VALIDATION =========="

grep -q 'data class SceneSettings(' "$MODELS"
grep -q 'fun setScene(settings: SceneSettings)' "$CONTROLLER"
grep -q 'current.getGlInterface().setFilter(0, next)' "$CONTROLLER"
grep -q 'addFilter(0, sceneFilter!!)' "$CONTROLLER"
grep -q 'fun resetScene()' "$VIEWMODEL"
grep -q 'SceneEditorSection(vm)' "$SCREENS"
grep -q 'fun SceneEditorSection(vm: MainViewModel)' "$EXTRAS"

git diff --check

trap - ERR

echo
echo "PASS: SceneSettings added."
echo "PASS: CropFilterRender inserted before overlay compositor."
echo "PASS: Live filter replacement routed through GL queue."
echo "PASS: Zoom/pan/reset controls added."
echo "PASS: Scene settings persisted."
echo "PASS: git diff --check"
echo
echo "BACKUP: $BACKUP"
echo "No build, APK install, commit, or push was performed."
echo "IMPORTANT: Structural checks only; build and real UVC/RTMP tests are still required."
