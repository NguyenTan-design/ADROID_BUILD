package com.docmanager

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.LruCache
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Một từ trong lớp chữ của PDF; tọa độ là tỉ lệ 0..1 so với kích thước trang (gốc ở góc trên trái). */
data class PWord(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

enum class ViewerMode { NONE, SELECT, OCR }
enum class ViewerPicker { IMAGE_DIR, CONFIG_DIR }

sealed class ViewerDialog {
    data class Message(val title: String, val text: String) : ViewerDialog()
    object QuickMenu : ViewerDialog()
}

private const val CROP_W = 2000
private const val MAX_RENDER_W = 2800

private class WordStripper(val pw: Float, val ph: Float) : PDFTextStripper() {
    val words = ArrayList<PWord>()

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (textPositions == null) return
        val sb = StringBuilder()
        var x0 = 0f
        var y0 = 0f
        var x1 = 0f
        var y1 = 0f
        fun flush() {
            if (sb.isNotEmpty()) {
                words.add(
                    PWord(
                        sb.toString(),
                        (x0 / pw).coerceIn(0f, 1f), (y0 / ph).coerceIn(0f, 1f),
                        (x1 / pw).coerceIn(0f, 1f), (y1 / ph).coerceIn(0f, 1f)
                    )
                )
                sb.setLength(0)
            }
        }
        for (tp in textPositions) {
            val u = tp.unicode ?: ""
            if (u.isBlank()) { flush(); continue }
            val h = if (tp.heightDir > 0f) tp.heightDir else tp.fontSizeInPt
            val tx0 = tp.xDirAdj
            val tx1 = tp.xDirAdj + tp.widthDirAdj
            val ty1 = tp.yDirAdj
            val ty0 = ty1 - h
            if (sb.isEmpty()) {
                x0 = tx0; x1 = tx1; y0 = ty0; y1 = ty1
            } else {
                x0 = minOf(x0, tx0); x1 = maxOf(x1, tx1); y0 = minOf(y0, ty0); y1 = maxOf(y1, ty1)
            }
            sb.append(u)
        }
        flush()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

class PdfViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val db = Db.get(app)
    private val prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val pdfDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // ---- tài liệu
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    var aspects by mutableStateOf<List<Float>>(emptyList()); private set      // cao / rộng của từng trang
    var startPage by mutableStateOf(0); private set
    var zoom by mutableFloatStateOf(1f)
    var renderZoom by mutableFloatStateOf(1f)

    // ---- chọn chữ / OCR
    var mode by mutableStateOf(ViewerMode.NONE)
    var selPage by mutableStateOf(-1); private set
    var selWords by mutableStateOf<List<PWord>>(emptyList()); private set
    var ocrPage by mutableStateOf(-1); private set
    var ocrRect by mutableStateOf<FloatArray?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var dialog by mutableStateOf<ViewerDialog?>(null)

    // ---- bản nháp Quick Note
    var showSave by mutableStateOf(false); private set
    var draftBitmap by mutableStateOf<Bitmap?>(null); private set
    var draftText by mutableStateOf("")
    var draftType by mutableStateOf<String?>(null)
    var types by mutableStateOf<List<String>>(emptyList()); private set
    var typesMsg by mutableStateOf(""); private set
    var imageDirName by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set

    var pickerRequest by mutableStateOf<ViewerPicker?>(null)
    var currentPicker: ViewerPicker? = null
    private var pendingSave = false

    private var opened: String? = null
    private var renderer: PdfRenderer? = null
    private var cacheFile: File? = null
    private var pdDoc: PDDocument? = null
    private val wordCache = HashMap<Int, List<PWord>>()
    private val cache = object : LruCache<String, Bitmap>(80 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    // =============================================================== mở file
    fun open(uriStr: String, name: String, page: Int) {
        if (opened == uriStr) return
        opened = uriStr
        title = name.ifBlank { Saf.info(ctx, Uri.parse(uriStr))?.name ?: "PDF" }
        startPage = (page - 1).coerceAtLeast(0)
        imageDirName = dirName(prefs.getString("qn_dir", null))
        viewModelScope.launch {
            loading = true
            error = null
            try {
                withContext(pdfDispatcher) {
                    val dir = File(ctx.cacheDir, "viewer").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, "v_${uriStr.hashCode()}.pdf")
                    val input = ctx.contentResolver.openInputStream(Uri.parse(uriStr))
                        ?: throw IllegalStateException("không mở được file")
                    input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    cacheFile = f
                    val r = PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
                    renderer = r
                    val list = ArrayList<Float>(r.pageCount)
                    var first = 1.4f
                    for (i in 0 until r.pageCount) {
                        if (i == 0 || r.pageCount <= 300) {
                            r.openPage(i).use { p -> first = p.height.toFloat() / p.width.toFloat() }
                            list.add(first)
                        } else {
                            list.add(first)          // tài liệu dài: tạm dùng tỉ lệ trang đầu, chỉnh lại khi trang được vẽ
                        }
                    }
                    aspects = list
                }
            } catch (e: Throwable) {
                error = "Không mở được PDF: ${e.message ?: e.javaClass.simpleName}"
            }
            loading = false
        }
    }

    // ============================================================ kết xuất trang
    fun anyCached(index: Int): Bitmap? =
        cache.snapshot().entries.firstOrNull { it.key.startsWith("$index@") }?.value

    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(pdfDispatcher) {
        val w = widthPx.coerceIn(200, MAX_RENDER_W)
        val key = "$index@$w"
        cache.get(key)?.let { return@withContext it }
        val r = renderer ?: return@withContext null
        try {
            r.openPage(index).use { p ->
                val asp = p.height.toFloat() / p.width.toFloat()
                val h = (w * asp).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                val cur = aspects
                if (index < cur.size && kotlin.math.abs(cur[index] - asp) > 0.01f) {
                    aspects = cur.toMutableList().also { it[index] = asp }
                }
                bmp
            }
        } catch (e: Throwable) {
            cache.evictAll()
            null
        }
    }

    // ============================================================ lớp chữ
    suspend fun wordsFor(index: Int): List<PWord> = withContext(pdfDispatcher) {
        wordCache[index]?.let { return@withContext it }
        val res = try { extractWords(index) } catch (e: Throwable) { emptyList() }
        wordCache[index] = res
        res
    }

    private fun extractWords(index: Int): List<PWord> {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        System.setProperty("java.io.tmpdir", ctx.cacheDir.absolutePath)
        val f = cacheFile ?: return emptyList()
        val doc = pdDoc ?: PDDocument.load(f, MemoryUsageSetting.setupMixed(24L * 1024 * 1024)).also { pdDoc = it }
        val page = doc.getPage(index)
        val box = page.cropBox
        var pw = box.width
        var ph = box.height
        val rot = page.rotation
        if (rot == 90 || rot == 270) { val t = pw; pw = ph; ph = t }
        val st = WordStripper(pw, ph)
        st.setSortByPosition(true)
        st.setStartPage(index + 1)
        st.setEndPage(index + 1)
        st.getText(doc)
        return st.words
    }

    // ============================================================ chọn chữ
    fun setSelection(page: Int, words: List<PWord>) { selPage = page; selWords = words }

    fun clearSelection() { selPage = -1; selWords = emptyList() }

    fun noTextHint() = toast("Trang này không có lớp chữ (có thể là bản scan). Hãy dùng Quick Note > Quét OCR.")

    fun toggleSelectMode() {
        mode = if (mode == ViewerMode.SELECT) ViewerMode.NONE else ViewerMode.SELECT
        ocrRect = null
    }

    // ============================================================ OCR
    fun startOcrMode() {
        dialog = null
        clearSelection()
        ocrRect = null
        mode = ViewerMode.OCR
    }

    fun cancelOcrMode() { mode = ViewerMode.NONE; ocrRect = null }

    fun setOcrRect(page: Int, r: FloatArray) { ocrPage = page; ocrRect = r }

    fun finishOcr(page: Int) {
        val r = ocrRect ?: return
        if (r[2] - r[0] < 0.01f || r[3] - r[1] < 0.004f) {
            ocrRect = null
            toast("Vùng khoanh quá nhỏ, hãy kéo lại.")
            return
        }
        busy = "Đang nhận diện chữ..."
        viewModelScope.launch {
            try {
                val bmp = cropPage(page, r) ?: throw IllegalStateException("không cắt được vùng ảnh")
                val text = recognize(bmp)
                busy = null
                mode = ViewerMode.NONE
                ocrRect = null
                if (text.isBlank()) toast("Không nhận diện được chữ, bạn có thể tự gõ nội dung.")
                openDraft(text, bmp)
            } catch (e: Throwable) {
                busy = null
                ocrRect = null
                dialog = ViewerDialog.Message("Lỗi nhận diện chữ", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun recognize(bmp: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val res = rec.process(InputImage.fromBitmap(bmp, 0)).awaitResult()
            return collapse(res.text.replace('\n', ' '))
        } finally {
            rec.close()
        }
    }

    // ============================================================ ảnh cắt từ trang
    private suspend fun cropPage(page: Int, r: FloatArray): Bitmap? {
        val full = renderPage(page, CROP_W) ?: return null
        val x0 = (r[0] * full.width).toInt().coerceIn(0, full.width - 1)
        val y0 = (r[1] * full.height).toInt().coerceIn(0, full.height - 1)
        val x1 = (r[2] * full.width).toInt().coerceIn(x0 + 1, full.width)
        val y1 = (r[3] * full.height).toInt().coerceIn(y0 + 1, full.height)
        return withContext(Dispatchers.Default) { Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0) }
    }

    // ============================================================ Quick Note
    fun onQuickNote() {
        if (busy != null || showSave) return
        val words = selWords
        if (words.isEmpty()) { dialog = ViewerDialog.QuickMenu; return }
        val page = selPage
        busy = "Đang chuẩn bị ảnh..."
        viewModelScope.launch {
            val pad = 0.008f
            val rect = floatArrayOf(
                (words.minOf { it.x0 } - pad).coerceAtLeast(0f), (words.minOf { it.y0 } - pad).coerceAtLeast(0f),
                (words.maxOf { it.x1 } + pad).coerceAtMost(1f), (words.maxOf { it.y1 } + pad).coerceAtMost(1f)
            )
            val bmp = cropPage(page, rect)
            busy = null
            if (bmp == null) { toast("Không tạo được ảnh từ trang này."); return@launch }
            openDraft(words.joinToString(" ") { it.text }, bmp)
        }
    }

    private fun openDraft(text: String, bmp: Bitmap) {
        draftBitmap = bmp
        draftText = collapse(text)
        draftType = null
        imageDirName = dirName(prefs.getString("qn_dir", null))
        loadTypes()
        showSave = true
    }

    fun cancelDraft() {
        showSave = false
        draftBitmap = null
        pendingSave = false
    }

    private fun loadTypes() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { TypeConfig.load(ctx) }
            types = r.values
            typesMsg = if (r.values.isEmpty()) r.message else ""
            if (draftType != null && draftType !in r.values) draftType = null
        }
    }

    /** Nút Reload Config trong hộp thoại lưu: đọc lại TYPE.config. */
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
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                prefs.edit().putString("qn_dir", uri.toString()).apply()
                imageDirName = dirName(uri.toString())
                if (pendingSave) { pendingSave = false; save() }
            }
            ViewerPicker.CONFIG_DIR -> {
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(s))
            id.substringAfterLast('/').substringAfter(':').ifEmpty { "Bộ nhớ trong" }
        } catch (_: Exception) { s }
    }

    fun save() {
        val type = draftType
        val bmp = draftBitmap
        if (type == null || bmp == null || saving) return
        val dirStr = prefs.getString("qn_dir", null)
        if (dirStr == null) {                       // lần đầu: hỏi nơi lưu ảnh, các lần sau tự nhớ
            pendingSave = true
            pickerRequest = ViewerPicker.IMAGE_DIR
            return
        }
        val dir = Uri.parse(dirStr)
        val title = collapse(draftText).ifEmpty { "(ảnh)" }
        saving = true
        viewModelScope.launch {
            try {
                val q = withContext(Dispatchers.IO) {
                    val now = Date()
                    val base = "QN-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
                    var docNo = base
                    var n = 2
                    while (db.quickNoteExists(docNo)) { docNo = "$base-$n"; n++ }
                    val out = DocumentsContract.createDocument(
                        ctx.contentResolver, Saf.docUri(dir, Saf.rootDocId(dir)), "image/png", "$docNo.png"
                    ) ?: throw IllegalStateException("không tạo được file ảnh trong thư mục đã chọn")
                    ctx.contentResolver.openOutputStream(out)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val note = QuickNote(
                        0, docNo, SimpleDateFormat("MM/dd/yyyy", Locale.US).format(now),
                        out.toString(), title, "IMAGE NOTE", type
                    )
                    db.insertQuickNote(note)
                    note
                }
                showSave = false
                draftBitmap = null
                clearSelection()
                toast("Đã lưu Quick Note ${q.docNo}")
            } catch (e: Throwable) {
                dialog = ViewerDialog.Message(
                    "Không lưu được",
                    "${e.message ?: e.javaClass.simpleName}\n\nHãy bấm \"Đổi\" để chọn lại thư mục lưu ảnh."
                )
            } finally {
                saving = false
            }
        }
    }

    override fun onCleared() {
        try { renderer?.close() } catch (_: Throwable) {}
        try { pdDoc?.close() } catch (_: Throwable) {}
        try { cacheFile?.delete() } catch (_: Throwable) {}
        pdfDispatcher.close()
        super.onCleared()
    }
}
