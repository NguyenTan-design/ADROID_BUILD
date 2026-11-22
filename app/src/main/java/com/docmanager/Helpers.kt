package com.docmanager

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WS = Regex("\\s+")

fun collapse(s: String): String {
    val sb = StringBuilder(s.length)
    var space = false
    for (ch in s) {
        if (ch.isWhitespace()) {
            space = sb.isNotEmpty()
        } else {
            if (space) { sb.append(' '); space = false }
            sb.append(ch)
        }
    }
    return sb.toString()
}

/** Tương đương norm() trong bản PC: gộp khoảng trắng, chữ thường, bỏ dấu ';' ở hai đầu. */
fun norm(text: String?): String =
    collapse(text ?: "").lowercase(Locale.ROOT).trim(';').trim()

private val KEY_NAMES = setOf("values", "value", "type", "types", "items", "data")

fun flattenValues(data: Any?): List<String> = when (data) {
    is String -> {
        val c = collapse(data).trim(';').trim()
        if (c.isEmpty()) emptyList() else listOf(c)
    }
    is Int, is Long, is Double -> listOf(data.toString())
    is JSONArray -> (0 until data.length()).flatMap { flattenValues(data.get(it)) }
    is JSONObject -> {
        val keys = data.keys().asSequence().toList()
        val special = keys.firstOrNull { norm(it) in KEY_NAMES }
        if (special != null) flattenValues(data.get(special))
        else keys.flatMap { flattenValues(data.get(it)) }
    }
    else -> emptyList()
}

fun uniqueClean(values: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (v in values) {
        val c = collapse(v)
        if (c.isNotEmpty() && seen.add(norm(c))) out.add(c)
    }
    return out
}

fun dedupeColumns(cols: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return cols.map { c ->
        val n = seen[c]
        if (n != null) {
            seen[c] = n + 1
            "$c.${n + 1}"
        } else {
            seen[c] = 0
            c
        }
    }
}

/** Đổi định dạng ngày kiểu Python (%d/%m/%Y) sang pattern của Java. */
fun pyToJavaFormat(py: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < py.length) {
        val c = py[i]
        if (c == '%' && i + 1 < py.length) {
            sb.append(
                when (py[i + 1]) {
                    'd' -> "dd"; 'm' -> "MM"; 'Y' -> "yyyy"; 'y' -> "yy"
                    'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"; '%' -> "%"
                    else -> ""
                }
            )
            i += 2
        } else {
            when {
                c == '\'' -> sb.append("''")
                c.isLetter() -> sb.append("'").append(c).append("'")
                else -> sb.append(c)
            }
            i++
        }
    }
    return sb.toString()
}

private val DATE_TIME_FORMATS = listOf("d/M/yyyy H:mm", "yyyy-M-d H:mm").map { DateTimeFormatter.ofPattern(it) }
private val DATE_FORMATS = listOf("d/M/yyyy", "yyyy-M-d", "d-M-yyyy", "d.M.yyyy").map { DateTimeFormatter.ofPattern(it) }

/** Khóa sắp xếp theo ngày (dayfirst như bản PC). Trả null nếu không đọc được. */
private fun fastDate(t: String): Long? {
    val sp = t.indexOf(' ')
    val datePart = if (sp < 0) t else t.substring(0, sp)
    val timePart = if (sp < 0) "" else t.substring(sp + 1).trim()
    var sep = ' '
    for (ch in datePart) if (ch == '/' || ch == '-' || ch == '.') { sep = ch; break }
    if (sep == ' ') return null
    val p = datePart.split(sep)
    if (p.size != 3) return null
    val a = p[0].toIntOrNull() ?: return null
    val b = p[1].toIntOrNull() ?: return null
    val c = p[2].toIntOrNull() ?: return null
    var y = if (p[0].length == 4) a else c
    val d = if (p[0].length == 4) c else a
    if (y < 100) y += 2000
    var secs = 0L
    if (timePart.isNotEmpty()) {
        val tp = timePart.split(':')
        val hh = tp[0].toIntOrNull() ?: return null
        secs = hh * 3600L + (tp.getOrNull(1)?.toIntOrNull() ?: 0) * 60L + (tp.getOrNull(2)?.toIntOrNull() ?: 0)
    }
    return try { LocalDate.of(y, b, d).toEpochDay() * 86400L + secs } catch (_: Exception) { null }
}

fun parseDateKey(s: String): Long? {
    val t = s.trim()
    if (t.isEmpty()) return null
    fastDate(t)?.let { return it }
    for (f in DATE_TIME_FORMATS) {
        try { return LocalDateTime.parse(t, f).toEpochSecond(ZoneOffset.UTC) } catch (_: Exception) {}
    }
    for (f in DATE_FORMATS) {
        try { return LocalDate.parse(t, f).atStartOfDay().toEpochSecond(ZoneOffset.UTC) } catch (_: Exception) {}
    }
    return null
}
