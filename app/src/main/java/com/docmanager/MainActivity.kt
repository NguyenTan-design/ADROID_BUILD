@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.docmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen(vm) }
    }
}

// ============================================================ màn hình chính
@Composable
fun MainScreen(vm: MainViewModel) {
    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onExcelPicked(uri)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.onTreePicked(uri)
    }
    LaunchedEffect(vm.pickerRequest) {
        val p = vm.pickerRequest ?: return@LaunchedEffect
        vm.currentPicker = p
        vm.pickerRequest = null
        if (p == Picker.EXCEL) excelPicker.launch(arrayOf("*/*")) else treePicker.launch(null)
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(6.dp)) {
                TopButtons(vm)
                Spacer(Modifier.height(4.dp))
                SearchBoxes(vm)
                Spacer(Modifier.height(4.dp))
                FilterPanel(vm)
                DocTable(vm, Modifier.weight(1f).fillMaxWidth())
                Text(
                    vm.status, fontSize = 11.sp, color = Color(0xFF555555), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                )
            }
            DialogHost(vm)
        }
    }
}

// ----------------------------------------------------------------- nút trên cùng
@Composable
fun AppButton(
    text: String, bg: Color, fg: Color = Color.White, enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit
) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (enabled) bg else Color(0xFFBDBDBD))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (enabled) fg else Color.White, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, maxLines = 1
        )
    }
}

@Composable
fun TopButtons(vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    val n = vm.selected.size
    val loaded = vm.table != null
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppButton("📂 Load File", Color(0xFF2196F3)) { vm.pickerRequest = Picker.EXCEL }
        AppButton("📁 Quét thư mục", Color(0xFF7B1FA2)) { vm.onScanClicked() }
        AppButton("🔄 Refresh", Color(0xFF4CAF50), enabled = loaded) { vm.loadData() }
        AppButton(
            "⚙ Reload Config", Color(0xFFE0E0E0), Color.Black,
            onLongClick = { vm.pickConfigFolder() }
        ) { vm.onReloadConfigClicked() }
        Box {
            AppButton("👁 Ẩn/Hiện cột", Color(0xFFE0E0E0), Color.Black, enabled = loaded) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("✔ Hiện tất cả cột") }, onClick = { vm.showAllColumns() })
                HorizontalDivider()
                vm.table?.columns?.forEachIndexed { i, name ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = name !in vm.hiddenCols, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(name)
                            }
                        },
                        enabled = i != 0,
                        onClick = { vm.setColumnVisible(name, name in vm.hiddenCols) }
                    )
                }
            }
        }
        AppButton("☑ Chọn tất cả", Color(0xFF546E7A), enabled = loaded) { vm.selectAll() }
        AppButton(
            if (n > 0) "📤 Xuất file ($n)" else "📤 Xuất file", Color(0xFFFB8C00), enabled = n > 0
        ) { vm.exportSelected() }
        AppButton("✖ Bỏ chọn", Color(0xFFE0E0E0), Color.Black, enabled = n > 0) { vm.clearSelection() }
    }
}

// -------------------------------------------------------------------- tìm kiếm
@Composable
fun CompactField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = Color.Black),
        cursorBrush = SolidColor(Color(0xFF2196F3)),
        modifier = modifier.height(38.dp),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().border(1.dp, Color(0xFF9E9E9E), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Text("✕", color = Color.Gray, modifier = Modifier.clickable { onChange("") }.padding(4.dp))
                }
            }
        }
    )
}

@Composable
fun SearchBoxes(vm: MainViewModel) {
    val s = vm.config.settings
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactField(vm.txtDoc, vm::setTxtDoc, "🔎 ${s.documentColumn}...", Modifier.weight(2f))
            CompactField(vm.txtTitle, vm::setTxtTitle, "🔎 ${s.titleColumn}...", Modifier.weight(3f))
        }
        CompactField(vm.txtAll, vm::setTxtAll, "🔎 Tìm trong tất cả các cột...", Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- nút lọc
@Composable
fun FilterBtn(label: String, style: BtnStyle, checked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.defaultMinSize(minHeight = 34.dp).clip(shape).background(style.bg)
            .border(if (checked) 3.dp else 1.dp, if (checked) Color(0xFFE53935) else style.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = style.fg, fontSize = 13.sp,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun FilterPanel(vm: MainViewModel) {
    var open by remember { mutableStateOf(true) }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (open) "▾ Bộ lọc" else "▸ Bộ lọc", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF444444))
        val act = vm.activeFilters.values.joinToString(", ") { it.label }
        if (act.isNotEmpty()) Text("  ($act)", fontSize = 12.sp, color = Color(0xFFE53935), maxLines = 1)
    }
    if (!open) return
    val types = vm.filterButtons.filter { it.group == "type" }
    val cats = vm.filterButtons.filter { it.group == "category" }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterBtn("Clear", vm.config.clearStyle, false) { vm.resetAll() }
            types.forEach { b ->
                FilterBtn(b.label, b.style, vm.activeFilters[b.group]?.id == b.id) { vm.onFilterClicked(b) }
            }
        }
        if (cats.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cats.forEach { b ->
                    FilterBtn(b.label, b.style, vm.activeFilters[b.group]?.id == b.id) { vm.onFilterClicked(b) }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

// ---------------------------------------------------------------------- bảng
@Composable
fun DocTable(vm: MainViewModel, modifier: Modifier) {
    val t = vm.table
    if (t == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Chưa có dữ liệu.\n1) Bấm 📁 Quét thư mục để lấy link file trên điện thoại.\n2) Bấm 📂 Load File để chọn file Excel.",
                color = Color.Gray, fontSize = 14.sp
            )
        }
        return
    }
    val hScroll = rememberScrollState()
    val widths = vm.colWidths
    val hidden = vm.hiddenCols
    val visible = remember(t, hidden) { t.columns.indices.filter { it == 0 || t.columns[it] !in hidden } }
    fun w(c: Int): Dp = widths.getOrElse(c) { 120 }.dp

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFFE8EAF6))) {
            HeaderCell(vm, t, 0, w(0))
            Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                visible.drop(1).forEach { c -> HeaderCell(vm, t, c, w(c)) }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(vm.order, key = { _, pos -> pos }) { index, pos ->
                DataRow(vm, t, pos, index, visible, widths, hScroll)
            }
        }
    }
}

@Composable
fun HeaderCell(vm: MainViewModel, t: TableData, c: Int, width: Dp) {
    val isRelease = c == vm.releaseIdx
    val arrow = if (vm.sortCol == c) (if (vm.sortAsc) " ▲" else " ▼") else ""
    Row(
        Modifier.width(width).fillMaxHeight().border(0.5.dp, Color(0xFFBDBDBD))
            .then(if (isRelease) Modifier.clickable { vm.onHeaderClicked(c) } else Modifier)
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            t.columns[c] + arrow, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (c != 0) {
            Text(
                "✕", color = Color(0xFFC62828), fontSize = 12.sp,
                modifier = Modifier.clickable { vm.setColumnVisible(t.columns[c], false) }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun CellBox(
    width: Dp, bg: Color, onClick: () -> Unit, onLongClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        Modifier.width(width).fillMaxHeight().background(bg)
            .border(0.5.dp, Color(0xFFE0E0E0))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}

@Composable
fun DataRow(
    vm: MainViewModel, t: TableData, pos: Int, index: Int, visible: List<Int>,
    widths: List<Int>, hScroll: androidx.compose.foundation.ScrollState
) {
    val row = t.rows[pos]
    val doc = t.doc(pos)
    val note = vm.notes[doc]
    val isSel = pos in vm.selected
    val flagged = note?.flag == true
    val bg = when {
        isSel -> Color(0xFFBBDEFB)
        flagged -> Color(0xFFFFCDD2)
        index % 2 == 1 -> Color(0xFFF6F6F6)
        else -> Color.White
    }

    @Composable
    fun cell(c: Int) {
        val width = widths.getOrElse(c) { 120 }.dp
        val key = t.commentByCol[c]
        val click = { vm.onCellClick(pos, c) }
        val long = { vm.onCellLongClick(pos, c) }
        when {
            c == t.docIdx -> CellBox(width, bg, click, long) {
                Text(
                    row[c], color = Color(0xFF0563C1), textDecoration = TextDecoration.Underline,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            c == t.noteIdx -> CellBox(width, bg, click, long) {
                if (doc.isNotBlank()) Checkbox(checked = flagged, onCheckedChange = { vm.setFlag(doc, it) })
            }
            key != null -> {
                val cl = note?.cell(key) ?: Cell()
                val hasLink = cl.link.isNotEmpty()
                CellBox(width, if (hasLink) Color(0xFFE3F2FD) else bg, click, long) {
                    when {
                        hasLink -> Text(
                            "🔗 ${cl.name.ifEmpty { "Mở link" }}", color = Color(0xFF0D47A1),
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        cl.text.isNotEmpty() -> Text(cl.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else -> Text("...", color = Color(0xFF888888), fontSize = 13.sp)
                    }
                }
            }
            else -> CellBox(width, bg, click, long) {
                Text(
                    row[c].substringBefore('\n').let { if (row[c].contains('\n')) "$it (+${row[c].count { ch -> ch == '\n' }})" else it },
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (c == t.linkIdx) Color(0xFF455A64) else Color.Unspecified
                )
            }
        }
    }

    Row(Modifier.fillMaxWidth().height(40.dp)) {
        cell(0)
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            visible.drop(1).forEach { c -> cell(c) }
        }
    }
}

// ================================================================= hộp thoại
@Composable
fun DialogHost(vm: MainViewModel) {
    when (val d = vm.dialog) {
        null -> {}
        is UiDialog.Message -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(d.title) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(d.text)
                    if (d.details.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(d.details, fontSize = 12.sp, color = Color(0xFF555555))
                    }
                }
            },
            confirmButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } },
            dismissButton = if (d.folder != null) {
                {
                    TextButton({ vm.dialog = null; vm.openFolder(d.folder) }) { Text("Mở thư mục") }
                }
            } else null
        )
        is UiDialog.Progress -> AlertDialog(
            onDismissRequest = {},
            title = { Text(d.title) },
            text = {
                Column {
                    Text(d.text, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    if (d.total > 0) LinearProgressIndicator(progress = { d.done.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton({ vm.cancelWork() }) { Text("Hủy") } }
        )
        is UiDialog.ScanChoice -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Quét thư mục") },
            text = { Text("Thư mục đã chọn trước đó: ${d.folderName}\nĐang có ${d.fileCount} file trong chỉ mục.") },
            confirmButton = { TextButton({ vm.dialog = null; vm.rescanSavedFolder() }) { Text("Quét lại") } },
            dismissButton = {
                Row {
                    TextButton({ vm.dialog = null; vm.pickScanFolder() }) { Text("Chọn thư mục khác") }
                    TextButton({ vm.dialog = null }) { Text("Hủy") }
                }
            }
        )
        is UiDialog.TextInput -> TextInputDialog(vm, d)
        is UiDialog.Choose -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(d.title, fontSize = 16.sp) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(d.items) { _, (name, uri) ->
                        Text(
                            name, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { vm.dialog = null; vm.openUri(uri) }.padding(vertical = 10.dp)
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } }
        )
        is UiDialog.CommentMenu -> CommentMenuDialog(vm, d)
        is UiDialog.LinkPicker -> LinkPickerDialog(vm, d)
    }
}

@Composable
fun TextInputDialog(vm: MainViewModel, d: UiDialog.TextInput) {
    var v by remember(d) { mutableStateOf(d.initial) }
    AlertDialog(
        onDismissRequest = { vm.dialog = null; d.onCancel() },
        title = { Text(d.title) },
        text = {
            Column {
                Text(d.label, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(v, { v = it })
            }
        },
        confirmButton = { TextButton({ vm.dialog = null; d.onOk(v) }) { Text("OK") } },
        dismissButton = { TextButton({ vm.dialog = null; d.onCancel() }) { Text("Hủy") } }
    )
}

@Composable
fun CommentMenuDialog(vm: MainViewModel, d: UiDialog.CommentMenu) {
    val cell = vm.notes[d.doc]?.cell(d.key) ?: Cell()
    val items = ArrayList<Pair<String, () -> Unit>>()
    if (cell.link.isNotEmpty()) {
        items += "🔗 Mở liên kết" to { vm.openTarget(cell.link) }
        items += "✏ Sửa liên kết…" to { vm.openLinkPicker(d.doc, d.key) }
        items += "🏷 Đổi tên nút…" to { vm.renameButton(d.doc, d.key) }
        items += "🗑 Xóa nút" to { vm.clearComment(d.doc, d.key) }
    } else {
        items += "🔗 Thêm nút liên kết…" to { vm.openLinkPicker(d.doc, d.key) }
        items += "✏ Gõ chữ trực tiếp" to { vm.typeComment(d.doc, d.key) }
        if (cell.text.isNotEmpty()) items += "🗑 Xóa chữ" to { vm.clearComment(d.doc, d.key) }
    }
    AlertDialog(
        onDismissRequest = { vm.dialog = null },
        title = { Text(d.doc, fontSize = 16.sp) },
        text = {
            Column {
                items.forEach { (label, action) ->
                    Text(
                        label, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { vm.dialog = null; action() }.padding(vertical = 12.dp)
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } }
    )
}

@Composable
fun LinkPickerDialog(vm: MainViewModel, d: UiDialog.LinkPicker) {
    val t = vm.table ?: return
    var q by remember { mutableStateOf("") }
    val titleIdx = t.columns.indexOfFirst { norm(it) == norm(vm.config.settings.titleColumn) }
    val terms = q.lowercase().split(' ').filter { it.isNotBlank() }
    val items = remember(t, q) {
        t.rows.indices.filter { i ->
            val b = (t.doc(i) + " " + (if (titleIdx >= 0) t.rows[i][titleIdx] else "")).lowercase()
            terms.all { b.contains(it) }
        }
    }
    Dialog(onDismissRequest = { vm.dialog = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(0.96f), shape = RoundedCornerShape(10.dp), color = Color.White) {
            Column(Modifier.padding(10.dp)) {
                Text("Chọn liên kết (LINK)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                CompactField(q, { q = it }, "Tìm theo Document No hoặc Title...", Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(items, key = { _, i -> i }) { _, i ->
                        val link = t.rows[i][t.linkIdx]
                        Column(
                            Modifier.fillMaxWidth().clickable { vm.pickLink(d.doc, d.key, i) }.padding(vertical = 8.dp)
                        ) {
                            Text(t.doc(i), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0563C1))
                            if (titleIdx >= 0) Text(t.rows[i][titleIdx], fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (link.isEmpty()) "(chưa có file)" else link.replace("\n", "  |  "),
                                fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ vm.dialog = null }) { Text("Hủy") }
                }
            }
        }
    }
}
