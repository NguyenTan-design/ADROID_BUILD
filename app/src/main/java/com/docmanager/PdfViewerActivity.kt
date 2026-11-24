@file:OptIn(ExperimentalLayoutApi::class)

package com.docmanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Trình xem PDF của app - chỉ PDF mở từ app mới có nút Quick Note. */
class PdfViewerActivity : ComponentActivity() {
    private val vm: PdfViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra("uri")
        if (uri == null) { finish(); return }
        vm.open(uri, intent.getStringExtra("name") ?: "", intent.getIntExtra("page", 1))
        setContent { PdfViewerScreen(vm, uri) { finish() } }
    }
}

private fun openExternal(ctx: Context, uri: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Không có ứng dụng nào mở được PDF.", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun PdfViewerScreen(vm: PdfViewerViewModel, uri: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    LaunchedEffect(vm.pickerRequest) {
        val p = vm.pickerRequest ?: return@LaunchedEffect
        vm.currentPicker = p
        vm.pickerRequest = null
        treePicker.launch(null)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = vm.startPage)

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF424242)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                ViewerTopBar(vm, listState, onBack) { openExternal(ctx, uri) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        vm.loading -> Column(
                            Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Text("Đang mở PDF...", color = Color.White)
                        }
                        vm.error != null -> Text(
                            vm.error ?: "", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                        else -> PdfContent(vm, listState)
                    }
                    ModeHint(vm, Modifier.align(Alignment.TopCenter))
                    QuickNoteFab(vm, Modifier.align(Alignment.BottomEnd))
                    vm.busy?.let { msg ->
                        Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(Modifier.height(8.dp))
                                Text(msg, color = Color.White)
                            }
                        }
                    }
                }
            }
            ViewerDialogs(vm)
        }
    }
}

// ----------------------------------------------------------------- thanh trên
@Composable
fun BarBtn(text: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFF3F51B5) else Color(0xFF455A64))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun ViewerTopBar(vm: PdfViewerViewModel, listState: LazyListState, onBack: () -> Unit, onExternal: () -> Unit) {
    val scope = rememberCoroutineScope()
    val total = vm.aspects.size
    val cur = (listState.firstVisibleItemIndex + 1).coerceAtMost(maxOf(1, total))
    Column(Modifier.fillMaxWidth().background(Color(0xFF263238)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarBtn("◀") { onBack() }
            Text(vm.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            BarBtn("↗ Mở app khác") { onExternal() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarBtn("▲") { scope.launch { listState.animateScrollToItem((cur - 2).coerceAtLeast(0)) } }
            Text("Tr. $cur/$total", color = Color.White, fontSize = 12.sp)
            BarBtn("▼") { scope.launch { listState.animateScrollToItem(cur.coerceAtMost(maxOf(0, total - 1))) } }
            Spacer(Modifier.weight(1f))
            BarBtn("−") { vm.zoom = (vm.zoom - 0.25f).coerceAtLeast(1f) }
            BarBtn("+") { vm.zoom = (vm.zoom + 0.25f).coerceAtMost(3.5f) }
            BarBtn("✎ Chọn chữ", active = vm.mode == ViewerMode.SELECT) { vm.toggleSelectMode() }
        }
    }
}

@Composable
fun ModeHint(vm: PdfViewerViewModel, modifier: Modifier) {
    val text = when {
        vm.mode == ViewerMode.OCR -> "Kéo ngón tay để khoanh vùng cần quét chữ"
        vm.mode == ViewerMode.SELECT && vm.selWords.isEmpty() -> "Kéo ngón tay qua đoạn văn bản để chọn, rồi bấm Quick Note"
        vm.selWords.isNotEmpty() -> "Đã chọn ${vm.selWords.size} từ - bấm Quick Note để lưu"
        else -> return
    }
    Row(
        modifier.padding(8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color.White, fontSize = 12.sp)
        if (vm.mode == ViewerMode.OCR) {
            Text("  ✕ Hủy", color = Color(0xFFFFCC80), fontSize = 12.sp, modifier = Modifier.clickable { vm.cancelOcrMode() })
        } else if (vm.selWords.isNotEmpty()) {
            Text("  ✕", color = Color(0xFFFFCC80), fontSize = 12.sp, modifier = Modifier.clickable { vm.clearSelection() }.padding(horizontal = 4.dp))
        }
    }
}

/** Nút Quick Note nổi, mờ để không che nội dung PDF (đậm hơn khi đã có đoạn chọn). */
@Composable
fun QuickNoteFab(vm: PdfViewerViewModel, modifier: Modifier) {
    Box(
        modifier.padding(16.dp)
            .alpha(if (vm.selWords.isNotEmpty()) 0.8f else 0.38f)
            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF00796B))
            .clickable { vm.onQuickNote() }.padding(horizontal = 14.dp, vertical = 10.dp)
    ) { Text("📝 Quick Note", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

// ------------------------------------------------------------------ nội dung
/** Phóng to/thu nhỏ bằng 2 ngón tay (ngón đơn vẫn cuộn bình thường). */
private fun Modifier.pinchZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size >= 2) {
                val z = event.calculateZoom()
                if (z != 1f) {
                    onZoom(z)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
fun PdfContent(vm: PdfViewerViewModel, listState: LazyListState) {
    BoxWithConstraints(Modifier.fillMaxSize().pinchZoom { z -> vm.zoom = (vm.zoom * z).coerceIn(1f, 3.5f) }) {
        val density = LocalDensity.current
        val viewportPx = with(density) { maxWidth.toPx() }
        val pageWidth = maxWidth * vm.zoom
        LaunchedEffect(vm.zoom) {
            delay(350)
            vm.renderZoom = vm.zoom
        }
        val renderW = (viewportPx * vm.renderZoom).toInt()
        val hScroll = rememberScrollState()
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(
                Modifier.width(pageWidth).fillMaxHeight(), state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(vm.aspects) { index, aspect -> PdfPage(vm, index, aspect, renderW) }
            }
        }
    }
}

private fun nearestWord(words: List<PWord>, x: Float, y: Float): Int {
    var best = -1
    var bestD = Float.MAX_VALUE
    for (i in words.indices) {
        val w = words[i]
        val dx = if (x < w.x0) w.x0 - x else if (x > w.x1) x - w.x1 else 0f
        val dy = if (y < w.y0) w.y0 - y else if (y > w.y1) y - w.y1 else 0f
        val d = dx * dx + dy * dy * 4f
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

private fun normRect(x0: Float, y0: Float, x1: Float, y1: Float, size: IntSize): FloatArray {
    val w = size.width.toFloat().coerceAtLeast(1f)
    val h = size.height.toFloat().coerceAtLeast(1f)
    return floatArrayOf(
        (minOf(x0, x1) / w).coerceIn(0f, 1f), (minOf(y0, y1) / h).coerceIn(0f, 1f),
        (maxOf(x0, x1) / w).coerceIn(0f, 1f), (maxOf(y0, y1) / h).coerceIn(0f, 1f)
    )
}

@Composable
fun PdfPage(vm: PdfViewerViewModel, index: Int, aspect: Float, renderW: Int) {
    val bmp by produceState<Bitmap?>(initialValue = vm.anyCached(index), index, renderW) {
        val b = vm.renderPage(index, renderW)
        if (b != null) value = b
    }
    val words by produceState(emptyList<PWord>(), index, vm.mode) {
        if (vm.mode == ViewerMode.SELECT) value = vm.wordsFor(index)
    }
    val gesture = when (vm.mode) {
        ViewerMode.SELECT -> Modifier.pointerInput(index, words) {
            var startIdx = -1
            detectDragGestures(
                onDragStart = { p ->
                    if (words.isEmpty()) {
                        vm.noTextHint()
                    } else {
                        startIdx = nearestWord(words, p.x / size.width, p.y / size.height)
                        if (startIdx >= 0) vm.setSelection(index, listOf(words[startIdx]))
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    if (startIdx >= 0 && words.isNotEmpty()) {
                        val cur = nearestWord(words, change.position.x / size.width, change.position.y / size.height)
                        if (cur >= 0) vm.setSelection(index, words.subList(minOf(startIdx, cur), maxOf(startIdx, cur) + 1))
                    }
                }
            )
        }
        ViewerMode.OCR -> Modifier.pointerInput(index) {
            var sx = 0f
            var sy = 0f
            detectDragGestures(
                onDragStart = { p ->
                    sx = p.x; sy = p.y
                    vm.setOcrRect(index, normRect(sx, sy, sx, sy, size))
                },
                onDrag = { change, _ ->
                    change.consume()
                    vm.setOcrRect(index, normRect(sx, sy, change.position.x, change.position.y, size))
                },
                onDragEnd = { vm.finishOcr(index) },
                onDragCancel = { vm.cancelOcrMode() }
            )
        }
        else -> Modifier
    }

    Box(Modifier.fillMaxWidth().aspectRatio(1f / aspect).background(Color.White).then(gesture)) {
        val b = bmp
        if (b != null) {
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        } else {
            Text("Đang tải trang ${index + 1}...", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        }
        Canvas(Modifier.fillMaxSize()) {
            if (vm.selPage == index) {
                for (w in vm.selWords) {
                    drawRect(
                        Color(0x663F51B5), topLeft = Offset(w.x0 * size.width, w.y0 * size.height),
                        size = Size((w.x1 - w.x0) * size.width, (w.y1 - w.y0) * size.height)
                    )
                }
            }
            val r = vm.ocrRect
            if (r != null && vm.ocrPage == index) {
                val tl = Offset(r[0] * size.width, r[1] * size.height)
                val sz = Size((r[2] - r[0]) * size.width, (r[3] - r[1]) * size.height)
                drawRect(Color(0x33FF9800), topLeft = tl, size = sz)
                drawRect(Color(0xFFFF9800), topLeft = tl, size = sz, style = Stroke(width = 4f))
            }
        }
    }
}

// ------------------------------------------------------------------ hộp thoại
@Composable
fun ViewerDialogs(vm: PdfViewerViewModel) {
    when (val d = vm.dialog) {
        null -> {}
        is ViewerDialog.Message -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } }
        )
        ViewerDialog.QuickMenu -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Quick Note") },
            text = {
                Column {
                    Text("Chưa có đoạn văn bản nào được chọn. Bạn muốn:", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "✎ Chọn chữ trong văn bản", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3F51B5),
                        modifier = Modifier.fillMaxWidth().clickable { vm.dialog = null; vm.mode = ViewerMode.SELECT }.padding(vertical = 10.dp)
                    )
                    Text(
                        "(bật chế độ chọn, kéo ngón tay qua đoạn cần ghi chú rồi bấm Quick Note)", fontSize = 11.sp, color = Color.Gray
                    )
                    Text(
                        "🔍 Quét & nhận diện chữ (OCR)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00796B),
                        modifier = Modifier.fillMaxWidth().clickable { vm.startOcrMode() }.padding(top = 14.dp, bottom = 6.dp)
                    )
                    Text("(khoanh vùng trên trang, app tự đọc chữ trong vùng đó - dùng được cả với PDF scan)", fontSize = 11.sp, color = Color.Gray)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ vm.dialog = null }) { Text("Hủy") } }
        )
    }
    if (vm.showSave) SaveNoteDialog(vm)
}

@Composable
fun SaveNoteDialog(vm: PdfViewerViewModel) {
    AlertDialog(
        onDismissRequest = { if (!vm.saving) vm.cancelDraft() },
        title = { Text("Lưu Quick Note") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                vm.draftBitmap?.let { b ->
                    val img = remember(b) { b.asImageBitmap() }
                    Image(
                        img, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).border(1.dp, Color(0xFFBDBDBD)),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Nội dung ghi chú (cột Title):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(vm.draftText, { vm.draftText = it }, Modifier.fillMaxWidth(), maxLines = 5)
                Spacer(Modifier.height(8.dp))
                Text("Chọn giá trị cột TYPE:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (vm.types.isEmpty()) {
                    Text(
                        (if (vm.typesMsg.isEmpty()) "Chưa có danh sách TYPE." else vm.typesMsg) +
                            "\nHãy đặt file TYPE.config vào thư mục config rồi bấm Reload Config.",
                        color = Color(0xFFC62828), fontSize = 12.sp
                    )
                    TextButton({ vm.reloadTypes() }) { Text("⚙ Reload Config") }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        vm.types.forEach { t -> FilterBtn(t, TYPE_STYLE, vm.draftType == t) { vm.draftType = t } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ảnh lưu vào: " + (vm.imageDirName ?: "(chưa chọn - sẽ hỏi khi bấm Save)"),
                        fontSize = 12.sp, color = Color(0xFF555555), modifier = Modifier.weight(1f)
                    )
                    TextButton({ vm.changeImageDir() }) { Text("Đổi") }
                }
                Text(
                    "Document No tự đặt, Category = IMAGE NOTE, ngày = hôm nay (MM/DD/YY), LINK = ảnh vừa lưu.",
                    fontSize = 11.sp, color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton({ vm.save() }, enabled = vm.draftType != null && !vm.saving) {
                Text(if (vm.saving) "Đang lưu..." else "Save")
            }
        },
        dismissButton = { TextButton({ vm.cancelDraft() }, enabled = !vm.saving) { Text("Cancel") } }
    )
}
