package com.docmanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Một ghi chú ảnh (Quick Note) - sẽ hiện như 1 dòng mới ở cuối bảng. */
data class QuickNote(
    val id: Long, val docNo: String, val date: String, val link: String,
    val title: String, val category: String, val type: String
)

/** Đọc danh sách giá trị TYPE từ file TYPE.config nằm trong thư mục config. */
object TypeConfig {
    class Result(val values: List<String>, val found: Boolean, val message: String)

    fun load(ctx: Context): Result {
        val prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("config", null)
            ?: return Result(emptyList(), false, "Chưa chọn thư mục config.")
        return try {
            val tree = Uri.parse(uriStr)
            val kids = Saf.children(ctx, tree, Saf.rootDocId(tree)).filter { !Saf.isDir(it.mime) }
            val f = kids.firstOrNull { it.name.lowercase().startsWith("type.config") }
                ?: return Result(emptyList(), false, "Không tìm thấy file TYPE.config trong thư mục config.")
            val values = parse(Saf.readText(ctx, Saf.docUri(tree, f.docId)))
            if (values.isEmpty()) Result(emptyList(), true, "File TYPE.config không có giá trị nào.")
            else Result(values, true, "")
        } catch (e: Exception) {
            Result(emptyList(), false, "Không đọc được thư mục config: ${e.message}")
        }
    }

    /** Chấp nhận JSON (mảng / đối tượng) hoặc văn bản thường, mỗi dòng 1 giá trị (dòng bắt đầu bằng # là chú thích). */
    fun parse(text: String): List<String> {
        val t = text.trim()
        if (t.startsWith("[") || t.startsWith("{")) {
            try {
                return uniqueClean(flattenValues(JSONTokener(t).nextValue()))
            } catch (_: Exception) {
            }
        }
        val items = ArrayList<String>()
        for (line in t.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            if (l.contains(';') || (l.contains(',') && !l.contains(' '))) items.addAll(l.split(';', ','))
            else items.add(l)
        }
        return uniqueClean(items)
    }
}

/** Mở PDF bằng trình xem trong app (có Quick Note). */
fun Context.openPdfInApp(uri: String, name: String = "", page: Int = 1) {
    startActivity(
        Intent(this, PdfViewerActivity::class.java)
            .putExtra("uri", uri).putExtra("name", name).putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Lưu ảnh PNG vào thư mục đã chọn và thêm 1 bản ghi Quick Note (Document No tự đặt, ngày MM/dd/yy, Category IMAGE NOTE). */
suspend fun saveQuickNoteImage(
    ctx: Context, db: Db, dirStr: String, bmp: Bitmap, title: String, type: String
): QuickNote = withContext(Dispatchers.IO) {
    val dir = Uri.parse(dirStr)
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
        0, docNo, SimpleDateFormat("MM/dd/yy", Locale.US).format(now), out.toString(), title, "IMAGE NOTE", type
    )
    db.insertQuickNote(note)
    note
}
