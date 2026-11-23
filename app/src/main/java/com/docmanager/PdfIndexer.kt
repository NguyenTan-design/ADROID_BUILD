package com.docmanager

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileNotFoundException

data class IndexProgress(val folderName: String, val done: Int, val total: Int, val text: String)

object PdfIndexer {
    class Found(
        val docId: String, val uri: String, val name: String, val path: String,
        val size: Long, val modified: Long
    )

    class Result {
        var indexed = 0
        var unchanged = 0
        var removed = 0
        var pages = 0
        var noText = 0
        var cancelled = false
        val errors = ArrayList<String>()
    }

    /** Liệt kê đệ quy toàn bộ file .pdf trong thư mục (kể cả thư mục con). */
    fun listPdfs(ctx: Context, tree: Uri, isCancelled: () -> Boolean, onCount: (Int) -> Unit): List<Found> {
        val out = ArrayList<Found>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(Saf.rootDocId(tree) to "")
        while (stack.isNotEmpty() && !isCancelled()) {
            val (id, path) = stack.removeLast()
            for (ch in Saf.children(ctx, tree, id)) {
                if (Saf.isDir(ch.mime)) {
                    stack.addLast(ch.docId to "$path${ch.name}/")
                } else if (ch.name.endsWith(".pdf", ignoreCase = true) || ch.mime == "application/pdf") {
                    out.add(Found(ch.docId, Saf.docUri(tree, ch.docId).toString(), ch.name, "$path${ch.name}", ch.size, ch.modified))
                    if (out.size % 100 == 0) onCount(out.size)
                }
            }
        }
        return out
    }

    /**
     * Cập nhật chỉ mục của 1 thư mục.
     *  - rebuild = false: chỉ xử lý file MỚI / ĐÃ SỬA / từng bị lỗi, và xóa chỉ mục của file đã bị xóa.
     *  - rebuild = true : xóa toàn bộ chỉ mục của thư mục rồi lập lại từ đầu.
     * Mỗi file được ghi riêng 1 giao dịch nên nếu bị ngắt giữa chừng, lần cập nhật sau sẽ làm tiếp.
     */
    fun run(
        ctx: Context, db: PdfDb, folder: FolderRow, rebuild: Boolean,
        isCancelled: () -> Boolean, onProgress: (IndexProgress) -> Unit
    ): Result {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        System.setProperty("java.io.tmpdir", ctx.cacheDir.absolutePath)

        val res = Result()
        val tree = Uri.parse(folder.uri)
        onProgress(IndexProgress(folder.name, 0, 0, "Đang liệt kê file PDF..."))
        val found = listPdfs(ctx, tree, isCancelled) { n ->
            onProgress(IndexProgress(folder.name, 0, 0, "Đang liệt kê file PDF... ($n)"))
        }
        if (isCancelled()) { res.cancelled = true; return res }

        if (rebuild) db.deleteFolderContents(folder.id)
        val existing = db.pdfsOf(folder.id)
        val foundIds = found.map { it.docId }.toHashSet()
        for ((docId, row) in existing) {
            if (docId !in foundIds) { db.deletePdf(row.id); res.removed++ }
        }
        val todo = found.filter { f ->
            val e = existing[f.docId]
            e == null || e.status == 2 || e.size != f.size || e.modified != f.modified
        }
        res.unchanged = found.size - todo.size

        for ((i, f) in todo.withIndex()) {
            if (isCancelled()) { res.cancelled = true; break }
            onProgress(IndexProgress(folder.name, i, todo.size, f.name))
            indexOne(ctx, db, folder.id, f, isCancelled, res)
        }
        db.updateFolderStats(folder.id)
        return res
    }

    private fun indexOne(
        ctx: Context, db: PdfDb, folderId: Long, f: Found, isCancelled: () -> Boolean, res: Result
    ) {
        val tmp = File.createTempFile("pdf", ".pdf", ctx.cacheDir)
        try {
            val ins = ctx.contentResolver.openInputStream(Uri.parse(f.uri)) ?: throw FileNotFoundException("không mở được file")
            ins.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }

            val texts = ArrayList<Pair<Int, String>>()
            var total = 0
            PDDocument.load(tmp, MemoryUsageSetting.setupMixed(24L * 1024 * 1024)).use { doc ->
                total = doc.numberOfPages
                val stripper = PDFTextStripper()
                for (p in 1..total) {
                    if (isCancelled()) return
                    stripper.setStartPage(p)
                    stripper.setEndPage(p)
                    val t = stripper.getText(doc).trim()
                    if (t.isNotEmpty()) texts.add(p to (if (t.length > 60000) t.substring(0, 60000) else t))
                }
            }
            val status = if (texts.isEmpty()) 1 else 0
            db.insertPdf(folderId, f.docId, f.uri, f.name, f.path, f.size, f.modified, total, status, texts)
            res.indexed++
            res.pages += texts.size
            if (status == 1) res.noText++
        } catch (e: Throwable) {
            res.errors.add("${f.name}: ${e.message ?: e.javaClass.simpleName}")
            try {
                db.insertPdf(folderId, f.docId, f.uri, f.name, f.path, f.size, f.modified, 0, 2, emptyList())
            } catch (_: Throwable) {
            }
        } finally {
            tmp.delete()
        }
    }
}
