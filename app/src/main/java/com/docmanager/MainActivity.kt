@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.docmanager

import android.content.Intent
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

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
                PageBar(vm)
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
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val n = vm.selected.size
    val loaded = vm.table != null
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppButton("📂 Load File", Color(0xFF2196F3)) { vm.pickerRequest = Picker.EXCEL }
        AppButton("📁 Quét thư mục", Color(0xFF7B1FA2)) { vm.onScanClicked() }
        AppButton("🔎 Tìm nâng cao", Color(0xFF00897B)) {
            context.startActivity(Intent(context, AdvancedSearchActivity::class.java))
        }
        AppButton("🔄 Refresh", Color(0xFF4CAF50), enabled = loaded) { vm.loadData() }
        AppButton(
            "⚙ Reload Config", Color(0xFFE0E0E0), Color.Black,
            onLongClick = { vm.pickConfigFolder() }
        ) { vm.onReloadConfigClicked() }
        Box {
            AppButton("👁 Ẩn/Hiện cột", Color(0xFFE0E0E0), Color.Black, enabled = loaded) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("✔ Hiện tất cả cột") }, onClick = { vm.showAllColumns() })
                DropdownMenuItem(text = { Text("↔ Đặt lại độ rộng cột") }, onClick = { vm.resetWidths(); menu = false })
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
        Modifier.defaultMinSize(minHeight = 26.dp).clip(shape).background(style.bg)
            .border(if (checked) 2.dp else 1.dp, if (checked) Color(0xFFE53935) else style.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = style.fg, fontSize = 11.sp, maxLines = 1,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun FilterPanel(vm: MainViewModel) {
    val open = vm.filterPanelOpen
    Row(
        Modifier.fillMaxWidth().clickable { vm.toggleFilterPanel() }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (open) "▾ Bộ lọc" else "▸ Bộ lọc", fontSize = 12.sp,
            fontWeight = FontWeight.Bold, color = Color(0xFF444444)
        )
        val act = vm.activeFilters.values.joinToString(", ") { it.label }
        if (act.isNotEmpty()) {
            Text("  ($act)", fontSize = 11.sp, color = Color(0xFFE53935), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (!open) return
    val types = vm.filterButtons.filter { it.group == "type" }
    val cats = vm.filterButtons.filter { it.group == "category" }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FilterBtn("Clear", vm.config.clearStyle, false) { vm.resetAll() }
            vm.filterButtons.firstOrNull { it.group == "hide" }?.let { b ->
                FilterBtn("HIDE (${vm.hiddenDocs.size})", b.style, vm.activeFilters[b.group]?.id == b.id) { vm.onFilterClicked(b) }
            }
            types.forEach { b ->
                FilterBtn(b.label, b.style, vm.activeFilters[b.group]?.id == b.id) { vm.onFilterClicked(b) }
            }
        }
        if (cats.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                cats.forEach { b ->
                    FilterBtn(b.label, b.style, vm.activeFilters[b.group]?.id == b.id) { vm.onFilterClicked(b) }
                }
            }
        }
    }
    Spacer(Modifier.height(3.dp))
}

// ------------------------------------------------------------- chuyển trang
@Composable
fun PageBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (enabled) Color(0xFF3F51B5) else Color(0xFFBDBDBD))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
fun PageBar(vm: MainViewModel) {
    if (vm.table == null) return
    val total = vm.order.size
    val pages = vm.pageCount
    val from = if (total == 0) 0 else vm.page * PAGE_SIZE + 1
    val to = minOf(total, (vm.page + 1) * PAGE_SIZE)
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PageBtn("⏮", vm.page > 0) { vm.goPage(0) }
        PageBtn("◀", vm.page > 0) { vm.goPage(vm.page - 1) }
        Column(
            Modifier.clickable { vm.askJumpPage() }.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Trang ${vm.page + 1}/$pages", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("$from–$to / $total", fontSize = 11.sp, color = Color(0xFF666666))
        }
        PageBtn("▶", vm.page < pages - 1) { vm.goPage(vm.page + 1) }
        PageBtn("⏭", vm.page < pages - 1) { vm.goPage(pages - 1) }
    }
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
    val widths = vm.colWidths
    val hidden = vm.hiddenCols
    val visible = remember(t, hidden) { t.columns.indices.filter { it == 0 || t.columns[it] !in hidden } }
    val scrollCols = remember(visible) { visible.drop(1) }
    val density = LocalDensity.current
    val widthsPx = remember(widths, scrollCols, density) {
        FloatArray(scrollCols.size) { with(density) { widths.getOrElse(scrollCols[it]) { 120 }.dp.toPx() } }
    }
    val totalPx = remember(widthsPx) { widthsPx.sum() }

    // Cuộn ngang: chỉ 1 giá trị offset dùng chung, mỗi dòng chỉ dịch lớp đồ họa (không đo/xếp lại từng ô)
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val maxScroll = (totalPx - viewportPx).coerceAtLeast(0f)
    LaunchedEffect(maxScroll) { if (offset > maxScroll) offset = maxScroll }
    val scrollState = rememberScrollableState { delta ->
        val cur = offset
        val next = (cur - delta).coerceIn(0f, maxScroll)
        offset = next
        cur - next
    }
    val offsetProvider = remember { { offset } }

    Column(modifier.scrollable(scrollState, Orientation.Horizontal)) {
        Row(Modifier.fillMaxWidth().height(34.dp).background(Color(0xFFE8EAF6))) {
            HeaderCell(vm, t, 0, widths.getOrElse(0) { 120 }.dp)
            Box(
                Modifier.weight(1f).fillMaxHeight().clipToBounds()
                    .onSizeChanged { viewportPx = it.width.toFloat() }
            ) {
                Row(
                    Modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight()
                        .graphicsLayer { translationX = -offsetProvider() }
                ) {
                    scrollCols.forEach { c -> HeaderCell(vm, t, c, widths.getOrElse(c) { 120 }.dp) }
                }
            }
        }
        val listState = rememberLazyListState()
        val items = vm.pageItems
        // Lần mở app đầu tiên: cuộn về đúng vị trí đã đóng; các lần lọc/chuyển trang sau: về đầu trang
        LaunchedEffect(vm.page, vm.order) {
            if (vm.scrollRestorePending) {
                if (items.isNotEmpty()) {
                    vm.scrollRestorePending = false
                    listState.scrollToItem(vm.restoreIndex.coerceIn(0, items.size - 1))
                    offset = vm.restoreH
                }
            } else {
                listState.scrollToItem(0)
            }
        }
        // Ghi nhớ vị trí cuộn (dọc + ngang) sau khi ngừng cuộn 0,5 giây
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to offset }.collectLatest { (i, h) ->
                delay(500)
                vm.saveScroll(i, h)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(items, key = { _, pos -> pos }, contentType = { _, _ -> 0 }) { index, pos ->
                if (pos < t.rows.size) DataRow(vm, t, pos, index, scrollCols, widths, widthsPx, offsetProvider)
            }
        }
    }
}

@Composable
fun HeaderCell(vm: MainViewModel, t: TableData, c: Int, width: Dp) {
    val isRelease = c == vm.releaseIdx
    val arrow = if (vm.sortCol == c) (if (vm.sortAsc) " ▲" else " ▼") else ""
    Row(
        Modifier.width(width).fillMaxHeight().background(Color(0xFFE8EAF6)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .then(if (isRelease) Modifier.clickable { vm.onHeaderClicked(c) } else Modifier)
                .padding(start = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                t.columns[c] + arrow, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            if (c != 0) {
                Text(
                    "✕", color = Color(0xFFC62828), fontSize = 11.sp,
                    modifier = Modifier.clickable { vm.setColumnVisible(t.columns[c], false) }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
        }
        // Tay kéo ở mép phải: kéo sang trái để co nhỏ cột, sang phải để giãn
        Box(
            Modifier.width(18.dp).fillMaxHeight().pointerInput(c) {
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = vm.colWidths.getOrElse(c) { 100 }.toFloat() }
                ) { change, dragAmount ->
                    change.consume()
                    acc += dragAmount.toDp().value
                    vm.setColWidth(c, acc.roundToInt())
                }
            },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight(0.65f).background(Color(0xFF7986CB)))
        }
    }
}

private fun colAt(w: FloatArray, x: Float): Int? {
    if (x < 0f) return null
    var acc = 0f
    for (i in w.indices) {
        acc += w[i]
        if (x < acc) return i
    }
    return null
}

private val GRID = Color(0xFFE0E0E0)

@Composable
fun CellContent(vm: MainViewModel, t: TableData, c: Int, row: Array<String>, doc: String, note: Note?) {
    val key = t.commentByCol[c]
    when {
        c == t.docIdx -> Text(
            row[c], color = Color(0xFF0563C1), textDecoration = TextDecoration.Underline,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        c == t.noteIdx -> if (doc.isNotBlank()) {
            Checkbox(checked = note?.flag == true, onCheckedChange = { vm.setFlag(doc, it) })
        }
        key != null -> {
            val cl = note?.cell(key) ?: Cell()
            val hasLink = cl.link.isNotEmpty()
            Box(
                Modifier.fillMaxSize().background(if (hasLink) Color(0xFFE3F2FD) else Color.Transparent),
                contentAlignment = Alignment.CenterStart
            ) {
                when {
                    hasLink -> Text(
                        "🔗 ${cl.name.ifEmpty { "Mở link" }}", color = Color(0xFF0D47A1),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    cl.text.isNotEmpty() -> Text(cl.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> Text("...", color = Color(0xFF888888), fontSize = 12.sp)
                }
            }
        }
        else -> {
            val raw = row[c]
            val shown = if (c == t.linkIdx) {
                if (raw.isEmpty()) "" else {
                    val parts = raw.split('\n')
                    "📎 " + (vm.fileIndex.nameByUri[parts[0]] ?: "file") +
                        (if (parts.size > 1) " (+${parts.size - 1})" else "")
                }
            } else raw
            Text(
                shown, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (c == t.linkIdx) Color(0xFF455A64) else Color.Unspecified
            )
        }
    }
}

@Composable
fun DataRow(
    vm: MainViewModel, t: TableData, pos: Int, index: Int, scrollCols: List<Int>,
    widths: List<Int>, widthsPx: FloatArray, offsetProvider: () -> Float
) {
    val row = t.rows[pos]
    val doc = t.doc(pos)
    val note = vm.notes[doc]
    val isSel = pos in vm.selected
    val flagged = note?.flag == true
    val isHidden = doc.isNotEmpty() && doc in vm.hiddenDocs
    val bg = when {
        isSel -> Color(0xFFBBDEFB)
        flagged -> Color(0xFFFFCDD2)
        isHidden -> Color(0xFFD7CCC8)
        index % 2 == 1 -> Color(0xFFF6F6F6)
        else -> Color.White
    }
    val frozenW = widths.getOrElse(0) { 120 }.dp

    Row(Modifier.fillMaxWidth().height(36.dp).background(bg)) {
        // Cột cố định (Document No)
        Box(
            Modifier.width(frozenW).fillMaxHeight()
                .drawBehind {
                    drawLine(GRID, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
                    drawLine(GRID, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }
                .pointerInput(pos) {
                    detectTapGestures(
                        onTap = { vm.onCellClick(pos, 0) },
                        onLongPress = { vm.onCellLongClick(pos, 0) }
                    )
                }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) { CellContent(vm, t, 0, row, doc, note) }

        // Phần cuộn ngang: 1 bộ nhận cú chạm cho cả dòng, tìm cột theo vị trí chạm
        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .pointerInput(pos, widthsPx, scrollCols) {
                    detectTapGestures(
                        onTap = { p -> colAt(widthsPx, p.x + offsetProvider())?.let { vm.onCellClick(pos, scrollCols[it]) } },
                        onLongPress = { p -> colAt(widthsPx, p.x + offsetProvider())?.let { vm.onCellLongClick(pos, scrollCols[it]) } }
                    )
                }
        ) {
            Row(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight()
                    .graphicsLayer { translationX = -offsetProvider() }
                    .drawBehind {
                        var x = 0f
                        for (w in widthsPx) {
                            x += w
                            drawLine(GRID, Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                        drawLine(GRID, Offset(0f, size.height), Offset(x, size.height), 1f)
                    }
            ) {
                scrollCols.forEach { c ->
                    Box(
                        Modifier.width(widths.getOrElse(c) { 120 }.dp).fillMaxHeight().padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart
                    ) { CellContent(vm, t, c, row, doc, note) }
                }
            }
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
        is UiDialog.RowPopup -> RowPopupDialog(vm, d)
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
        }.take(200)
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

@Composable
fun RowPopupDialog(vm: MainViewModel, d: UiDialog.RowPopup) {
    val t = vm.table ?: return
    val positions = d.positions.filter { it < t.rows.size }
    if (positions.isEmpty()) return

    if (positions.size == 1) {
        val pos = positions[0]
        val doc = t.doc(pos)
        val title = if (t.titleIdx >= 0) t.rows[pos][t.titleIdx] else ""
        val link = t.rows[pos][t.linkIdx]
        val names = link.split('\n').filter { it.isNotBlank() }.map { vm.fileIndex.nameByUri[it] ?: "file" }
        val isHidden = doc in vm.hiddenDocs
        AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(doc.ifEmpty { "(không có Document No)" }, fontSize = 15.sp, color = Color(0xFF0563C1)) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(title.ifEmpty { "(Title trống)" }, fontSize = 15.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (names.isEmpty()) "Chưa có file trên điện thoại (hãy bấm 📁 Quét thư mục)."
                        else "📎 " + names.joinToString("\n📎 "),
                        fontSize = 12.sp, color = Color(0xFF555555)
                    )
                    if (isHidden) {
                        Spacer(Modifier.height(6.dp))
                        Text("Dòng này đang được Hide.", fontSize = 12.sp, color = Color(0xFF8D6E63))
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { vm.dialog = null; vm.openTarget(link) },
                        enabled = names.isNotEmpty()
                    ) { Text("📂 Mở link") }
                    if (isHidden) TextButton({ vm.dialog = null; vm.unhideRows(positions) }) { Text("👁 Unhide") }
                    else TextButton({ vm.dialog = null; vm.hideRows(positions) }) { Text("🙈 Hide") }
                }
            },
            dismissButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } }
        )
    } else {
        val toHide = positions.filter { t.doc(it).isNotBlank() && t.doc(it) !in vm.hiddenDocs }
        val toUnhide = positions.filter { t.doc(it).isNotBlank() && t.doc(it) in vm.hiddenDocs }
        AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Đã chọn ${positions.size} dòng", fontSize = 16.sp) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        positions.take(12).joinToString("\n") { "• " + t.doc(it) } +
                            (if (positions.size > 12) "\n… (+${positions.size - 12} dòng nữa)" else ""),
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                Row {
                    if (toHide.isNotEmpty()) TextButton({ vm.dialog = null; vm.hideRows(toHide) }) { Text("🙈 Hide (${toHide.size})") }
                    if (toUnhide.isNotEmpty()) TextButton({ vm.dialog = null; vm.unhideRows(toUnhide) }) { Text("👁 Unhide (${toUnhide.size})") }
                }
            },
            dismissButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } }
        )
    }
}
