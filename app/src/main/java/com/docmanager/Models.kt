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
 * Chỉ mục các file đã quét. Ghép theo Document No ↔ tên file:
 *  1) tên file (bỏ đuôi) trùng hẳn Document No  -> ưu tiên
 *  2) Document No xuất hiện trong tên file như một cụm nguyên vẹn (ranh giới là dấu cách/gạch/chấm...)
 * Ví dụ "ABC-123" khớp "ABC-123.pdf", "ABC-123_Rev2.pdf" nhưng KHÔNG khớp "ABC-1234.pdf".
 */
class FileIndex(val files: List<ScannedFile>) {
    private val exact = HashMap<String, MutableList<ScannedFile>>()
    private val windows = HashMap<String, MutableList<ScannedFile>>()
    val nameByUri: Map<String, String> = files.associate { it.uri to it.name }

    init {
        for (f in files) {
            val toks = tokens(stripExt(f.name))
            if (toks.isEmpty()) continue
            exact.getOrPut(toks.joinToString(" ")) { ArrayList() }.add(f)
            val seen = HashSet<String>()
            for (n in 1..minOf(toks.size, MAX_WINDOW)) {
                for (i in 0..toks.size - n) {
                    val k = toks.subList(i, i + n).joinToString(" ")
                    if (seen.add(k)) windows.getOrPut(k) { ArrayList() }.add(f)
                }
            }
        }
    }

    fun match(doc: String): List<ScannedFile> {
        val key = tokens(doc).joinToString(" ")
        if (key.isEmpty()) return emptyList()
        val list = exact[key] ?: windows[key] ?: return emptyList()
        return list.sortedBy { it.path.lowercase() }
    }

    companion object {
        private const val MAX_WINDOW = 12
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        fun tokens(s: String): List<String> = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }
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
    val commentIdx: Map<Int, Int>          // số comment (1..4) -> cột
) {
    val commentByCol: Map<Int, Int> = commentIdx.entries.associate { it.value to it.key }
    private val normCache = HashMap<Int, Array<String>>()

    @Synchronized
    fun normalized(col: Int): Array<String> =
        normCache.getOrPut(col) { Array(rows.size) { norm(rows[it][col]) } }

    val blob: Array<String> by lazy {
        Array(rows.size) { rows[it].joinToString("\n").lowercase() }
    }

    fun doc(pos: Int): String = docIdx?.let { rows[pos][it] } ?: ""

    /** Thay cột LINK bằng đường link thật trên điện thoại (bước 3 của yêu cầu). */
    fun withLinks(index: FileIndex): TableData {
        val d = docIdx
        val newRows = rows.map { r ->
            val c = r.copyOf()
            c[linkIdx] = if (d == null) "" else index.match(r[d]).joinToString("\n") { it.uri }
            c
        }
        return TableData(columns, newRows, docIdx, linkIdx, noteIdx, commentIdx)
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
        if (linkIdx < 0) {
            names = names + s.linkColumn
            rows = rows.map { it + "" }
            linkIdx = names.size - 1
        } else {
            rows.forEach { it[linkIdx] = "" }
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
            comments
        )
    }
}

// ------------------------------------------------------------------ Dialog
sealed class UiDialog {
    data class Message(
        val title: String, val text: String, val details: String = "", val folder: Uri? = null
    ) : UiDialog()
    data class CommentMenu(val doc: String, val key: Int) : UiDialog()
    class TextInput(
        val title: String, val label: String, val initial: String,
        val onOk: (String) -> Unit, val onCancel: () -> Unit = {}
    ) : UiDialog()
    data class LinkPicker(val doc: String, val key: Int) : UiDialog()
    class Choose(val title: String, val items: List<Pair<String, String>>) : UiDialog()   // (tên, uri)
    data class Progress(val title: String, val text: String, val done: Int, val total: Int) : UiDialog()
    data class ScanChoice(val folderName: String, val fileCount: Int) : UiDialog()
}
