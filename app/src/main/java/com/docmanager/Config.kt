package com.docmanager

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

data class Settings(
    val headerRow: Int = 3,
    val sheetName: String = "Sheet1",
    val documentColumn: String = "Document No",
    val titleColumn: String = "Title",
    val linkColumn: String = "LINK",
    val typeColumn: String = "TYPE",
    val categoryColumn: String = "Category",
    val releaseDateColumn: String = "Release date",
    val noteFlagColumn: String = "Note Flag",
    val commentColumns: List<String> = listOf("Comment1", "Comment2", "Comment3", "Comment4"),
    val maxColumnWidth: Int = 400,
    val dateFormat: String = "%d/%m/%Y",
    val filterDir: String = ""
)

data class BtnStyle(val bg: Color, val fg: Color, val border: Color, val bold: Boolean = false)

val CLEAR_STYLE = BtnStyle(Color(0xFFE0E0E0), Color.Black, Color(0xFF999999))
val CATEGORY_STYLE = BtnStyle(Color(0xFFF5D77A), Color.Black, Color(0xFFB8860B))
val TYPE_STYLE = BtnStyle(Color.Black, Color.White, Color.Black)     // nút TYPE luôn đen

data class AppConfig(
    val settings: Settings,
    val clearStyle: BtnStyle,
    val categoryStyle: BtnStyle,
    val typeButtons: List<Pair<String, String>>,     // (label, file json)
    val categoryFile: String?
) {
    companion object {
        fun default() = AppConfig(
            Settings(), CLEAR_STYLE, CATEGORY_STYLE,
            listOf(
                "AIMEX3" to "AIMEXIII.json", "Feeder" to "Feeder.json", "Flexa" to "Flexa.json",
                "Fujitrax" to "Fujitrax.json", "GPX" to "GPX.json", "GPX-CII" to "GPX-CII.json",
                "GPX-CL" to "GPX-CL.json", "GPX-CSII" to "GPX-CSII.json", "GPXCS" to "GPX-CS.json",
                "KNOWLEDGE" to "KNOWLEDGE.json", "NXTIII" to "NXTIII.json", "Nexim" to "Nexim.json",
                "NXT II" to "NXTII.json", "NXTR" to "NXTR.json", "Unit" to "Unit.json"
            ),
            "category.json"
        )

        fun parse(j: JSONObject): AppConfig {
            val d = default()
            val st = j.optJSONObject("settings")
            val ds = d.settings
            fun str(k: String, def: String) = st?.opt(k)?.toString()?.takeIf { it.isNotBlank() } ?: def
            fun int(k: String, def: Int) = st?.opt(k)?.toString()?.trim()?.toDoubleOrNull()?.toInt() ?: def
            val cc = st?.optJSONArray("comment_columns")?.let { a -> (0 until a.length()).map { a.get(it).toString() } }
            val settings = Settings(
                headerRow = int("header_row", ds.headerRow),
                sheetName = str("sheet_name", ds.sheetName),
                documentColumn = str("document_column", ds.documentColumn),
                titleColumn = str("title_column", ds.titleColumn),
                linkColumn = str("link_column", ds.linkColumn),
                typeColumn = str("type_column", ds.typeColumn),
                categoryColumn = str("category_column", ds.categoryColumn),
                releaseDateColumn = str("release_date_column", ds.releaseDateColumn),
                noteFlagColumn = str("note_flag_column", ds.noteFlagColumn),
                commentColumns = cc ?: ds.commentColumns,
                maxColumnWidth = int("max_column_width", ds.maxColumnWidth),
                dateFormat = str("date_format", ds.dateFormat),
                filterDir = st?.opt("filter_dir")?.toString() ?: ""
            )
            val styles = j.optJSONObject("styles")
            val buttons = j.optJSONArray("type_buttons")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { o -> o.optString("label", "?") to o.optString("file", "") }
                }
            } ?: d.typeButtons
            return AppConfig(
                settings,
                parseStyle(styles?.optJSONObject("clear"), CLEAR_STYLE),
                parseStyle(styles?.optJSONObject("category"), CATEGORY_STYLE),
                buttons,
                if (j.has("category_file")) j.optString("category_file").ifBlank { null } else d.categoryFile
            )
        }

        private fun parseStyle(o: JSONObject?, def: BtnStyle): BtnStyle {
            if (o == null) return def
            fun col(k: String, d: Color) = try {
                Color(android.graphics.Color.parseColor(o.optString(k)))
            } catch (_: Exception) { d }
            return BtnStyle(col("bg", def.bg), col("fg", def.fg), col("border", def.border), o.optBoolean("bold", def.bold))
        }
    }
}

/** Một nút lọc (TYPE hoặc Category). */
data class FilterButton(
    val id: String,
    val group: String,                         // "type" | "category"
    val label: String,
    val style: BtnStyle,
    val filter: Map<String, List<String>>? = null,
    val special: String? = null                // "note_flag"
)
