package com.docmanager

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

class Folded(val text: String, val map: IntArray)      // map[i] = vị trí trong chuỗi gốc của ký tự thứ i sau chuẩn hóa

/**
 * Chuẩn hóa văn bản để tìm kiếm không phân biệt hoa/thường và dấu:
 *  "Báo cáo ĐƯỜNG" -> "bao cao duong". Chữ Nhật/Trung/Hàn: mỗi ký tự là một từ riêng.
 */
object PdfText {
    private val latinCache = ConcurrentHashMap<Char, String>()
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val WS = Regex("\\s+")

    private fun isLatinExt(code: Int) = code in 0xC0..0x24F || code in 0x1E00..0x1EFF

    fun isCjk(c: Char): Boolean {
        val x = c.code
        return x in 0x3040..0x30FF || x in 0x3400..0x4DBF || x in 0x4E00..0x9FFF ||
            x in 0xAC00..0xD7AF || x in 0xF900..0xFAFF
    }

    private fun foldLatin(c: Char): String = latinCache.getOrPut(c) {
        if (c == 'đ' || c == 'Đ') {
            "d"
        } else {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            val sb = StringBuilder()
            for (ch in d) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch.lowercaseChar())
            }
            sb.toString()
        }
    }

    fun foldAligned(src: String): Folded {
        val sb = StringBuilder(src.length)
        var map = IntArray(src.length + 16)
        var n = 0
        fun put(ch: Char, idx: Int) {
            if (n == map.size) map = map.copyOf(n * 2)
            map[n++] = idx
            sb.append(ch)
        }
        for (i in src.indices) {
            val c = src[i]
            val code = c.code
            when {
                code < 128 -> put(c.lowercaseChar(), i)
                code in 0x300..0x36F -> {}                       // dấu kết hợp: bỏ
                isLatinExt(code) -> for (ch in foldLatin(c)) put(ch, i)
                else -> put(c.lowercaseChar(), i)
            }
        }
        return Folded(sb.toString(), map.copyOf(n))
    }

    /** Văn bản đã chuẩn hóa dùng để lập chỉ mục / tìm kiếm. */
    fun forIndex(src: String): String {
        val f = foldAligned(src).text
        val sb = StringBuilder(f.length + 16)
        for (c in f) {
            if (isCjk(c)) sb.append(' ').append(c).append(' ') else sb.append(c)
        }
        return sb.toString()
    }

    fun tokens(q: String): List<String> = forIndex(q).split(NON_ALNUM).filter { it.isNotEmpty() }

    /**
     * Biểu thức MATCH của FTS4.
     *  - Mặc định: tất cả các từ đều phải có trong cùng 1 trang (từ ghép như ABC-123 hay chữ Nhật được giữ liền nhau).
     *  - phrase = true: cả cụm từ phải đứng liền nhau đúng thứ tự.
     */
    fun matchExpr(query: String, phrase: Boolean): String? {
        if (phrase) {
            val t = tokens(query)
            return if (t.isEmpty()) null else "\"" + t.joinToString(" ") + "\""
        }
        val parts = query.trim().split(WS).mapNotNull { w ->
            val t = tokens(w)
            when {
                t.isEmpty() -> null
                t.size == 1 -> t[0] + "*"
                else -> "\"" + t.joinToString(" ") + "\""
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    /** Các từ khóa (đã chuẩn hóa) dùng để tô sáng. */
    fun terms(q: String): List<String> = tokens(q).distinct()

    fun highlightRanges(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val f = foldAligned(text)
        if (f.text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        for (t in terms) {
            if (t.isEmpty()) continue
            var from = 0
            while (true) {
                val i = f.text.indexOf(t, from)
                if (i < 0) break
                val s = f.map[i]
                val e = f.map[i + t.length - 1] + 1
                out.add(s until e)
                from = i + t.length
            }
        }
        return out
    }

    fun snippet(text: String, terms: List<String>, before: Int = 70, after: Int = 130): String {
        val ranges = highlightRanges(text, terms)
        val first = ranges.minOfOrNull { it.first } ?: 0
        val s = maxOf(0, first - before)
        val e = minOf(text.length, first + after)
        var sn = text.substring(s, e).replace(WS, " ").trim()
        if (s > 0) sn = "…$sn"
        if (e < text.length) sn = "$sn…"
        return sn
    }
}
