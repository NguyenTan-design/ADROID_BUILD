package com.docmanager

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class Picker { EXCEL, SCAN, CONFIG, EXPORT }

private class PendingExport(val files: List<String>, val problems: List<String>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val db = Db(app)
    private val prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE)

    // ---- cấu hình & nút lọc
    var config by mutableStateOf(AppConfig.default()); private set
    var filterButtons by mutableStateOf<List<FilterButton>>(emptyList()); private set
    var activeFilters by mutableStateOf<Map<String, FilterButton>>(emptyMap()); private set

    // ---- dữ liệu
    var table by mutableStateOf<TableData?>(null); private set
    var order by mutableStateOf<List<Int>>(emptyList()); private set
    var colWidths by mutableStateOf<List<Int>>(emptyList()); private set
    val notes = mutableStateMapOf<String, Note>()
    var fileIndex by mutableStateOf(FileIndex(emptyList())); private set

    // ---- ô tìm kiếm / trạng thái giao diện
    var txtDoc by mutableStateOf(""); private set
    var txtTitle by mutableStateOf(""); private set
    var txtAll by mutableStateOf(""); private set
    var hiddenCols by mutableStateOf<Set<String>>(emptySet()); private set
    var sortCol by mutableStateOf<Int?>(null); private set
    var sortAsc by mutableStateOf(false); private set
    var selected by mutableStateOf<Set<Int>>(emptySet()); private set
    var status by mutableStateOf("Sẵn sàng."); private set
    var dialog by mutableStateOf<UiDialog?>(null)
    var pickerRequest by mutableStateOf<Picker?>(null)
    var currentPicker: Picker? = null

    // ---- đường dẫn đã lưu
    private var excelUri: Uri? = pref("excel")
    private var folderUri: Uri? = pref("folder")
    private var configUri: Uri? = pref("config")
    private var pendingExport: PendingExport? = null
    private var excelName = ""

    private var debounceJob: Job? = null
    private var filterJob: Job? = null
    private var workJob: Job? = null
    @Volatile private var cancelFlag = false
    private val maskCache = ConcurrentHashMap<Pair<Int, List<String>>, BooleanArray>()

    val releaseIdx: Int?
        get() = table?.columns?.indexOfFirst { norm(it) == norm(config.settings.releaseDateColumn) }
            ?.takeIf { it >= 0 }

    private fun pref(key: String): Uri? = prefs.getString(key, null)?.let { Uri.parse(it) }
    private fun savePref(key: String, uri: Uri?) = prefs.edit().putString(key, uri?.toString()).apply()
    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    private fun persist(uri: Uri, write: Boolean = false) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
        }
    }

    init {
        hiddenCols = prefs.getStringSet("hidden_cols", emptySet())?.toSet() ?: emptySet()
        viewModelScope.launch {
            fileIndex = FileIndex(withContext(Dispatchers.IO) { db.allFiles() })
            loadConfigNow()
            if (excelUri != null) loadData(newFile = true)
        }
    }

    val canRefresh: Boolean get() = excelUri != null

    // =================================================================== config
    fun onReloadConfigClicked() {
        if (configUri == null) pickConfigFolder() else reloadConfig()
    }

    fun pickConfigFolder() {
        toast("Chọn thư mục chứa config.json và các file lọc (*.json)")
        pickerRequest = Picker.CONFIG
    }

    fun reloadConfig() {
        viewModelScope.launch {
            loadConfigNow()
            if (excelUri != null) loadData()
        }
    }

    private class ConfigFolder(val ctx: Context, val tree: Uri, val filterDir: String) {
        private val files = HashMap<String, String>()          // tên thường -> docId
        init {
            val root = Saf.rootDocId(tree)
            val rootKids = Saf.children(ctx, tree, root)
            if (filterDir.isNotBlank()) {
                rootKids.firstOrNull { Saf.isDir(it.mime) && it.name.equals(filterDir, true) }?.let { d ->
                    Saf.children(ctx, tree, d.docId).filter { !Saf.isDir(it.mime) }
                        .forEach { files[it.name.lowercase()] = it.docId }
                }
            }
            rootKids.filter { !Saf.isDir(it.mime) }.forEach { files.putIfAbsent(it.name.lowercase(), it.docId) }
        }
        fun read(name: String): String? =
            files[name.lowercase()]?.let { Saf.readText(ctx, Saf.docUri(tree, it)) }
    }

    private suspend fun loadConfigNow() {
        var cfg = AppConfig.default()
        var folder: ConfigFolder? = null
        val tree = configUri
        if (tree != null) {
            try {
                withContext(Dispatchers.IO) {
                    val first = ConfigFolder(ctx, tree, "")
                    val txt = first.read("config.json")
                    if (txt != null) cfg = AppConfig.parse(JSONObject(txt))
                    folder = ConfigFolder(ctx, tree, cfg.settings.filterDir)
                }
            } catch (e: Exception) {
                dialog = UiDialog.Message("Lỗi config.json", "Không đọc được config:\n${e.message}")
            }
        }
        config = cfg
        buildButtons(cfg, folder)
    }

    private suspend fun buildButtons(cfg: AppConfig, folder: ConfigFolder?) {
        val typeCol = cfg.settings.typeColumn
        val catCol = cfg.settings.categoryColumn
        val list = withContext(Dispatchers.IO) {
            val out = ArrayList<FilterButton>()
            fun readJson(name: String): Any? {
                val txt = folder?.read(name) ?: throw IllegalStateException("Không tìm thấy $name")
                return JSONTokener(txt).nextValue()
            }
            cfg.typeButtons.forEachIndexed { i, (label, file) ->
                val values = try {
                    uniqueClean(flattenValues(readJson(file))).ifEmpty { throw IllegalStateException("rỗng") }
                } catch (_: Exception) {
                    listOf("*$label*")                      // dự phòng: lọc cột TYPE chứa tên nút
                }
                out.add(FilterButton("type$i", "type", label, TYPE_STYLE, mapOf(typeCol to values)))
            }
            cfg.categoryFile?.let { cf ->
                try {
                    categoryItems(readJson(cf)).forEachIndexed { i, (label, values) ->
                        out.add(
                            if (label.uppercase() == "NOTE")
                                FilterButton("cat$i", "category", label, cfg.categoryStyle, special = "note_flag")
                            else
                                FilterButton("cat$i", "category", label, cfg.categoryStyle, mapOf(catCol to values))
                        )
                    }
                } catch (e: Exception) {
                    status = "Lỗi tải category: ${e.message}"
                }
            }
            out
        }
        filterButtons = list
        activeFilters = emptyMap()
        if (table != null) applyFilters()
    }

    private fun categoryItems(dataIn: Any?): List<Pair<String, List<String>>> {
        var data = dataIn
        if (data is JSONObject && data.length() == 1) {
            val key = data.keys().next()
            val v = data.get(key)
            if (norm(key) in setOf("category", "categories", "values") && v is org.json.JSONArray) data = v
        }
        val d = data
        val pairs: List<Pair<String, List<String>>> = if (d is JSONObject) {
            d.keys().asSequence().map { k -> k to uniqueClean(flattenValues(d.get(k))) }.toList()
        } else {
            uniqueClean(flattenValues(d)).map { it to listOf(it) }
        }
        return pairs.filter { it.second.isNotEmpty() }
    }

    // =============================================================== nút lọc
    fun onFilterClicked(b: FilterButton) {
        val m = activeFilters.toMutableMap()
        if (m[b.group]?.id == b.id) m.remove(b.group) else m[b.group] = b
        activeFilters = m
        applyFilters()
    }

    fun resetAll() {
        activeFilters = emptyMap()
        txtDoc = ""; txtTitle = ""; txtAll = ""
        applyFilters()
    }

    fun setTxtDoc(s: String) { txtDoc = s; scheduleFilter() }
    fun setTxtTitle(s: String) { txtTitle = s; scheduleFilter() }
    fun setTxtAll(s: String) { txtAll = s; scheduleFilter() }

    private fun scheduleFilter() {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch { delay(220); applyFilters() }
    }

    // ============================================================= tải Excel
    fun onExcelPicked(uri: Uri) {
        persist(uri)
        excelUri = uri
        savePref("excel", uri)
        hiddenCols = emptySet()
        saveHidden()
        sortCol = null
        loadData(newFile = true)
    }

    fun loadData(newFile: Boolean = false) {
        val uri = excelUri ?: return
        viewModelScope.launch {
            status = "Đang tải dữ liệu..."
            try {
                val s = config.settings
                val data = withContext(Dispatchers.IO) {
                    val raw = XlsxReader.read(ctx, uri, s.sheetName, s.headerRow, s.dateFormat)
                    TableBuilder.build(raw, s).withLinks(fileIndex)
                }
                val widths = withContext(Dispatchers.Default) { computeWidths(data, s) }
                val loaded = withContext(Dispatchers.IO) { db.allNotes() }
                notes.clear(); notes.putAll(loaded)
                maskCache.clear()
                excelName = Saf.info(ctx, uri)?.name ?: "Excel"
                hiddenCols = hiddenCols.intersect(data.columns.toSet())
                saveHidden()
                if (sortCol != null && sortCol!! >= data.columns.size) sortCol = null
                colWidths = widths
                table = data
                applyFilters()
            } catch (e: java.io.FileNotFoundException) {
                dialog = UiDialog.Message("Lỗi", "Không tìm thấy file Excel. Hãy bấm 📂 Load File để chọn lại.")
            } catch (e: Exception) {
                dialog = UiDialog.Message("Lỗi", "Có lỗi xảy ra: ${e.message}")
            }
        }
    }

    private fun computeWidths(t: TableData, s: Settings): List<Int> {
        val maxW = minOf(s.maxColumnWidth, 320)
        return t.columns.indices.map { c ->
            var w = t.columns[c].length * 8 + 44
            var a = 0; var b = 0; var d = 0
            for (r in t.rows) {
                val l = r[c].length
                if (l > a) { d = b; b = a; a = l } else if (l > b) { d = b; b = l } else if (l > d) d = l
            }
            for (l in listOf(a, b, d)) w = maxOf(w, minOf(l, 120) * 7 + 20)
            if (c in t.commentByCol) w = maxOf(w, 150)
            if (c == t.noteIdx) w = maxOf(w, 90)
            w.coerceIn(60, maxOf(60, maxW))
        }
    }

    // ============================================================ quét thư mục
    fun onScanClicked() {
        val f = folderUri
        if (f == null) pickerRequest = Picker.SCAN
        else dialog = UiDialog.ScanChoice(
            runCatching { DocumentsContract.getTreeDocumentId(f) }.getOrDefault(f.toString()),
            fileIndex.files.size
        )
    }

    fun rescanSavedFolder() {
        folderUri?.let { scan(it) }
    }

    fun pickScanFolder() { pickerRequest = Picker.SCAN }

    private fun scan(tree: Uri) {
        cancelFlag = false
        dialog = UiDialog.Progress("Quét thư mục", "Đang quét...", 0, 0)
        workJob = viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    FileScanner.scan(ctx, tree, { cancelFlag }) { n ->
                        dialog = UiDialog.Progress("Quét thư mục", "Đã tìm thấy $n file...", 0, 0)
                    }
                }
                if (cancelFlag) {
                    dialog = UiDialog.Message("Quét thư mục", "Đã hủy.")
                    return@launch
                }
                withContext(Dispatchers.IO) { db.replaceFiles(list) }
                fileIndex = FileIndex(list)
                val t = table
                var matched = 0
                var total = 0
                var missing = ""
                if (t != null) {
                    val nt = withContext(Dispatchers.Default) { t.withLinks(fileIndex) }
                    total = nt.rows.size
                    matched = nt.rows.count { it[nt.linkIdx].isNotEmpty() }
                    missing = nt.rows.filter { it[nt.linkIdx].isEmpty() }.take(300)
                        .joinToString("\n") { if (nt.docIdx != null) it[nt.docIdx] else "" }
                    table = nt
                    maskCache.clear()
                    applyFilters()
                }
                val text = buildString {
                    append("Đã quét ${list.size} file.")
                    if (t != null) append("\nĐã ghép LINK cho $matched/$total bản ghi.")
                    else append("\nHãy bấm 📂 Load File để nạp Excel, link sẽ được ghép tự động.")
                }
                dialog = UiDialog.Message(
                    "Kết quả quét", text,
                    if (missing.isNotEmpty()) "Bản ghi chưa có file tương ứng:\n$missing" else ""
                )
            } catch (e: Exception) {
                dialog = UiDialog.Message("Lỗi", "Không quét được thư mục:\n${e.message}")
            }
        }
    }

    fun cancelWork() { cancelFlag = true }

    // ======================================================== chọn thư mục xong
    fun onTreePicked(uri: Uri?) {
        val p = currentPicker
        currentPicker = null
        if (uri == null) {
            if (p == Picker.EXPORT) pendingExport = null
            return
        }
        when (p) {
            Picker.SCAN -> {
                persist(uri)
                folderUri = uri
                savePref("folder", uri)
                scan(uri)
            }
            Picker.CONFIG -> {
                persist(uri)
                configUri = uri
                savePref("config", uri)
                reloadConfig()
            }
            Picker.EXPORT -> {
                persist(uri, write = true)
                pendingExport?.let { runExport(it, uri) }
                pendingExport = null
            }
            else -> {}
        }
    }

    // ============================================================== lọc / sắp xếp
    private class FilterInput(
        val t: TableData, val active: List<FilterButton>, val doc: String, val title: String,
        val all: String, val sortCol: Int?, val asc: Boolean, val flagged: Set<String>,
        val titleCol: Int
    )

    fun applyFilters() {
        debounceJob?.cancel()
        filterJob?.cancel()
        val t = table
        if (t == null) { order = emptyList(); return }
        val input = FilterInput(
            t, activeFilters.values.toList(), txtDoc.trim(), txtTitle.trim(), txtAll.trim(),
            sortCol, sortAsc, notes.filterValues { it.flag }.keys.toSet(),
            t.columns.indexOfFirst { norm(it) == norm(config.settings.titleColumn) }
        )
        val typeNames = config.settings
        filterJob = viewModelScope.launch {
            val missing = ArrayList<String>()
            val idx = withContext(Dispatchers.Default) { compute(input, missing, typeNames) }
            selected = emptySet()
            order = idx
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            var msg = "$excelName - hiển thị ${idx.size}/${t.rows.size} bản ghi (cập nhật $time) | " +
                "${fileIndex.files.size} file đã quét"
            if (missing.isNotEmpty()) msg += " | Không có cột: " + missing.distinct().sorted().joinToString(", ")
            status = msg
        }
    }

    private fun terms(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }

    private fun compute(inp: FilterInput, missing: MutableList<String>, s: Settings): List<Int> {
        val t = inp.t
        val n = t.rows.size
        val mask = BooleanArray(n) { true }
        var noteFilter = false

        for (b in inp.active) {
            if (b.special == "note_flag") { noteFilter = true; continue }
            for ((colName, values) in b.filter ?: emptyMap()) {
                val col = t.columns.indexOfFirst { norm(it) == norm(colName) }
                if (col < 0) { missing.add(colName); java.util.Arrays.fill(mask, false); continue }
                val m = maskCache.getOrPut(col to values) {
                    val ser = t.normalized(col)
                    val exact = HashSet<String>()
                    val words = ArrayList<String>()
                    for (v in values) {
                        val vn = norm(v)
                        if (vn.startsWith("*") && vn.endsWith("*")) words.add(vn.trim('*')) else exact.add(vn)
                    }
                    BooleanArray(n) { i -> ser[i] in exact || words.any { w -> ser[i].contains(w) } }
                }
                for (i in 0 until n) mask[i] = mask[i] && m[i]
            }
        }
        if (noteFilter && t.docIdx != null) {
            for (i in 0 until n) mask[i] = mask[i] && t.rows[i][t.docIdx] in inp.flagged
        }
        fun applyTerms(text: String, ser: Array<String>) {
            val ts = terms(text)
            if (ts.isEmpty()) return
            for (i in 0 until n) if (mask[i]) mask[i] = ts.all { ser[i].contains(it) }
        }
        if (inp.doc.isNotEmpty()) {
            if (t.docIdx == null) missing.add(s.documentColumn) else applyTerms(inp.doc, t.normalized(t.docIdx))
        }
        if (inp.title.isNotEmpty()) {
            if (inp.titleCol < 0) missing.add(s.titleColumn) else applyTerms(inp.title, t.normalized(inp.titleCol))
        }
        if (inp.all.isNotEmpty()) applyTerms(inp.all, t.blob)

        var idx = (0 until n).filter { mask[it] }
        val sc = inp.sortCol
        if (sc != null && sc < t.columns.size && idx.isNotEmpty()) {
            val keys = HashMap<Int, Long?>(idx.size * 2)
            for (i in idx) keys[i] = parseDateKey(t.rows[i][sc])
            idx = idx.sortedWith { a, b ->
                val ka = keys[a]; val kb = keys[b]
                when {
                    ka == null && kb == null -> 0
                    ka == null -> 1
                    kb == null -> -1
                    inp.asc -> ka.compareTo(kb)
                    else -> kb.compareTo(ka)
                }
            }
        }
        return idx
    }

    fun onHeaderClicked(col: Int) {
        if (col != releaseIdx) return
        when {
            sortCol != col -> { sortCol = col; sortAsc = false }
            !sortAsc -> sortAsc = true
            else -> sortCol = null
        }
        applyFilters()
    }

    // ============================================================ ẩn/hiện cột
    private fun saveHidden() = prefs.edit().putStringSet("hidden_cols", hiddenCols).apply()

    fun setColumnVisible(name: String, visible: Boolean) {
        hiddenCols = if (visible) hiddenCols - name else hiddenCols + name
        saveHidden()
    }

    fun showAllColumns() { hiddenCols = emptySet(); saveHidden() }

    // ================================================================ chọn dòng
    fun toggleSelect(pos: Int) { selected = if (pos in selected) selected - pos else selected + pos }
    fun selectAll() { selected = order.toSet() }
    fun clearSelection() { selected = emptySet() }

    // ==================================================== ghi chú / comment
    private fun updateNote(doc: String, f: (Note) -> Note) {
        val n = f(notes[doc] ?: Note())
        notes[doc] = n
        viewModelScope.launch(Dispatchers.IO) { db.saveNote(doc, n) }
    }

    fun setFlag(doc: String, checked: Boolean) {
        if (doc.isNotBlank()) updateNote(doc) { it.copy(flag = checked) }
    }

    fun setCommentText(doc: String, key: Int, text: String) {
        val t = text.trim()
        if (t == (notes[doc]?.cell(key)?.text ?: "")) return
        updateNote(doc) { it.withCell(key, Cell(text = t)) }
    }

    fun clearComment(doc: String, key: Int) = updateNote(doc) { it.withCell(key, Cell()) }

    fun renameButton(doc: String, key: Int) {
        val cur = notes[doc]?.cell(key)?.name ?: ""
        dialog = UiDialog.TextInput("Đổi tên nút", "Tên hiển thị của nút:", cur, onOk = { name ->
            if (name.isNotBlank()) updateNote(doc) { n -> n.withCell(key, n.cell(key).copy(name = name.trim())) }
        })
    }

    fun typeComment(doc: String, key: Int) {
        val cur = notes[doc]?.cell(key)?.text ?: ""
        dialog = UiDialog.TextInput("Ghi chú", "Gõ nội dung:", cur, onOk = { setCommentText(doc, key, it) })
    }

    fun openLinkPicker(doc: String, key: Int) { dialog = UiDialog.LinkPicker(doc, key) }

    fun pickLink(doc: String, key: Int, pos: Int) {
        val t = table ?: return
        val link = t.rows[pos][t.linkIdx]
        val selDoc = t.doc(pos)
        if (link.isEmpty()) { toast("Bản ghi này chưa có giá trị LINK."); return }
        val currentName = notes[doc]?.cell(key)?.name ?: ""
        fun save(typed: String) {
            val name = typed.trim().ifEmpty { currentName.ifEmpty { selDoc.ifEmpty { "Mở link" } } }
            updateNote(doc) { it.withCell(key, Cell(link = link, name = name, text = "")) }
        }
        dialog = UiDialog.TextInput(
            "Đặt tên nút", "Nhập tên hiển thị cho nút liên kết:", currentName.ifEmpty { selDoc },
            onOk = { save(it) }, onCancel = { save("") }
        )
    }

    // ============================================================ tương tác ô
    fun onCellClick(pos: Int, col: Int) {
        val t = table ?: return
        if (selected.isNotEmpty()) { toggleSelect(pos); return }
        val doc = t.doc(pos)
        if (col == t.docIdx) { openTarget(t.rows[pos][t.linkIdx]); return }
        val key = t.commentByCol[col] ?: return
        if (doc.isBlank()) return
        val link = notes[doc]?.cell(key)?.link ?: ""
        if (link.isNotEmpty()) openTarget(link) else dialog = UiDialog.CommentMenu(doc, key)
    }

    fun onCellLongClick(pos: Int, col: Int) {
        val t = table ?: return
        val key = t.commentByCol[col]
        val doc = t.doc(pos)
        if (key != null && selected.isEmpty() && doc.isNotBlank()) dialog = UiDialog.CommentMenu(doc, key)
        else toggleSelect(pos)
    }

    // ================================================================ mở link
    fun displayName(uri: String): String = fileIndex.nameByUri[uri]
        ?: Saf.info(ctx, Uri.parse(uri))?.name
        ?: Uri.decode(uri.substringAfterLast('/')).substringAfterLast(':')

    fun openTarget(raw: String) {
        val parts = raw.split('\n').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        when {
            parts.isEmpty() -> toast("Bản ghi này chưa có file trên điện thoại. Hãy bấm 📁 Quét thư mục.")
            parts.size == 1 -> openUri(parts[0])
            else -> dialog = UiDialog.Choose(
                "Có ${parts.size} file khớp - chọn file để mở",
                parts.map { displayName(it) to it }
            )
        }
    }

    fun openUri(raw: String) {
        try {
            val uri = Uri.parse(raw)
            val i = Intent(Intent.ACTION_VIEW)
            if (raw.startsWith("content://")) {
                val type = ctx.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        MimeTypeMap.getFileExtensionFromUrl(raw).lowercase()
                    ) ?: "*/*"
                i.setDataAndType(uri, type)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                i.data = uri
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            toast("Không có ứng dụng nào mở được loại file này.")
        } catch (e: Exception) {
            toast("Không mở được file: ${e.message}")
        }
    }

    // ================================================================ xuất file
    fun exportSelected() {
        val t = table ?: return
        val rows = selected.sorted()
        if (rows.isEmpty()) {
            dialog = UiDialog.Message("Xuất file", "Hãy chọn ít nhất một bản ghi (nhấn giữ một dòng để chọn, chạm để chọn thêm).")
            return
        }
        val files = ArrayList<String>()
        val seen = HashSet<String>()
        val problems = ArrayList<String>()
        for (pos in rows) {
            val doc = t.doc(pos)
            val raw = t.rows[pos][t.linkIdx]
            if (raw.isBlank()) { problems.add("$doc: không có giá trị LINK"); continue }
            for (part in raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (part.startsWith("http://") || part.startsWith("https://") || part.startsWith("mailto:"))
                    problems.add("$doc: $part (link web, không thể sao chép)")
                else if (seen.add(part)) files.add(part)
            }
        }
        if (files.isEmpty()) {
            dialog = UiDialog.Message(
                "Xuất file", "Không có file nào để xuất từ các bản ghi đã chọn.", problems.joinToString("\n")
            )
            return
        }
        pendingExport = PendingExport(files, problems)
        pickerRequest = Picker.EXPORT
        toast("Chọn thư mục đích để xuất ${files.size} file")
    }

    private fun uniqueName(name: String, taken: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while ("$base ($n)$ext".lowercase() in taken) n++
        return "$base ($n)$ext"
    }

    private fun runExport(p: PendingExport, destTree: Uri) {
        cancelFlag = false
        val total = p.files.size
        dialog = UiDialog.Progress("Xuất file", "Đang xuất file...", 0, total)
        workJob = viewModelScope.launch {
            var copied = 0
            var skipped = 0
            val errors = ArrayList<String>()
            withContext(Dispatchers.IO) {
                val destRoot = Saf.rootDocId(destTree)
                val destUri = Saf.docUri(destTree, destRoot)
                val existing = Saf.children(ctx, destTree, destRoot)
                    .associate { it.name.lowercase() to it.size }.toMutableMap()
                for ((i, u) in p.files.withIndex()) {
                    if (cancelFlag) break
                    val src = Uri.parse(u)
                    val info = Saf.info(ctx, src)
                    val name = info?.name ?: fileIndex.nameByUri[u] ?: "file_$i"
                    dialog = UiDialog.Progress("Xuất file", "($i/$total) $name", i, total)
                    try {
                        if (info == null) throw IllegalStateException("không tìm thấy file")
                        val parent = info.docId.substringBeforeLast('/', "")
                        if (parent == destRoot && src.authority == destTree.authority) { skipped++; continue }
                        val sameName = existing.containsKey(name.lowercase())
                        if (sameName && existing[name.lowercase()] == info.size && info.size >= 0) { skipped++; continue }
                        val target = if (sameName) uniqueName(name, existing.keys) else name
                        val out = DocumentsContract.createDocument(ctx.contentResolver, destUri, info.mime.ifEmpty { "application/octet-stream" }, target)
                            ?: throw IllegalStateException("không tạo được file đích")
                        ctx.contentResolver.openInputStream(src)!!.use { ins ->
                            ctx.contentResolver.openOutputStream(out)!!.use { os -> ins.copyTo(os) }
                        }
                        existing[target.lowercase()] = info.size
                        copied++
                    } catch (e: Exception) {
                        errors.add("$name: ${e.message}")
                    }
                }
            }
            var summary = "Đã xuất $copied file vào thư mục đã chọn."
            if (skipped > 0) summary += "\nBỏ qua $skipped file (đã có sẵn, giống hệt)."
            if (cancelFlag) summary += "\nĐã hủy giữa chừng."
            if (errors.isNotEmpty()) summary += "\nLỗi: ${errors.size} file."
            if (p.problems.isNotEmpty()) summary += "\nKhông xuất được: ${p.problems.size} mục (xem chi tiết)."
            dialog = UiDialog.Message(
                "Kết quả xuất file", summary, (errors + p.problems).joinToString("\n"),
                folder = Saf.docUri(destTree, Saf.rootDocId(destTree))
            )
        }
    }

    fun openFolder(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
            toast("Không mở được thư mục, hãy mở bằng ứng dụng Tệp.")
        }
    }
}
