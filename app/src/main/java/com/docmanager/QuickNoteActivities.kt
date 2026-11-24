@file:OptIn(ExperimentalLayoutApi::class)

package com.docmanager

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// =========================================================== xin quyền chụp màn hình
/** Màn hình trong suốt: xin quyền chụp màn hình, bật dịch vụ nút nổi rồi mở PDF bằng Acrobat. */
class QuickNoteConsentActivity : ComponentActivity() {
    private var uri: String? = null

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, QuickNoteOverlayService::class.java)
                    .setAction(QuickNoteOverlayService.ACTION_START)
                    .putExtra("code", res.resultCode).putExtra("data", data)
            )
        } else {
            Toast.makeText(
                this, "Bạn đã từ chối chụp màn hình nên nút Quick Note nổi sẽ không hoạt động. PDF vẫn được mở.",
                Toast.LENGTH_LONG
            ).show()
        }
        uri?.let { openPdfExternal(this, it) }
        finish()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.getStringExtra("uri")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askCapture()
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mpm.createScreenCaptureIntent())
    }
}

// =========================================================== hộp thoại lưu (hiện trên Acrobat)
private suspend fun <T> Task<T>.awaitOcr(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

class QuickNoteSaveViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val db = Db.get(app)
    private val prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE)

    var bitmap by mutableStateOf<Bitmap?>(null); private set
    var title by mutableStateOf("")
    var ocrText by mutableStateOf(""); private set
    var ocrRunning by mutableStateOf(false); private set
    var clipText by mutableStateOf(""); private set
    var type by mutableStateOf<String?>(null)
    var types by mutableStateOf<List<String>>(emptyList()); private set
    var typesMsg by mutableStateOf(""); private set
    var imageDirName by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set
    var done by mutableStateOf(false); private set
    var errorMsg by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var mode by mutableStateOf("ocr"); private set                 // "copy" hoặc "ocr"

    var pickerRequest by mutableStateOf<ViewerPicker?>(null)
    var currentPicker: ViewerPicker? = null
    private var pendingSave = false
    private var loadedPath: String? = null
    private var draftFile: File? = null
    private var clipChecked = false
    private var titleTouched = false

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun load(path: String, mode: String) {
        if (loadedPath == path) return
        loadedPath = path
        this.mode = mode
        draftFile = File(path)
        imageDirName = dirName(prefs.getString("qn_dir", null))
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (bmp == null) { toast("Không đọc được ảnh vừa chụp."); done = true; return@launch }
            bitmap = bmp
            loadTypes()
            if (this@QuickNoteSaveViewModel.mode == "ocr") runOcr(bmp, false)     // chế độ Copy: chỉ OCR khi bấm nút Từ OCR
        }
    }

    fun onTitleEdited(s: String) { titleTouched = true; title = s }

    /** Đọc clipboard khi cửa sổ đã có tiêu điểm (Android 10+ chỉ cho đọc lúc đó). */
    fun readClipboard() {
        if (clipChecked) return
        clipChecked = true
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val txt = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx)?.toString()?.trim().orEmpty() else ""
            if (txt.isNotEmpty()) {
                clipText = collapse(txt)
                if (mode == "copy" && !titleTouched && title.isEmpty()) title = clipText
            } else if (mode == "copy") {
                notice = "Chưa có chữ nào được Copy. Hãy bôi chọn chữ trong Acrobat, bấm Copy rồi bấm lại nút Quick Note - hoặc bấm \"Từ OCR\" bên dưới."
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun runOcr(bmp: Bitmap, force: Boolean) {
        ocrRunning = true
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val res = rec.process(InputImage.fromBitmap(bmp, 0)).awaitOcr()
            ocrText = collapse(res.text.replace('\n', ' '))
            if (ocrText.isNotEmpty() && (force || (!titleTouched && title.isEmpty()))) {
                title = ocrText
                if (force) titleTouched = true
            }
        } catch (e: Throwable) {
            ocrText = ""
        } finally {
            rec.close()
            ocrRunning = false
        }
    }

    fun useClip() { if (clipText.isNotEmpty()) { title = clipText; titleTouched = true } }
    fun useOcr() {
        if (ocrText.isNotEmpty()) { title = ocrText; titleTouched = true; return }
        val bmp = bitmap ?: return
        if (ocrRunning) return
        viewModelScope.launch { runOcr(bmp, true) }
    }

    private fun loadTypes() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { TypeConfig.load(ctx) }
            types = r.values
            typesMsg = if (r.values.isEmpty()) r.message else ""
            if (type != null && type !in r.values) type = null
        }
    }

    fun reloadTypes() {
        if (prefs.getString("config", null) == null) {
            toast("Chọn thư mục chứa config.json và TYPE.config")
            pickerRequest = ViewerPicker.CONFIG_DIR
        } else {
            loadTypes()
        }
    }

    fun changeImageDir() { pendingSave = false; pickerRequest = ViewerPicker.IMAGE_DIR }

    fun onTreePicked(uri: Uri?) {
        val p = currentPicker
        currentPicker = null
        if (uri == null) { pendingSave = false; return }
        when (p) {
            ViewerPicker.IMAGE_DIR -> {
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                prefs.edit().putString("qn_dir", uri.toString()).apply()
                imageDirName = dirName(uri.toString())
                if (pendingSave) { pendingSave = false; save() }
            }
            ViewerPicker.CONFIG_DIR -> {
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                prefs.edit().putString("config", uri.toString()).apply()
                loadTypes()
            }
            else -> {}
        }
    }

    private fun dirName(s: String?): String? {
        if (s == null) return null
        return try {
            DocumentsContract.getTreeDocumentId(Uri.parse(s)).substringAfterLast('/').substringAfter(':')
                .ifEmpty { "Bộ nhớ trong" }
        } catch (_: Exception) { s }
    }

    fun cancel() {
        try { draftFile?.delete() } catch (_: Exception) {}
        done = true
    }

    fun save() {
        val t = type
        val bmp = bitmap
        if (t == null || bmp == null || saving) return
        val dirStr = prefs.getString("qn_dir", null)
        if (dirStr == null) {                          // lần đầu: hỏi nơi lưu ảnh, các lần sau tự nhớ
            pendingSave = true
            pickerRequest = ViewerPicker.IMAGE_DIR
            return
        }
        saving = true
        viewModelScope.launch {
            try {
                val q = saveQuickNoteImage(ctx, db, dirStr, bmp, collapse(title).ifEmpty { "(ảnh)" }, t)
                toast("Đã lưu Quick Note ${q.docNo}")
                try { draftFile?.delete() } catch (_: Exception) {}
                done = true
            } catch (e: Throwable) {
                errorMsg = "${e.message ?: e.javaClass.simpleName}\nHãy bấm \"Đổi\" để chọn lại thư mục lưu ảnh."
            } finally {
                saving = false
            }
        }
    }
}

class QuickNoteSaveActivity : ComponentActivity() {
    private val vm: QuickNoteSaveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        vm.load(path, intent.getStringExtra("mode") ?: "ocr")
        setContent { QuickNoteSaveScreen(vm) { finish() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) vm.readClipboard()
    }
}

@Composable
fun QuickNoteSaveScreen(vm: QuickNoteSaveViewModel, onFinish: () -> Unit) {
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    LaunchedEffect(vm.pickerRequest) {
        val p = vm.pickerRequest ?: return@LaunchedEffect
        vm.currentPicker = p
        vm.pickerRequest = null
        treePicker.launch(null)
    }
    LaunchedEffect(vm.done) { if (vm.done) onFinish() }

    MaterialTheme(colorScheme = lightColorScheme()) {
        if (vm.bitmap != null) {
            AlertDialog(
                onDismissRequest = { if (!vm.saving) vm.cancel() },
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text("Lưu Quick Note") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        vm.bitmap?.let { b ->
                            val img = remember(b) { b.asImageBitmap() }
                            Image(
                                img, contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).border(1.dp, Color(0xFFBDBDBD)),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (vm.mode == "copy") "Chế độ: 📋 Copy chữ" else "Chế độ: 🔍 Quét OCR",
                            fontSize = 11.sp, color = Color(0xFF3F51B5), fontWeight = FontWeight.Bold
                        )
                        Text("Nội dung ghi chú (cột Title):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(vm.title, { vm.onTitleEdited(it) }, Modifier.fillMaxWidth(), maxLines = 5)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ vm.useClip() }, enabled = vm.clipText.isNotEmpty()) { Text("📋 Từ Copy", fontSize = 12.sp) }
                            TextButton({ vm.useOcr() }, enabled = !vm.ocrRunning) {
                                Text(if (vm.ocrRunning) "🔍 OCR (đang đọc...)" else "🔍 Từ OCR", fontSize = 12.sp)
                            }
                        }
                        vm.notice?.let { Text(it, fontSize = 11.sp, color = Color(0xFFE65100)) }
                        Spacer(Modifier.height(8.dp))
                        Text("Chọn giá trị cột TYPE:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        if (vm.types.isEmpty()) {
                            Text(
                                (if (vm.typesMsg.isEmpty()) "Chưa có danh sách TYPE." else vm.typesMsg) +
                                    "\nHãy đặt file TYPE.config vào thư mục config rồi bấm Reload Config.",
                                color = Color(0xFFC62828), fontSize = 12.sp
                            )
                            TextButton({ vm.reloadTypes() }) { Text("⚙ Reload Config") }
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                vm.types.forEach { t -> FilterBtn(t, TYPE_STYLE, vm.type == t) { vm.type = t } }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Ảnh lưu vào: " + (vm.imageDirName ?: "(chưa chọn - sẽ hỏi khi bấm Save)"),
                                fontSize = 12.sp, color = Color(0xFF555555), modifier = Modifier.weight(1f)
                            )
                            TextButton({ vm.changeImageDir() }) { Text("Đổi") }
                        }
                        vm.errorMsg?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
                    }
                },
                confirmButton = {
                    TextButton({ vm.errorMsg = null; vm.save() }, enabled = vm.type != null && !vm.saving) {
                        Text(if (vm.saving) "Đang lưu..." else "Save")
                    }
                },
                dismissButton = { TextButton({ vm.cancel() }, enabled = !vm.saving) { Text("Cancel") } }
            )
        }
    }
}
