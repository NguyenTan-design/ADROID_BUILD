package com.docmanager

import android.net.Uri

// ------------------------------------------------------------------ Notes
data class Cell(val link: String = "", val name: String = "", val text: String = "")

data class Note(val flag: Boolean = false, val cells: List<Cell> = List(4) { Cell() }) {
    fun cell(key: Int): Cell = cells[key - 1]
    fun withCell(key: Int, c: Cell): Note =
        copy(cells = cells.toMutableList().also { it[key - 1] = c })
}

val NOTE_FIELDS = (1..4).flatMap { i -> listOf("link", "name", "text").map { "c${i}_$it" } }

// ------------------------------------------------------- File trên điện thoại
data class ScannedFile(val uri: String, val name: String, val path: String)

/**
 * Chỉ mục các file đã quét. Ghép theo thứ tự ưu tiên (dừng ở bước đầu tiên có kết quả):
 *  1) Tên file ghi sẵn trong Excel (ví dụ cột LINK cũ trên PC: lấy phần tên file cuối đường dẫn,
 *     hoặc bất kỳ ô nào trông như tên file "xxx.pdf") khớp tên file trên điện thoại
 *  2) Document No trùng hẳn tên file (bỏ đuôi), hoặc nằm nguyên cụm trong tên file
 *  3) Title trùng tên file
 *  4) Bỏ hết dấu cách/gạch/chấm rồi so sánh (ABC 123 ~ ABC123.pdf)
 */
class FileIndex(val files: List<ScannedFile>) {
    private val exact = HashMap<String, MutableList<ScannedFile>>()
    private val windows = HashMap<String, MutableList<ScannedFile>>()
    private val byFullName = HashMap<String, MutableList<ScannedFile>>()
    private val byAlnum = HashMap<String, MutableList<ScannedFile>>()
    private val alnumNames: Array<String> = Array(files.size) { alnum(stripExt(files[it].name)) }
    val nameByUri: Map<String, String> = files.associate { it.uri to it.name }
    private val offsets = IntArray(files.size)
    private val big: String = buildString {
        for (i in files.indices) { offsets[i] = length; append(alnumNames[i]); append('|') }
    }

    init {
        for ((i, f) in files.withIndex()) {
            byFullName.getOrPut(f.name.trim().lowercase()) { ArrayList() }.add(f)
            if (alnumNames[i].isNotEmpty()) byAlnum.getOrPut(alnumNames[i]) { ArrayList() }.add(f)
            val toks = tokens(stripExt(f.name))
            if (toks.isEmpty()) continue
            exact.getOrPut(toks.joinToString(" ")) { ArrayList() }.add(f)
            val seen = HashSet<String>()
            for (n in 1..minOf(toks.size, MAX_WINDOW)) {
                for (k in 0..toks.size - n) {
                    val key = toks.subList(k, k + n).joinToString(" ")
                    if (seen.add(key)) windows.getOrPut(key) { ArrayList() }.add(f)
                }
            }
        }
    }

    /** Tìm da trong tất cả tên file bằng 1 lần quét chuỗi (nhanh hơn lặp từng file). */
    private fun containing(da: String, limit: Int): List<ScannedFile> {
        val res = ArrayList<ScannedFile>()
        var from = 0
        var last = -1
        while (res.size < limit && from < big.length) {
            val pos = big.indexOf(da, from)
            if (pos < 0) break
            var lo = 0
            var hi = offsets.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (offsets[mid] <= pos) lo = mid else hi = mid - 1
            }
            if (lo != last) { res.add(files[lo]); last = lo }
            from = if (lo + 1 < offsets.size) offsets[lo + 1] else big.length
        }
        return res
    }

    private fun sorted(l: List<ScannedFile>) = l.sortedBy { it.path.lowercase() }

    fun match(doc: String, fileHints: List<String> = emptyList(), title: String = ""): List<ScannedFile> {
        for (h in fileHints) byFullName[h.trim().lowercase()]?.let { return sorted(it) }
        for (h in fileHints) {
            val k = tokens(stripExt(h)).joinToString(" ")
            if (k.isNotEmpty()) exact[k]?.let { return sorted(it) }
        }
        val dk = tokens(doc).joinToString(" ")
        if (dk.isNotEmpty()) (exact[dk] ?: windows[dk])?.let { return sorted(it) }
        val tk = tokens(title).joinToString(" ")
        if (tk.length >= 6) exact[tk]?.let { return sorted(it) }
        val da = alnum(doc)
        if (da.isNotEmpty()) {
            byAlnum[da]?.let { return sorted(it) }
            if (da.length >= 6) {
                val res = containing(da, 20)
                if (res.isNotEmpty()) return sorted(res)
            }
        }
        return emptyList()
    }

    companion object {
        private const val MAX_WINDOW = 8
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        fun tokens(s: String): List<String> = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }
        fun alnum(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
        fun stripExt(name: String): String {
            val i = name.lastIndexOf('.')
            return if (i > 0 && name.length - i <= 6) name.substring(0, i) else name
        }
    }
}

// ------------------------------------------------------------------ Bảng dữ liệu
class TableData(
    val columns: List<String>,
    val rows: List<Array<String>>,
    val docIdx: Int?,
    val linkIdx: Int,
    val noteIdx: Int?,
    val commentIdx: Map<Int, Int>,         // số comment (1..4) -> cột
    val titleIdx: Int,
    val origLinks: List<String>,           // giá trị cột LINK gốc trong Excel (nếu còn) - dùng làm gợi ý tên file
    val quickCount: Int = 0                // số dòng Quick Note nằm ở cuối bảng
) {
    val commentByCol: Map<Int, Int> = commentIdx.entries.associate { it.value to it.key }
    private val normCache = HashMap<Int, Array<String>>()

    private val dateCache = HashMap<Int, LongArray>()

    /** Khóa ngày của cả cột (Long.MIN_VALUE = không đọc được) - chỉ tính 1 lần cho mỗi cột. */
    @Synchronized
    fun dateKeys(col: Int): LongArray =
        dateCache.getOrPut(col) {
            val qStart = rows.size - quickCount                      // dòng Quick Note ghi ngày dạng MM/dd/yyyy
            LongArray(rows.size) { parseDateKey(rows[it][col], it >= qStart) ?: Long.MIN_VALUE }
        }

    @Synchronized
    fun normalized(col: Int): Array<String> =
        normCache.getOrPut(col) { Array(rows.size) { norm(rows[it][col]) } }

    /** Chuỗi gộp mọi cột (trừ LINK, vì chứa đường dẫn hệ thống) để tìm "tất cả các cột". */
    val blob: Array<String> by lazy {
        Array(rows.size) { i ->
            val r = rows[i]
            val sb = StringBuilder()
            for (c in r.indices) {
                if (c == linkIdx) continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(r[c])
            }
            sb.toString().lowercase()
        }
    }

    fun doc(pos: Int): String = docIdx?.let { rows[pos][it] } ?: ""

    /** Thay cột LINK bằng đường link thật trên điện thoại (bước 3 của yêu cầu). */
    fun withLinks(index: FileIndex): TableData {
        val d = docIdx
        val skip = HashSet<Int>().apply {
            add(linkIdx); noteIdx?.let { add(it) }; addAll(commentIdx.values); d?.let { add(it) }
        }
        val newRows = rows.mapIndexed { i, r ->
            if (i >= rows.size - quickCount) return@mapIndexed r.copyOf()      // dòng Quick Note giữ nguyên LINK ảnh
            val c = r.copyOf()
            val hints = ArrayList<String>()
            hints.addAll(fileNames(origLinks.getOrElse(i) { "" }))
            for (j in r.indices) {
                if (j in skip) continue
                val v = r[j].trim()
                if (v.length in 4..79 && v.indexOf('.') > 0 && LOOKS_LIKE_FILE.matches(v)) hints.add(lastSegment(v))
            }
            val doc = if (d != null) r[d] else ""
            val title = if (titleIdx >= 0) r[titleIdx] else ""
            c[linkIdx] = index.match(doc, hints, title).joinToString("\n") { it.uri }
            c
        }
        return TableData(columns, newRows, docIdx, linkIdx, noteIdx, commentIdx, titleIdx, origLinks, quickCount)
    }

    /** Thay các dòng Quick Note ở cuối bảng bằng danh sách mới. */
    fun withQuick(list: List<QuickNote>, s: Settings): TableData {
        val base = if (quickCount > 0) rows.subList(0, rows.size - quickCount) else rows
        fun idx(name: String) = columns.indexOfFirst { norm(it) == norm(name) }
        val iDoc = docIdx ?: -1
        val iDate = idx(s.releaseDateColumn)
        val iCat = idx(s.categoryColumn)
        val iType = idx(s.typeColumn)
        val extra = list.map { q ->
            val r = Array(columns.size) { "" }
            if (iDoc >= 0) r[iDoc] = q.docNo
            if (iDate >= 0) r[iDate] = q.date
            r[linkIdx] = q.link
            if (titleIdx >= 0) r[titleIdx] = q.title
            if (iCat >= 0) r[iCat] = q.category
            if (iType >= 0) r[iType] = q.type
            r
        }
        return TableData(
            columns, base + extra, docIdx, linkIdx, noteIdx, commentIdx, titleIdx,
            origLinks.take(base.size), list.size
        )
    }

    private companion object {
        val LOOKS_LIKE_FILE = Regex(".+\\.[A-Za-z0-9]{2,5}")
        fun lastSegment(p: String) = p.substringAfterLast('\\').substringAfterLast('/')
        fun fileNames(raw: String): List<String> =
            raw.split('\n', ';').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.map {
                val x = if (it.contains('%')) try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } else it
                lastSegment(x)
            }.filter { it.isNotEmpty() }
    }
}

object TableBuilder {
    fun build(raw: RawTable, s: Settings): TableData {
        var names = dedupeColumns(raw.headers.mapIndexed { i, h -> collapse(h).ifEmpty { "Unnamed: $i" } })
        val rows0 = raw.rows.filter { r -> r.any { it.isNotEmpty() } }

        // Bỏ cột trống hoàn toàn, TRỪ các cột nhập liệu (Comment, Note Flag, LINK, Document No)
        val keep = HashSet<String>().apply {
            add(norm(s.noteFlagColumn)); add(norm(s.linkColumn)); add(norm(s.documentColumn))
            s.commentColumns.forEach { add(norm(it)) }
        }
        val keepCols = names.indices.filter { c -> rows0.any { it[c].isNotEmpty() } || norm(names[c]) in keep }
        names = keepCols.map { names[it] }
        var rows: List<Array<String>> = rows0.map { r -> Array(keepCols.size) { r[keepCols[it]] } }

        // Cột LINK: Excel không còn cột này -> thêm vào cuối; nếu vẫn còn thì ghi đè giá trị PC
        val linkNorm = norm(s.linkColumn)
        var linkIdx = names.indexOfFirst { norm(it) == linkNorm }
        val origLinks: List<String>
        if (linkIdx < 0) {
            origLinks = rows.map { "" }
            names = names + s.linkColumn
            rows = rows.map { it + "" }
            linkIdx = names.size - 1
        } else {
            val li = linkIdx
            origLinks = rows.map { it[li] }
            rows.forEach { it[li] = "" }
        }

        // Document No luôn ở cột đầu (cột cố định)
        val docIdx0 = names.indexOfFirst { norm(it) == norm(s.documentColumn) }
        if (docIdx0 > 0) {
            val order = listOf(docIdx0) + names.indices.filter { it != docIdx0 }
            names = order.map { names[it] }
            rows = rows.map { r -> Array(order.size) { r[order[it]] } }
            linkIdx = names.indexOfFirst { norm(it) == linkNorm }
        }

        val lookup = names.withIndex().associate { norm(it.value) to it.index }
        val comments = HashMap<Int, Int>()
        s.commentColumns.forEachIndexed { i, n -> lookup[norm(n)]?.let { comments[i + 1] = it } }
        return TableData(
            names, rows,
            if (docIdx0 >= 0) 0 else null,
            linkIdx,
            lookup[norm(s.noteFlagColumn)],
            comments,
            lookup[norm(s.titleColumn)] ?: -1,
            origLinks
        )
    }
}

// ------------------------------------------------------------------ Dialog
sealed class UiDialog {
    data class Message(
        val title: String, val text: String, val details: String = "", val folder: Uri? = null
    ) : UiDialog()
    data class CommentMenu(val doc: String, val key: Int) : UiDialog()
    data class RowPopup(val positions: List<Int>) : UiDialog()
    data class OverlayHelp(val uri: String, val name: String) : UiDialog()
    data class ConfirmDelete(val positions: List<Int>) : UiDialog()
    class TextInput(
        val title: String, val label: String, val initial: String,
        val onOk: (String) -> Unit, val onCancel: () -> Unit = {}
    ) : UiDialog()
    data class LinkPicker(val doc: String, val key: Int) : UiDialog()
    class Choose(val title: String, val items: List<Pair<String, String>>) : UiDialog()   // (tên, uri)
    data class Progress(val title: String, val text: String, val done: Int, val total: Int) : UiDialog()
    data class ScanChoice(val folderName: String, val fileCount: Int) : UiDialog()
}
