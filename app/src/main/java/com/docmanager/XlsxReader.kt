package com.docmanager

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.floor

class RawTable(val headers: List<String>, val rows: List<List<String>>)

/**
 * Đọc .xlsx/.xlsm bằng ZipFile + XmlPullParser có sẵn của Android.
 * Mọi ô được đổi thành chuỗi gọn giống make_cell_formatter() của bản PC:
 * 12.0 -> "12", ngày -> theo date_format, NaN/None -> "".
 */
object XlsxReader {
    private val BUILTIN_DATE = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
        45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58
    )

    fun read(ctx: Context, uri: Uri, sheetName: String, headerRow: Int, dateFormat: String): RawTable {
        val tmp = File.createTempFile("excel", ".xlsx", ctx.cacheDir)
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            try {
                ZipFile(tmp).use { zip -> return parse(zip, sheetName, headerRow, dateFormat) }
            } catch (e: ZipException) {
                throw IllegalStateException(
                    "Chỉ hỗ trợ file .xlsx / .xlsm. Nếu là .xls, hãy mở bằng Excel và Save As .xlsx."
                )
            }
        } finally {
            tmp.delete()
        }
    }

    private fun newParser(s: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply { setInput(s, null) }

    private fun attr(p: XmlPullParser, name: String): String? = p.getAttributeValue(null, name)

    private fun relId(p: XmlPullParser): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).endsWith(":id")) return p.getAttributeValue(i)
        }
        return null
    }

    private fun parse(zip: ZipFile, sheetName: String, headerRow: Int, dateFormat: String): RawTable {
        // --- tìm sheet
        val wbEntry = zip.getEntry("xl/workbook.xml") ?: throw IllegalStateException("Không phải file .xlsx hợp lệ.")
        val sheets = ArrayList<Pair<String, String>>()
        zip.getInputStream(wbEntry).use { s ->
            val p = newParser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                    sheets.add((attr(p, "name") ?: "") to (relId(p) ?: ""))
                }
                ev = p.next()
            }
        }
        if (sheets.isEmpty()) throw IllegalStateException("File Excel không có sheet nào.")
        val chosen = sheets.firstOrNull { it.first == sheetName } ?: sheets[0]

        val rels = HashMap<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
            zip.getInputStream(e).use { s ->
                val p = newParser(s)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                        rels[attr(p, "Id") ?: ""] = attr(p, "Target") ?: ""
                    }
                    ev = p.next()
                }
            }
        }
        val target = rels[chosen.second] ?: "worksheets/sheet1.xml"
        val sheetPath = if (target.startsWith("/")) target.substring(1) else "xl/$target"
        val sheetEntry = zip.getEntry(sheetPath) ?: throw IllegalStateException("Không đọc được sheet '${chosen.first}'.")

        val shared = parseSharedStrings(zip)
        val dateStyles = parseDateStyles(zip)

        val javaFmt = pyToJavaFormat(dateFormat)
        val fmtDate = DateTimeFormatter.ofPattern(javaFmt)
        val fmtDateTime = DateTimeFormatter.ofPattern("$javaFmt HH:mm")

        // --- đọc ô
        val grid = TreeMap<Int, HashMap<Int, String>>()
        zip.getInputStream(sheetEntry).use { s ->
            val p = newParser(s)
            var rowNum = 0
            var lastCol = -1
            var cur: HashMap<Int, String>? = null
            var colIdx = 0
            var type: String? = null
            var style = -1
            val v = StringBuilder()
            val inline = StringBuilder()
            var inV = false
            var inT = false
            var inRph = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> {
                            rowNum = attr(p, "r")?.toIntOrNull() ?: (rowNum + 1)
                            cur = grid.getOrPut(rowNum) { HashMap() }
                            lastCol = -1
                        }
                        "c" -> {
                            val ref = attr(p, "r")
                            colIdx = if (ref != null) colIndex(ref) else lastCol + 1
                            lastCol = colIdx
                            type = attr(p, "t")
                            style = attr(p, "s")?.toIntOrNull() ?: -1
                            v.setLength(0)
                            inline.setLength(0)
                        }
                        "v" -> inV = true
                        "t" -> inT = true
                        "rPh" -> inRph = true
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v" -> inV = false
                        "t" -> inT = false
                        "rPh" -> inRph = false
                        "c" -> {
                            val value = cellValue(
                                type, v.toString(), inline.toString(), shared,
                                style >= 0 && dateStyles.getOrElse(style) { false }, fmtDate, fmtDateTime
                            )
                            if (value.isNotEmpty()) cur?.put(colIdx, value)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inV) v.append(p.text) else if (inT && !inRph) inline.append(p.text)
                    }
                }
                ev = p.next()
            }
        }

        if (grid.isEmpty()) return RawTable(emptyList(), emptyList())
        val hRow = maxOf(headerRow, 1)
        val header = grid[hRow] ?: HashMap()
        val dataRows = grid.tailMap(hRow, false).values.toList()
        var maxCol = header.keys.maxOrNull() ?: -1
        for (r in dataRows) r.keys.maxOrNull()?.let { if (it > maxCol) maxCol = it }
        val headers = (0..maxCol).map { header[it] ?: "" }
        val rows = dataRows.map { r -> (0..maxCol).map { r[it] ?: "" } }
        return RawTable(headers, rows)
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            n = when (ch) {
                in 'A'..'Z' -> n * 26 + (ch - 'A' + 1)
                in 'a'..'z' -> n * 26 + (ch - 'a' + 1)
                else -> return n - 1
            }
        }
        return n - 1
    }

    private fun cellValue(
        type: String?, v: String, inline: String, shared: List<String>, isDate: Boolean,
        fmtDate: DateTimeFormatter, fmtDateTime: DateTimeFormatter
    ): String {
        val raw: String = when (type) {
            "s" -> shared.getOrNull(v.trim().toIntOrNull() ?: -1) ?: ""
            "inlineStr" -> inline
            "str", "e" -> v
            "b" -> if (v.trim() == "1") "True" else "False"
            "d" -> try {
                LocalDateTime.parse(v.trim()).format(fmtDateTime)
            } catch (_: Exception) {
                try { LocalDate.parse(v.trim()).format(fmtDate) } catch (_: Exception) { v }
            }
            else -> {
                val t = v.trim()
                if (t.isEmpty()) "" else if (isDate) serialToString(t, fmtDate, fmtDateTime) else numberToString(t)
            }
        }
        val s = raw.trim()
        return if (s == "nan" || s == "NaT" || s == "None") "" else s
    }

    private fun numberToString(t: String): String = try {
        BigDecimal(t).stripTrailingZeros().toPlainString()
    } catch (_: Exception) { t }

    private fun serialToString(t: String, fmtDate: DateTimeFormatter, fmtDateTime: DateTimeFormatter): String {
        val d = t.toDoubleOrNull() ?: return t
        val days = floor(d).toLong()
        val secs = Math.round((d - days) * 86400.0)
        val dt = LocalDate.of(1899, 12, 30).plusDays(days).atStartOfDay().plusSeconds(secs)
        if (d < 1.0) return dt.format(DateTimeFormatter.ofPattern("HH:mm"))
        val hasTime = dt.hour != 0 || dt.minute != 0 || dt.second != 0
        return dt.format(if (hasTime) fmtDateTime else fmtDate)
    }

    private fun parseSharedStrings(zip: ZipFile): List<String> {
        val out = ArrayList<String>()
        val e = zip.getEntry("xl/sharedStrings.xml") ?: return out
        zip.getInputStream(e).use { s ->
            val p = newParser(s)
            var sb: StringBuilder? = null
            var inT = false
            var inRph = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> sb = StringBuilder()
                        "t" -> inT = true
                        "rPh" -> inRph = true
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "si" -> { out.add(sb?.toString() ?: ""); sb = null }
                        "t" -> inT = false
                        "rPh" -> inRph = false
                    }
                    XmlPullParser.TEXT -> if (inT && !inRph) sb?.append(p.text)
                }
                ev = p.next()
            }
        }
        return out
    }

    /** Danh sách theo chỉ số style (cellXfs): style đó có phải định dạng ngày/giờ không. */
    private fun parseDateStyles(zip: ZipFile): List<Boolean> {
        val e = zip.getEntry("xl/styles.xml") ?: return emptyList()
        val custom = HashMap<Int, String>()
        val xf = ArrayList<Int>()
        zip.getInputStream(e).use { s ->
            val p = newParser(s)
            var inXfs = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "numFmt" -> {
                            val id = attr(p, "numFmtId")?.toIntOrNull()
                            if (id != null) custom[id] = attr(p, "formatCode") ?: ""
                        }
                        "cellXfs" -> inXfs = true
                        "xf" -> if (inXfs) xf.add(attr(p, "numFmtId")?.toIntOrNull() ?: 0)
                    }
                    XmlPullParser.END_TAG -> if (p.name == "cellXfs") inXfs = false
                }
                ev = p.next()
            }
        }
        return xf.map { isDateFormat(it, custom[it]) }
    }

    private fun isDateFormat(id: Int, code: String?): Boolean {
        if (id in BUILTIN_DATE) return true
        if (code == null) return false
        val c = code.lowercase()
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\\\."), "")
        return !c.contains("general") && Regex("[dmyhs]").containsMatchIn(c)
    }
}
