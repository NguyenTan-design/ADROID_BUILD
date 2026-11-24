package com.docmanager

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract

// ---------------------------------------------------------------- Database
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, 4) {
    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notes (doc_no TEXT PRIMARY KEY, note_flag INTEGER" +
                NOTE_FIELDS.joinToString("") { ", $it TEXT" } + ")"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS files (uri TEXT PRIMARY KEY, name TEXT, path TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden (doc_no TEXT PRIMARY KEY)")
        createQuickNotes(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_docs (doc_no TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden (doc_no TEXT PRIMARY KEY)")
        createQuickNotes(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_docs (doc_no TEXT PRIMARY KEY)")
    }

    fun allDeleted(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT doc_no FROM deleted_docs", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun setDeleted(docs: Collection<String>, deleted: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (d in docs) {
                if (deleted) {
                    val cv = ContentValues()
                    cv.put("doc_no", d)
                    db.insertWithOnConflict("deleted_docs", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                } else {
                    db.delete("deleted_docs", "doc_no=?", arrayOf(d))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteQuickNote(docNo: String) {
        writableDatabase.delete("quick_notes", "doc_no=?", arrayOf(docNo))
    }

    private fun createQuickNotes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS quick_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, doc_no TEXT UNIQUE, " +
                "note_date TEXT, link TEXT, title TEXT, category TEXT, type TEXT, created INTEGER)"
        )
    }

    fun insertQuickNote(q: QuickNote) {
        val cv = ContentValues()
        cv.put("doc_no", q.docNo); cv.put("note_date", q.date); cv.put("link", q.link)
        cv.put("title", q.title); cv.put("category", q.category); cv.put("type", q.type)
        cv.put("created", System.currentTimeMillis())
        writableDatabase.insertOrThrow("quick_notes", null, cv)
    }

    fun allQuickNotes(): List<QuickNote> {
        val out = ArrayList<QuickNote>()
        readableDatabase.rawQuery(
            "SELECT id, doc_no, note_date, link, title, category, type FROM quick_notes ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    QuickNote(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun quickNoteExists(docNo: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM quick_notes WHERE doc_no=?", arrayOf(docNo)).use { it.moveToFirst() }

    fun allHidden(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT doc_no FROM hidden", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun setHidden(docs: Collection<String>, hidden: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (d in docs) {
                if (hidden) {
                    val cv = ContentValues()
                    cv.put("doc_no", d)
                    db.insertWithOnConflict("hidden", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                } else {
                    db.delete("hidden", "doc_no=?", arrayOf(d))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun allNotes(): Map<String, Note> {
        val out = HashMap<String, Note>()
        readableDatabase.rawQuery("SELECT * FROM notes", null).use { c ->
            val iDoc = c.getColumnIndex("doc_no")
            val iFlag = c.getColumnIndex("note_flag")
            while (c.moveToNext()) {
                val cells = (1..4).map { k ->
                    fun g(s: String): String {
                        val i = c.getColumnIndex("c${k}_$s")
                        return if (i >= 0) c.getString(i) ?: "" else ""
                    }
                    Cell(g("link"), g("name"), g("text"))
                }
                out[c.getString(iDoc).trim()] = Note(c.getInt(iFlag) != 0, cells)
            }
        }
        return out
    }

    fun saveNote(doc: String, n: Note) {
        if (doc.isBlank()) return
        val cv = ContentValues()
        cv.put("doc_no", doc.trim())
        cv.put("note_flag", if (n.flag) 1 else 0)
        for (k in 1..4) {
            val c = n.cell(k)
            cv.put("c${k}_link", c.link)
            cv.put("c${k}_name", c.name)
            cv.put("c${k}_text", c.text)
        }
        writableDatabase.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allFiles(): List<ScannedFile> {
        val out = ArrayList<ScannedFile>()
        readableDatabase.rawQuery("SELECT uri, name, path FROM files", null).use { c ->
            while (c.moveToNext()) out.add(ScannedFile(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        return out
    }

    fun replaceFiles(files: List<ScannedFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("files", null, null)
            val st = db.compileStatement("INSERT OR REPLACE INTO files (uri, name, path) VALUES (?,?,?)")
            for (f in files) {
                st.clearBindings()
                st.bindString(1, f.uri); st.bindString(2, f.name); st.bindString(3, f.path)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

// --------------------------------------------------- Storage Access Framework
object Saf {
    class Child(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long)

    fun isDir(mime: String) = mime == DocumentsContract.Document.MIME_TYPE_DIR
    fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)
    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    fun children(ctx: Context, tree: Uri, parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Child>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Child(
                        c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                        if (c.isNull(3)) -1L else c.getLong(3),
                        if (c.isNull(4)) 0L else c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    /** Thông tin 1 file theo document uri. Trả null nếu file không còn tồn tại. */
    fun info(ctx: Context, uri: Uri): Child? = try {
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) Child(
                c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                if (c.isNull(3)) -1L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)
            ) else null
        }
    } catch (_: Exception) { null }

    fun readText(ctx: Context, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }
}

/** Quét đệ quy toàn bộ file trong 1 thư mục (kể cả thư mục con) -> tên + đường link (content://). */
object FileScanner {
    fun scan(
        ctx: Context, tree: Uri, isCancelled: () -> Boolean, onProgress: (Int) -> Unit
    ): List<ScannedFile> {
        val out = ArrayList<ScannedFile>()
        val stack = ArrayDeque<Pair<String, String>>()       // (docId, đường dẫn tương đối)
        stack.addLast(Saf.rootDocId(tree) to "")
        while (stack.isNotEmpty() && !isCancelled()) {
            val (id, path) = stack.removeLast()
            for (ch in Saf.children(ctx, tree, id)) {
                if (Saf.isDir(ch.mime)) {
                    stack.addLast(ch.docId to "$path${ch.name}/")
                } else {
                    out.add(ScannedFile(Saf.docUri(tree, ch.docId).toString(), ch.name, "$path${ch.name}"))
                    if (out.size % 200 == 0) onProgress(out.size)
                }
            }
        }
        onProgress(out.size)
        return out
    }
}
