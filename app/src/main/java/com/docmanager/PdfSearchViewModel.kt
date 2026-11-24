package com.docmanager

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_HITS = 1500

data class PageHit(val pageId: Long, val page: Int, val snippet: String)

data class FileHit(
    val pdfId: Long, val name: String, val path: String, val uri: String,
    val folder: String, val pages: List<PageHit>
)

sealed class PdfDialog {
    data class Message(val title: String, val text: String, val details: String = "") : PdfDialog()
    class PageView(val file: FileHit, val page: Int, val text: String, val terms: List<String>) : PdfDialog()
    data class AskIndex(val folder: FolderRow) : PdfDialog()
    data class ConfirmDelete(val folder: FolderRow) : PdfDialog()
    data class ConfirmRebuild(val folder: FolderRow) : PdfDialog()
}

private class SearchOut(val files: List<FileHit>, val pages: Int, val truncated: Boolean)

class PdfSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val db = PdfDb(app)
    private val prefs = app.getSharedPreferences("pdf_search", Context.MODE_PRIVATE)

    var folders by mutableStateOf<List<FolderRow>>(emptyList()); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var query by mutableStateOf(prefs.getString("query", "") ?: "")
    var phrase by mutableStateOf(prefs.getBoolean("phrase", false))
    var folderSectionOpen by mutableStateOf(prefs.getBoolean("folders_open", true)); private set
    var results by mutableStateOf<List<FileHit>>(emptyList()); private set
    var resultInfo by mutableStateOf(""); private set
    var searching by mutableStateOf(false); private set
    var progress by mutableStateOf<IndexProgress?>(null); private set
    var lastTerms by mutableStateOf<List<String>>(emptyList()); private set
    var dialog by mutableStateOf<PdfDialog?>(null)

    private var autoSearch = true

    @Volatile private var cancelFlag = false
    private var indexJob: Job? = null

    init {
        selected = (prefs.getString("selected", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
        refreshFolders()
    }

    fun onQueryChange(s: String) {
        query = s
        prefs.edit().putString("query", s).apply()
    }

    fun onPhraseChange(b: Boolean) {
        phrase = b
        prefs.edit().putBoolean("phrase", b).apply()
    }

    fun toggleFolderSection() {
        folderSectionOpen = !folderSectionOpen
        prefs.edit().putBoolean("folders_open", folderSectionOpen).apply()
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    private fun saveSelected() = prefs.edit().putString("selected", selected.joinToString(",")).apply()

    fun refreshFolders() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { db.folders() }
            folders = list
            val ids = list.map { it.id }.toSet()
            if (!ids.containsAll(selected)) {
                selected = selected.intersect(ids)
                saveSelected()
            }
            // Mở lại màn hình: tự chạy lại lần tìm cuối cùng
            if (autoSearch) {
                autoSearch = false
                if (query.isNotBlank() && selected.isNotEmpty() && list.isNotEmpty()) search()
            }
        }
    }

    // ============================================================ thư mục
    private fun folderName(uri: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { uri.toString() }
        val n = id.substringAfterLast('/').substringAfter(':')
        return n.ifEmpty { "Bộ nhớ trong" }
    }

    fun onFolderPicked(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        viewModelScope.launch {
            val (id, isNew) = withContext(Dispatchers.IO) { db.addFolder(uri.toString(), folderName(uri)) }
            val list = withContext(Dispatchers.IO) { db.folders() }
            folders = list
            selected = selected + id
            saveSelected()
            val f = list.firstOrNull { it.id == id }
            if (f != null) {
                dialog = if (isNew) PdfDialog.AskIndex(f)
                else PdfDialog.Message("Thư mục đã có", "Thư mục này đã nằm trong danh sách. Bấm \"Cập nhật\" để làm mới chỉ mục.")
            }
        }
    }

    fun toggleFolder(id: Long) {
        selected = if (id in selected) selected - id else selected + id
        saveSelected()
    }

    fun selectAllFolders() { selected = folders.map { it.id }.toSet(); saveSelected() }

    fun deleteFolder(f: FolderRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteFolder(f.id) }
            try {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(f.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            selected = selected - f.id
            saveSelected()
            results = emptyList()
            resultInfo = ""
            refreshFolders()
        }
    }

    // ============================================================ lập chỉ mục
    fun startIndex(f: FolderRow, rebuild: Boolean) {
        if (progress != null) { toast("Đang lập chỉ mục, hãy chờ hoặc bấm Hủy."); return }
        cancelFlag = false
        progress = IndexProgress(f.name, 0, 0, "Đang chuẩn bị...")
        indexJob = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    PdfIndexer.run(ctx, db, f, rebuild, { cancelFlag }) { p -> progress = p }
                }
                progress = null
                refreshFolders()
                val text = buildString {
                    append(if (res.cancelled) "Đã hủy giữa chừng (phần đã xử lý vẫn được giữ, bấm Cập nhật để làm tiếp).\n" else "")
                    append("Đã lập chỉ mục: ${res.indexed} file (${res.pages} trang có chữ).\n")
                    append("Không đổi, bỏ qua: ${res.unchanged} file.\n")
                    if (res.removed > 0) append("Đã xóa khỏi chỉ mục (file không còn): ${res.removed}.\n")
                    if (res.noText > 0) append("PDF không có lớp chữ (bản scan, không tìm được): ${res.noText} file.\n")
                    if (res.errors.isNotEmpty()) append("Lỗi: ${res.errors.size} file.")
                }
                dialog = PdfDialog.Message(
                    if (res.cancelled) "Đã hủy" else "Hoàn tất lập chỉ mục", text.trim(),
                    res.errors.take(50).joinToString("\n")
                )
            } catch (e: Exception) {
                progress = null
                dialog = PdfDialog.Message("Lỗi", "Không lập được chỉ mục:\n${e.message}")
            }
        }
    }

    fun cancelIndex() { cancelFlag = true }

    // ============================================================ tìm kiếm
    fun search() {
        val q = query.trim()
        val expr = PdfText.matchExpr(q, phrase)
        if (expr == null) { results = emptyList(); resultInfo = "Hãy nhập từ khóa cần tìm."; return }
        if (folders.isEmpty()) { results = emptyList(); resultInfo = "Chưa có thư mục nào. Bấm ➕ Thêm thư mục để lập chỉ mục."; return }
        if (selected.isEmpty()) { results = emptyList(); resultInfo = "Hãy tích chọn ít nhất một thư mục để tìm."; return }
        val terms = PdfText.terms(q)
        val ids = selected
        searching = true
        viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.IO) { runSearch(expr, terms, ids) }
                lastTerms = terms
                results = out.files
                resultInfo = if (out.pages == 0) "Không tìm thấy \"$q\" trong các thư mục đã chọn."
                else "Tìm thấy ${out.pages} trang trong ${out.files.size} file" +
                    (if (out.truncated) " (chỉ hiện $MAX_HITS kết quả đầu, hãy gõ từ khóa cụ thể hơn)" else "")
            } catch (e: Exception) {
                results = emptyList()
                resultInfo = "Lỗi khi tìm: ${e.message}"
            } finally {
                searching = false
            }
        }
    }

    private fun runSearch(expr: String, terms: List<String>, ids: Set<Long>): SearchOut {
        val raw = db.search(expr, ids, MAX_HITS)
        val pagesByPdf = LinkedHashMap<Long, MutableList<PageHit>>()
        val meta = HashMap<Long, RawHit>()
        for (h in raw) {
            meta.putIfAbsent(h.pdfId, h)
            pagesByPdf.getOrPut(h.pdfId) { ArrayList() }.add(PageHit(h.pageId, h.page, PdfText.snippet(h.text, terms)))
        }
        val files = pagesByPdf.map { (id, pages) ->
            val m = meta[id]!!
            FileHit(id, m.name, m.path, m.uri, m.folder, pages)
        }
        return SearchOut(files, raw.size, raw.size >= MAX_HITS)
    }

    fun openPage(file: FileHit, hit: PageHit) {
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { db.pageText(hit.pageId) }
            dialog = PdfDialog.PageView(file, hit.page, t, lastTerms)
        }
    }

    // ============================================================ mở file
    fun openFile(uri: String, page: Int?) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            if (page != null) toast("Từ khóa nằm ở trang $page")
        } catch (_: ActivityNotFoundException) {
            toast("Không có ứng dụng nào mở được file PDF.")
        } catch (e: Exception) {
            toast("Không mở được file: ${e.message}")
        }
    }
}
