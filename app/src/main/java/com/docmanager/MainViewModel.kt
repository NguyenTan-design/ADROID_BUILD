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
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val MIN_W = 40
const val PAGE_SIZE = 50

enum class Picker { EXCEL, SCAN, CONFIG, EXPORT }

private class PendingExport(val files: List<String>, val problems: List<String>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val db = Db.get(app)
    private val prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE)

    // ---- cấu hình & nút lọc
    var config by mutableStateOf(AppConfig.default()); private set
    var filterButtons by mutableStateOf<List<FilterButton>>(emptyList()); private set
    var activeFilters by mutableStateOf<Map<String, FilterButton>>(emptyMap()); private set

    // ---- dữ liệu
    var table by mutableStateOf<TableData?>(null); private set
    var order by mutableStateOf<List<Int>>(emptyList()); private set
    var colWidths by mutableStateOf<List<Int>>(emptyList()); private set
    var page by mutableStateOf(prefs.getInt("page", 0)); private set
    var hiddenDocs by mutableStateOf<Set<String>>(emptySet()); private set
    var deletedDocs by mutableStateOf<Set<String>>(emptySet()); private set
    val notes = mutableStateMapOf<String, Note>()
    var fileIndex by mutableStateOf(FileIndex(emptyList())); private set

    // ---- ô tìm kiếm / trạng thái giao diện
    var txtDoc by mutableStateOf(prefs.getString("txt_doc", "") ?: ""); private set
    var txtTitle by mutableStateOf(prefs.getString("txt_title", "") ?: ""); private set
    var txtAll by mutableStateOf(prefs.getString("txt_all", "") ?: ""); private set
    var filterPanelOpen by mutableStateOf(prefs.getBoolean("filter_open", true)); private set
    var pdfMode by mutableStateOf(prefs.getString("pdf_mode", "external") ?: "external"); private set
    var hiddenCols by mutableStateOf<Set<String>>(emptySet()); private set
    var sortCol by mutableStateOf<Int?>(null); private set
    var sortAsc by mutableStateOf(prefs.getBoolean("sort_asc", false)); private set
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

    // ---- khôi phục trạng thái lần đóng cuối
    private val hasSavedExcel = prefs.getString("excel", null) != null
    private var startupRestore = hasSavedExcel                       // giữ nguyên trang khi nạp lần đầu
    var scrollRestorePending = hasSavedExcel                         // UI sẽ cuộn về vị trí cũ 1 lần
    val restoreIndex: Int = prefs.getInt("scroll_idx", 0)
    val restoreH: Float = prefs.getFloat("scroll_h", 0f)
    private var pendingSortName: String? = if (hasSavedExcel) prefs.getString("sort_col", null) else null
    private var pendingFilterIds: Set<String>? =
        prefs.getString("filters", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet()
    private var pendingSelectionDocs: Set<String>? =
        if (hasSavedExcel) prefs.getStringSet("sel_docs", null)?.toSet() else null
    private var selSaveJob: Job? = null

    private var widthSaveJob: Job? = null
    private var debounceJob: Job? = null
    private var filterJob: Job? = null
    private var workJob: Job? = null
    @Volatile private var cancelFlag = false
    private val maskCache = ConcurrentHashMap<Pair<Int, List<String>>, BooleanArray>()

    val pageCount: Int get() = maxOf(1, (order.size + PAGE_SIZE - 1) / PAGE_SIZE)

    /** Các dòng (vị trí gốc trong bảng) của trang hiện tại - mỗi trang chỉ render PAGE_SIZE bản ghi. */
    val pageItems: List<Int>
        get() {
            val from = (page * PAGE_SIZE).coerceAtMost(order.size)
            return order.subList(from, minOf(order.size, from + PAGE_SIZE))
        }

    fun goPage(p: Int) { page = p.coerceIn(0, pageCount - 1); savePage() }

    fun askJumpPage() {
        dialog = UiDialog.TextInput("Đi tới trang", "Nhập số trang (1 - $pageCount):", (page + 1).toString(), onOk = { txt ->
            txt.trim().toIntOrNull()?.let { goPage(it - 1) }
        })
    }

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
            checkTypeConfig(false)
            if (excelUri != null) loadData(newFile = true)
        }
    }

    val canRefresh: Boolean get() = excelUri != null

    // =============================================== lưu / khôi phục trạng thái
    private fun saveFilters() {
        prefs.edit().putString("filters", activeFilters.values.joinToString(",") { it.id }).apply()
    }

    private fun saveTexts() {
        prefs.edit().putString("txt_doc", txtDoc).putString("txt_title", txtTitle).putString("txt_all", txtAll).apply()
    }

    private fun saveSort() {
        val name = sortCol?.let { table?.columns?.getOrNull(it) }
        prefs.edit().putString("sort_col", name).putBoolean("sort_asc", sortAsc).apply()
    }

    private fun savePage() = prefs.edit().putInt("page", page).apply()

    fun saveScroll(index: Int, h: Float) {
        if (scrollRestorePending) return
        prefs.edit().putInt("scroll_idx", index).putFloat("scroll_h", h).apply()
    }

    fun toggleFilterPanel() {
        filterPanelOpen = !filterPanelOpen
        prefs.edit().putBoolean("filter_open", filterPanelOpen).apply()
    }

    /** Đặt các dòng đang chọn và ghi nhớ (theo Document No). */
    private fun updateSelection(s: Set<Int>) {
        selected = s
        selSaveJob?.cancel()
        selSaveJob = viewModelScope.launch {
            delay(400)
            val t = table ?: return@launch
            val docs = selected.filter { it < t.rows.size }.map { t.doc(it) }.filter { it.isNotBlank() }.toSet()
            prefs.edit().putStringSet("sel_docs", docs).apply()
        }
    }

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
            checkTypeConfig(true)
            if (excelUri != null) loadData()
        }
    }

    /** Cảnh báo nếu chưa có file TYPE.config (dùng cho Quick Note). */
    private suspend fun checkTypeConfig(announceOk: Boolean) {
        val r = withContext(Dispatchers.IO) { TypeConfig.load(ctx) }
        if (!r.found || r.values.isEmpty()) {
            if (dialog == null) {
                dialog = UiDialog.Message(
                    "Thiếu file TYPE.config",
                    r.message + "\n\nQuick Note cần file TYPE.config (liệt kê các giá trị cột TYPE, mỗi dòng 1 giá trị) " +
                        "đặt trong thư mục config. Sau khi đặt file, bấm ⚙ Reload Config để đọc lại."
                )
            }
        } else if (announceOk) {
            toast("Đã đọc TYPE.config: ${r.values.size} giá trị TYPE.")
        }
    }

    /** Gọi khi quay lại màn hình chính (ví dụ sau khi lưu Quick Note trong trình xem PDF). */
    fun onResume() {
        val c = pref("config")
        if (c?.toString() != configUri?.toString()) {
            configUri = c
            reloadConfig()
        }
        refreshQuickNotes()
    }

    fun refreshQuickNotes() {
        val t = table ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { db.allQuickNotes() }
            val current = (t.rows.size - t.quickCount until t.rows.size).map { t.doc(it) }
            if (current == list.map { it.docNo }) return@launch
            val nt = withContext(Dispatchers.Default) { t.withQuick(list, config.settings) }
            table = nt
            maskCache.clear()
            applyFilters(keepPage = true)
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
            if (out.none { it.group == "category" && it.label.equals("IMAGE NOTE", true) }) {
                out.add(FilterButton("catqn", "category", "IMAGE NOTE", cfg.categoryStyle, mapOf(catCol to listOf("IMAGE NOTE"))))
            }
            out.add(
                FilterButton("hide", "hide", "HIDE", BtnStyle(Color(0xFF8D6E63), Color.White, Color(0xFF5D4037), true), special = "hidden")
            )
            out
        }
        filterButtons = list
        val keep = pendingFilterIds ?: activeFilters.values.map { it.id }.toSet()
        pendingFilterIds = null
        activeFilters = list.filter { it.id in keep }.associateBy { it.group }
        saveFilters()
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
        saveFilters()
        applyFilters()
    }

    fun resetAll() {
        activeFilters = emptyMap()
        txtDoc = ""; txtTitle = ""; txtAll = ""
        saveFilters()
        saveTexts()
        applyFilters()
    }

    @JvmName("onDocTextChanged")
    fun setTxtDoc(s: String) { txtDoc = s; saveTexts(); scheduleFilter() }
    @JvmName("onTitleTextChanged")
    fun setTxtTitle(s: String) { txtTitle = s; saveTexts(); scheduleFilter() }
    @JvmName("onAllTextChanged")
    fun setTxtAll(s: String) { txtAll = s; saveTexts(); scheduleFilter() }

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
        saveSort()
        startupRestore = false
        scrollRestorePending = false
        pendingSortName = null
        pendingSelectionDocs = null
        prefs.edit().putInt("scroll_idx", 0).putFloat("scroll_h", 0f).remove("sel_docs").apply()
        loadData(newFile = true)
    }

    fun loadData(newFile: Boolean = false) {
        val uri = excelUri ?: return
        viewModelScope.launch {
            status = "Đang tải dữ liệu..."
            try {
                val s = config.settings
                val idxNow = fileIndex
                val (data, loaded, hidDel) = coroutineScope {
                    val notesJob = async(Dispatchers.IO) { db.allNotes() }
                    val hiddenJob = async(Dispatchers.IO) { db.allHidden() }
                    val deletedJob = async(Dispatchers.IO) { db.allDeleted() }
                    val tableJob = async(Dispatchers.IO) {
                        val raw = XlsxReader.read(ctx, uri, s.sheetName, s.headerRow, s.dateFormat)
                        TableBuilder.build(raw, s).withLinks(idxNow).withQuick(db.allQuickNotes(), s)
                    }
                    Triple(tableJob.await(), notesJob.await(), hiddenJob.await() to deletedJob.await())
                }
                val widths = withContext(Dispatchers.Default) { computeWidths(data, s) }
                notes.clear(); notes.putAll(loaded)
                hiddenDocs = hidDel.first
                deletedDocs = hidDel.second
                maskCache.clear()
                excelName = Saf.info(ctx, uri)?.name ?: "Excel"
                pendingSortName?.let { n ->
                    val i = data.columns.indexOf(n)
                    sortCol = if (i >= 0) i else null
                    pendingSortName = null
                }
                hiddenCols = hiddenCols.intersect(data.columns.toSet())
                saveHidden()
                if (sortCol != null && sortCol!! >= data.columns.size) sortCol = null
                colWidths = widths
                order = emptyList()          // tránh vị trí dòng cũ vượt quá bảng mới
                selected = emptySet()
                table = data
                applyFilters(keepPage = !newFile || startupRestore)
                startupRestore = false
            } catch (e: java.io.FileNotFoundException) {
                status = "Không tìm thấy file Excel."
                dialog = UiDialog.Message("Lỗi", "Không tìm thấy file Excel. Hãy bấm 📂 Load File để chọn lại.")
            } catch (e: Exception) {
                status = "Lỗi tải dữ liệu."
                dialog = UiDialog.Message("Lỗi", "Có lỗi xảy ra: ${e.message}")
            }
        }
    }

    private fun savedWidths(): JSONObject = try {
        JSONObject(prefs.getString("col_widths", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun computeWidths(t: TableData, s: Settings, useSaved: Boolean = true): List<Int> {
        val saved = if (useSaved) savedWidths() else JSONObject()
        val maxW = minOf(s.maxColumnWidth, 220)
        return t.columns.mapIndexed { c, name ->
            if (saved.has(name)) return@mapIndexed saved.optInt(name, 120).coerceIn(MIN_W, maxWidthFor(c))
            var w = name.length * 8 + 44
            var a = 0; var b = 0; var d = 0
            for (r in t.rows) {
                val l = r[c].length
                if (l > a) { d = b; b = a; a = l } else if (l > b) { d = b; b = l } else if (l > d) d = l
            }
            for (l in listOf(a, b, d)) w = maxOf(w, minOf(l, 60) * 7 + 20)
            if (c in t.commentByCol) w = maxOf(w, 130)
            if (c == t.noteIdx) w = maxOf(w, 80)
            if (c == 0) w.coerceIn(90, 150) else w.coerceIn(60, maxOf(60, maxW))
        }
    }

    private fun maxWidthFor(c: Int) = if (c == 0) 240 else 600

    /** Kéo mép phải tiêu đề cột để co/giãn. Độ rộng được nhớ cho lần sau. */
    fun setColWidth(c: Int, w: Int) {
        if (c !in colWidths.indices) return
        val nw = w.coerceIn(MIN_W, maxWidthFor(c))
        if (colWidths[c] == nw) return
        colWidths = colWidths.toMutableList().also { it[c] = nw }
        widthSaveJob?.cancel()
        widthSaveJob = viewModelScope.launch { delay(400); saveWidths() }
    }

    private fun saveWidths() {
        val t = table ?: return
        val o = savedWidths()
        t.columns.forEachIndexed { i, n -> if (i in colWidths.indices) o.put(n, colWidths[i]) }
        prefs.edit().putString("col_widths", o.toString()).apply()
    }

    fun resetWidths() {
        val t = table ?: return
        prefs.edit().remove("col_widths").apply()
        colWidths = computeWidths(t, config.settings, useSaved = false)
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
                    applyFilters(keepPage = true)
                }
                val text = buildString {
                    append("Đã quét ${list.size} file.")
                    if (t != null) append("\nĐã ghép LINK cho $matched/$total bản ghi.")
                    else append("\nHãy bấm 📂 Load File để nạp Excel, link sẽ được ghép tự động.")
                    if (list.isEmpty()) append("\nKhông thấy file nào - hãy chọn đúng thư mục có chứa tài liệu.")
                }
                val diag = buildString {
                    append("Tên file quét được (mẫu):\n")
                    list.take(6).forEach { append("• ${it.name}\n") }
                    if (t != null) {
                        append("\nDocument No trong Excel (mẫu):\n")
                        t.rows.indices.take(6).forEach { append("• ${t.doc(it)}\n") }
                    }
                    if (missing.isNotEmpty()) append("\nBản ghi chưa có file tương ứng:\n$missing")
                }
                dialog = UiDialog.Message("Kết quả quét", text, diag)
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
        val titleCol: Int, val hidden: Set<String>, val hiddenOnly: Boolean, val deleted: Set<String>
    )

    fun applyFilters(keepPage: Boolean = false) {
        debounceJob?.cancel()
        filterJob?.cancel()
        val t = table
        if (t == null) { order = emptyList(); return }
        val input = FilterInput(
            t, activeFilters.values.toList(), txtDoc.trim(), txtTitle.trim(), txtAll.trim(),
            sortCol, sortAsc,
            if (activeFilters.values.any { it.special == "note_flag" }) notes.filterValues { it.flag }.keys.toSet() else emptySet(),
            t.columns.indexOfFirst { norm(it) == norm(config.settings.titleColumn) },
            hiddenDocs, activeFilters.values.any { it.special == "hidden" }, deletedDocs
        )
        val typeNames = config.settings
        filterJob = viewModelScope.launch {
            val missing = ArrayList<String>()
            val idx = withContext(Dispatchers.Default) { compute(input, missing, typeNames) }
            val ps = pendingSelectionDocs
            pendingSelectionDocs = null
            updateSelection(
                if (ps != null) t.rows.indices.filter { t.doc(it) in ps }.toSet() else emptySet()
            )
            order = idx
            page = if (keepPage) page.coerceIn(0, maxOf(0, (idx.size + PAGE_SIZE - 1) / PAGE_SIZE - 1)) else 0
            savePage()
            if (idx.isEmpty()) scrollRestorePending = false
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val delPresent = if (t.docIdx == null || deletedDocs.isEmpty()) 0 else t.rows.count { it[t.docIdx] in deletedDocs }
            var msg = "$excelName - hiển thị ${idx.size}/${t.rows.size - delPresent} bản ghi (cập nhật $time) | " +
                "${fileIndex.files.size} file đã quét" + (if (hiddenDocs.isNotEmpty()) " | ${hiddenDocs.size} dòng đã Hide" else "")
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
            if (b.special == "hidden") continue
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
        // Dòng đã xóa: không bao giờ hiện
        if (t.docIdx != null && inp.deleted.isNotEmpty()) {
            for (i in 0 until n) if (mask[i] && t.rows[i][t.docIdx] in inp.deleted) mask[i] = false
        }
        // Dòng đã Hide: mặc định bị ẩn khỏi bảng; bấm nút HIDE thì chỉ hiện các dòng đã ẩn
        if (t.docIdx != null && (inp.hiddenOnly || inp.hidden.isNotEmpty())) {
            for (i in 0 until n) {
                if (!mask[i]) continue
                val isHidden = t.rows[i][t.docIdx] in inp.hidden
                mask[i] = if (inp.hiddenOnly) isHidden else !isHidden
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
            val keys = t.dateKeys(sc)
            val none = Long.MIN_VALUE
            idx = idx.sortedWith { a, b ->
                val ka = keys[a]
                val kb = keys[b]
                when {
                    ka == none && kb == none -> 0
                    ka == none -> 1
                    kb == none -> -1
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
        saveSort()
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
    fun toggleSelect(pos: Int) { updateSelection(if (pos in selected) selected - pos else selected + pos) }
    fun selectAll() { updateSelection(order.toSet()) }
    fun clearSelection() { updateSelection(emptySet()) }

    // ==================================================== ghi chú / comment
    private fun updateNote(doc: String, f: (Note) -> Note) {
        val n = f(notes[doc] ?: Note())
        notes[doc] = n
        viewModelScope.launch(Dispatchers.IO) { db.saveNote(doc, n) }
    }

    fun setFlag(doc: String, checked: Boolean) {
        if (doc.isBlank()) return
        updateNote(doc) { it.copy(flag = checked) }
        if (activeFilters.values.any { it.special == "note_flag" }) applyFilters(keepPage = true)
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
        if (t.titleIdx >= 0 && col == t.titleIdx) { showRowPopup(pos); return }
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

    // ================================================================ xóa dòng
    fun askDeleteRows() {
        val t = table ?: return
        val pos = selected.filter { it < t.rows.size }.sorted()
        if (pos.isEmpty()) {
            dialog = UiDialog.Message("Xóa dòng", "Hãy chọn ít nhất một bản ghi (nhấn giữ một dòng để chọn, chạm để chọn thêm).")
            return
        }
        dialog = UiDialog.ConfirmDelete(pos)
    }

    fun deleteRows(positions: List<Int>) {
        val t = table ?: return
        val quickStart = t.rows.size - t.quickCount
        val valid = positions.filter { it < t.rows.size }
        val excelDocs = valid.filter { it < quickStart }.map { t.doc(it) }.filter { it.isNotBlank() }.toSet()
        val quickDocs = valid.filter { it >= quickStart }.map { t.doc(it) }.filter { it.isNotBlank() }.toSet()
        if (excelDocs.isEmpty() && quickDocs.isEmpty()) {
            toast("Các dòng đã chọn không có Document No nên không xóa được.")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.setDeleted(excelDocs, true)
                quickDocs.forEach { db.deleteQuickNote(it) }
            }
            deletedDocs = deletedDocs + excelDocs
            if (quickDocs.isNotEmpty()) {
                val list = withContext(Dispatchers.IO) { db.allQuickNotes() }
                table = withContext(Dispatchers.Default) { t.withQuick(list, config.settings) }
                maskCache.clear()
            }
            toast("Đã xóa ${excelDocs.size + quickDocs.size} dòng.")
            applyFilters(keepPage = true)
        }
    }

    fun restoreDeleted() {
        if (deletedDocs.isEmpty()) return
        val docs = deletedDocs
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.setDeleted(docs, false) }
            deletedDocs = emptySet()
            toast("Đã khôi phục ${docs.size} dòng.")
            applyFilters(keepPage = true)
        }
    }

    // ================================================================ popup Title / Hide
    /** Chạm ô Title: nếu dòng đó đang nằm trong các dòng đã chọn thì thao tác áp dụng cho tất cả dòng đã chọn. */
    fun showRowPopup(pos: Int) {
        val sel = selected
        val positions = if (sel.isNotEmpty() && pos in sel) sel.sorted() else listOf(pos)
        dialog = UiDialog.RowPopup(positions)
    }

    fun hideRows(positions: List<Int>) {
        val t = table ?: return
        val docs = positions.filter { it < t.rows.size }.map { t.doc(it) }.filter { it.isNotBlank() }.toSet()
        if (docs.isEmpty()) { toast("Dòng không có Document No nên không thể Hide."); return }
        hiddenDocs = hiddenDocs + docs
        viewModelScope.launch(Dispatchers.IO) { db.setHidden(docs, true) }
        toast("Đã Hide ${docs.size} dòng. Bấm nút HIDE ở bộ lọc để xem lại.")
        applyFilters(keepPage = true)
    }

    fun unhideRows(positions: List<Int>) {
        val t = table ?: return
        val docs = positions.filter { it < t.rows.size }.map { t.doc(it) }.filter { it.isNotBlank() }.toSet()
        if (docs.isEmpty()) return
        hiddenDocs = hiddenDocs - docs
        viewModelScope.launch(Dispatchers.IO) { db.setHidden(docs, false) }
        toast("Đã Unhide ${docs.size} dòng.")
        applyFilters(keepPage = true)
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

    // ================================================================ mở PDF
    fun togglePdfMode() {
        pdfMode = if (pdfMode == "external") "inapp" else "external"
        prefs.edit().putString("pdf_mode", pdfMode).apply()
        toast(
            if (pdfMode == "external") "PDF sẽ mở bằng Adobe Acrobat kèm nút Quick Note nổi."
            else "PDF sẽ mở bằng trình xem trong app (có Quick Note tích hợp)."
        )
    }

    private fun openPdf(raw: String, name: String) {
        if (pdfMode == "inapp") {
            ctx.openPdfInApp(raw, name)
            return
        }
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            dialog = UiDialog.OverlayHelp(raw, name)
            return
        }
        QuickNoteOverlayService.begin(ctx, raw)
    }

    fun openOverlaySettings() {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            toast("Hãy vào Cài đặt > Ứng dụng > Document Management > Hiển thị trên các ứng dụng khác.")
        }
    }

    fun openPdfPlain(raw: String) = openPdfExternal(ctx, raw)

    fun openUri(raw: String) {
        if (raw.startsWith("content://")) {
            val isPdf = fileIndex.nameByUri[raw]?.endsWith(".pdf", true) == true ||
                try { ctx.contentResolver.getType(Uri.parse(raw)) == "application/pdf" } catch (_: Exception) { false }
            if (isPdf) {
                openPdf(raw, fileIndex.nameByUri[raw] ?: "")
                return
            }
        }
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
        val rows = selected.filter { it < t.rows.size }.sorted()
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
