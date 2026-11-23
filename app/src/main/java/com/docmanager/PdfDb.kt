package com.docmanager

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FolderRow(
    val id: Long, val uri: String, val name: String,
    val lastIndexed: Long, val pdfCount: Int, val pageCount: Int
)

data class PdfRow(val id: Long, val size: Long, val modified: Long, val status: Int)   // status: 0 ok, 1 không có chữ, 2 lỗi

class RawHit(
    val pageId: Long, val page: Int, val pdfId: Long, val name: String,
    val path: String, val uri: String, val folder: String, val text: String
)

/** Cơ sở dữ liệu chỉ mục PDF - tách riêng hoàn toàn với dữ liệu Excel của màn hình chính. */
class PdfDb(ctx: Context) : SQLiteOpenHelper(ctx, "pdf_index.db", null, 1) {

    init { setWriteAheadLoggingEnabled(true) }      // vừa lập chỉ mục vừa tìm được

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT UNIQUE, name TEXT, " +
                "last_indexed INTEGER DEFAULT 0, pdf_count INTEGER DEFAULT 0, page_count INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE pdfs (id INTEGER PRIMARY KEY AUTOINCREMENT, folder_id INTEGER, doc_id TEXT, uri TEXT, " +
                "name TEXT, path TEXT, size INTEGER, modified INTEGER, pages INTEGER, status INTEGER, " +
                "UNIQUE(folder_id, doc_id))"
        )
        db.execSQL("CREATE INDEX idx_pdfs_folder ON pdfs(folder_id)")
        db.execSQL("CREATE TABLE pages (id INTEGER PRIMARY KEY AUTOINCREMENT, pdf_id INTEGER, page INTEGER, text TEXT)")
        db.execSQL("CREATE INDEX idx_pages_pdf ON pages(pdf_id)")
        try {
            db.execSQL("CREATE VIRTUAL TABLE pages_fts USING fts4(norm, tokenize=unicode61)")
        } catch (e: Exception) {
            db.execSQL("CREATE VIRTUAL TABLE pages_fts USING fts4(norm)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ------------------------------------------------------------ thư mục
    fun folders(): List<FolderRow> {
        val out = ArrayList<FolderRow>()
        readableDatabase.rawQuery(
            "SELECT id, uri, name, last_indexed, pdf_count, page_count FROM folders ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(FolderRow(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3), c.getInt(4), c.getInt(5)))
            }
        }
        return out
    }

    /** Trả về (id, true nếu vừa tạo mới). */
    fun addFolder(uri: String, name: String): Pair<Long, Boolean> {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM folders WHERE uri=?", arrayOf(uri)).use { c ->
            if (c.moveToFirst()) return c.getLong(0) to false
        }
        val cv = ContentValues()
        cv.put("uri", uri)
        cv.put("name", name)
        return db.insert("folders", null, cv) to true
    }

    fun updateFolderStats(folderId: Long) {
        writableDatabase.execSQL(
            "UPDATE folders SET last_indexed=?, " +
                "pdf_count=(SELECT COUNT(*) FROM pdfs WHERE folder_id=?), " +
                "page_count=(SELECT COUNT(*) FROM pages pg JOIN pdfs p ON p.id = pg.pdf_id WHERE p.folder_id=?) " +
                "WHERE id=?",
            arrayOf<Any>(System.currentTimeMillis(), folderId, folderId, folderId)
        )
    }

    fun deleteFolderContents(folderId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM pages_fts WHERE docid IN (SELECT pg.id FROM pages pg JOIN pdfs p ON p.id = pg.pdf_id WHERE p.folder_id=?)",
                arrayOf<Any>(folderId)
            )
            db.execSQL(
                "DELETE FROM pages WHERE pdf_id IN (SELECT id FROM pdfs WHERE folder_id=?)", arrayOf<Any>(folderId)
            )
            db.execSQL("DELETE FROM pdfs WHERE folder_id=?", arrayOf<Any>(folderId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteFolder(folderId: Long) {
        deleteFolderContents(folderId)
        writableDatabase.execSQL("DELETE FROM folders WHERE id=?", arrayOf<Any>(folderId))
    }

    // ------------------------------------------------------------ file PDF
    fun pdfsOf(folderId: Long): Map<String, PdfRow> {
        val out = HashMap<String, PdfRow>()
        readableDatabase.rawQuery(
            "SELECT doc_id, id, size, modified, status FROM pdfs WHERE folder_id=?", arrayOf(folderId.toString())
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = PdfRow(c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4))
        }
        return out
    }

    private fun removePdfRows(db: SQLiteDatabase, pdfId: Long) {
        val a = arrayOf<Any>(pdfId)
        db.execSQL("DELETE FROM pages_fts WHERE docid IN (SELECT id FROM pages WHERE pdf_id=?)", a)
        db.execSQL("DELETE FROM pages WHERE pdf_id=?", a)
        db.execSQL("DELETE FROM pdfs WHERE id=?", a)
    }

    fun deletePdf(pdfId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            removePdfRows(db, pdfId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Ghi 1 file PDF + toàn bộ chữ của các trang (trong 1 giao dịch). */
    fun insertPdf(
        folderId: Long, docId: String, uri: String, name: String, path: String,
        size: Long, modified: Long, totalPages: Int, status: Int, pageTexts: List<Pair<Int, String>>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery(
                "SELECT id FROM pdfs WHERE folder_id=? AND doc_id=?", arrayOf(folderId.toString(), docId)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            if (old >= 0) removePdfRows(db, old)

            val cv = ContentValues()
            cv.put("folder_id", folderId); cv.put("doc_id", docId); cv.put("uri", uri)
            cv.put("name", name); cv.put("path", path); cv.put("size", size); cv.put("modified", modified)
            cv.put("pages", totalPages); cv.put("status", status)
            val pdfId = db.insert("pdfs", null, cv)

            val sp = db.compileStatement("INSERT INTO pages(pdf_id, page, text) VALUES (?,?,?)")
            val sf = db.compileStatement("INSERT INTO pages_fts(docid, norm) VALUES (?,?)")
            for ((pg, text) in pageTexts) {
                sp.clearBindings()
                sp.bindLong(1, pdfId); sp.bindLong(2, pg.toLong()); sp.bindString(3, text)
                val pid = sp.executeInsert()
                sf.clearBindings()
                sf.bindLong(1, pid); sf.bindString(2, PdfText.forIndex(text))
                sf.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------ tìm kiếm
    fun search(match: String, folderIds: Collection<Long>, limit: Int): List<RawHit> {
        if (folderIds.isEmpty()) return emptyList()
        val sql = "SELECT pg.id, pg.page, p.id, p.name, p.path, p.uri, f.name, pg.text " +
            "FROM pages_fts JOIN pages pg ON pg.id = pages_fts.docid " +
            "JOIN pdfs p ON p.id = pg.pdf_id JOIN folders f ON f.id = p.folder_id " +
            "WHERE pages_fts MATCH ? AND p.folder_id IN (${folderIds.joinToString(",")}) " +
            "ORDER BY p.name COLLATE NOCASE, pg.page LIMIT $limit"
        val out = ArrayList<RawHit>()
        readableDatabase.rawQuery(sql, arrayOf(match)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    RawHit(
                        c.getLong(0), c.getInt(1), c.getLong(2), c.getString(3) ?: "",
                        c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun pageText(pageId: Long): String =
        readableDatabase.rawQuery("SELECT text FROM pages WHERE id=?", arrayOf(pageId.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }
}
