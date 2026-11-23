package com.docmanager

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Màn hình Advanced search - chạy độc lập, không cần nạp Excel hay quét thư mục ở màn hình chính. */
class AdvancedSearchActivity : ComponentActivity() {
    private val vm: PdfSearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdvancedSearchScreen(vm) { finish() } }
    }
}

@Composable
fun rememberHighlighted(text: String, terms: List<String>): AnnotatedString = remember(text, terms) {
    val ranges = PdfText.highlightRanges(text, terms)
    buildAnnotatedString {
        append(text)
        for (r in ranges) {
            val s = r.first.coerceIn(0, text.length)
            val e = (r.last + 1).coerceIn(s, text.length)
            if (e > s) addStyle(SpanStyle(background = Color(0xFFFFEB3B), fontWeight = FontWeight.Bold), s, e)
        }
    }
}

@Composable
fun AdvancedSearchScreen(vm: PdfSearchViewModel, onBack: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }
    val indexing = vm.progress != null
    DisposableEffect(indexing) {
        val w = activity?.window
        if (indexing) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppButton("◀ Quay lại", Color(0xFF546E7A)) { onBack() }
                    Spacer(Modifier.width(10.dp))
                    Text("🔎 Advanced search (PDF)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(6.dp))
                FolderSection(vm) { treePicker.launch(null) }
                Spacer(Modifier.height(6.dp))
                SearchBar(vm)
                IndexProgressPanel(vm)
                if (vm.resultInfo.isNotEmpty()) {
                    Text(
                        vm.resultInfo, fontSize = 12.sp, color = Color(0xFF37474F),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (vm.results.isEmpty()) {
                        if (vm.resultInfo.isEmpty()) {
                            Text(
                                "1) Bấm ➕ Thêm thư mục để chọn thư mục chứa PDF và lập chỉ mục (làm 1 lần, có thể thêm nhiều thư mục).\n" +
                                    "2) Tích chọn thư mục cần tìm.\n3) Nhập từ khóa rồi bấm 🔍 Tìm.\n\n" +
                                    "Khi file PDF thay đổi, bấm \"Cập nhật\" ở thư mục đó để cập nhật chỉ mục.",
                                color = Color.Gray, fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(vm.results, key = { it.pdfId }) { file -> FileCard(vm, file) }
                        }
                    }
                }
            }
            PdfDialogHost(vm)
        }
    }
}

// ------------------------------------------------------------------- thư mục
@Composable
fun SmallBtn(text: String, enabled: Boolean = true, color: Color = Color(0xFF3F51B5), onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, if (enabled) color else Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    ) { Text(text, fontSize = 11.sp, color = if (enabled) color else Color(0xFFBDBDBD)) }
}

@Composable
fun FolderSection(vm: PdfSearchViewModel, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(true) }
    val busy = vm.progress != null
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (if (open) "▾" else "▸") + " Thư mục đã lập chỉ mục (${vm.selected.size}/${vm.folders.size} đang chọn)",
            fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f)
        )
        if (vm.folders.size > 1) {
            SmallBtn("Chọn tất cả") { vm.selectAllFolders() }
            Spacer(Modifier.width(6.dp))
        }
        AppButton("➕ Thêm thư mục", Color(0xFF7B1FA2), enabled = !busy) { onAdd() }
    }
    if (!open) return
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (vm.folders.isEmpty()) {
            Text("Chưa có thư mục nào.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(4.dp))
        }
        vm.folders.forEach { f ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, Color(0xFFCFD8DC), RoundedCornerShape(6.dp)).padding(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = f.id in vm.selected, onCheckedChange = { vm.toggleFolder(f.id) })
                    Column(Modifier.weight(1f)) {
                        Text("📁 ${f.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (f.lastIndexed == 0L) "Chưa lập chỉ mục"
                            else "${f.pdfCount} PDF · ${f.pageCount} trang · cập nhật ${fmt.format(Date(f.lastIndexed))}",
                            fontSize = 11.sp, color = Color(0xFF666666)
                        )
                    }
                }
                Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallBtn("⟳ Cập nhật", !busy) { vm.startIndex(f, rebuild = false) }
                    SmallBtn("♻ Lập lại", !busy, Color(0xFFFB8C00)) { vm.dialog = PdfDialog.ConfirmRebuild(f) }
                    SmallBtn("🗑 Xóa", !busy, Color(0xFFC62828)) { vm.dialog = PdfDialog.ConfirmDelete(f) }
                }
            }
        }
    }
}

@Composable
fun IndexProgressPanel(vm: PdfSearchViewModel) {
    val p = vm.progress ?: return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFFE3F2FD), RoundedCornerShape(6.dp)).padding(8.dp)
    ) {
        Text("Đang lập chỉ mục: ${p.folderName}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(
            if (p.total > 0) "${p.done}/${p.total} - ${p.text}" else p.text,
            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ vm.cancelIndex() }) { Text("Hủy") }
        }
        Text("Giữ màn hình sáng cho tới khi xong. Có thể bấm Hủy, lần Cập nhật sau sẽ làm tiếp phần còn lại.", fontSize = 10.sp, color = Color(0xFF666666))
    }
}

// ------------------------------------------------------------------ tìm kiếm
@Composable
fun SearchBar(vm: PdfSearchViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CompactField(vm.query, { vm.query = it }, "Nhập từ khóa cần tìm trong PDF...", Modifier.weight(1f))
        AppButton(if (vm.searching) "..." else "🔍 Tìm", Color(0xFF2196F3), enabled = !vm.searching) { vm.search() }
    }
    Spacer(Modifier.height(4.dp))
    val chip = BtnStyle(Color(0xFFE0E0E0), Color.Black, Color(0xFF999999))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterBtn("Tất cả các từ", chip, !vm.phrase) { vm.phrase = false }
        FilterBtn("Cụm từ chính xác", chip, vm.phrase) { vm.phrase = true }
    }
}

@Composable
fun FileCard(vm: PdfSearchViewModel, file: FileHit) {
    var all by remember(file.pdfId) { mutableStateOf(false) }
    val shown = if (all) file.pages else file.pages.take(4)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, Color(0xFFCFD8DC), RoundedCornerShape(6.dp)).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📄 ${file.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0D47A1))
                Text("${file.folder} › ${file.path}", fontSize = 10.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            AppButton("📂 Mở", Color(0xFF2196F3)) { vm.openFile(file.uri, null) }
        }
        Text(
            "Có từ khóa ở ${file.pages.size} trang: " + file.pages.joinToString(", ") { it.page.toString() },
            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFF444444),
            modifier = Modifier.padding(vertical = 4.dp)
        )
        shown.forEach { hit ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openPage(file, hit) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFE082)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("Tr.${hit.page}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
                Text(rememberHighlighted(hit.snippet, vm.lastTerms), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = Color(0xFFECEFF1))
        }
        if (!all && file.pages.size > 4) {
            Text(
                "▸ Hiện tất cả ${file.pages.size} trang", color = Color(0xFF0D47A1), fontSize = 12.sp,
                modifier = Modifier.clickable { all = true }.padding(top = 6.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ hộp thoại
@Composable
fun PdfDialogHost(vm: PdfSearchViewModel) {
    when (val d = vm.dialog) {
        null -> {}
        is PdfDialog.Message -> AlertDialog(
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
            confirmButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } }
        )
        is PdfDialog.AskIndex -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Đã thêm thư mục") },
            text = { Text("Lập chỉ mục cho tất cả file PDF trong \"${d.folder.name}\" ngay bây giờ?\n(Có thể mất vài phút nếu có nhiều file.)") },
            confirmButton = { TextButton({ vm.dialog = null; vm.startIndex(d.folder, rebuild = false) }) { Text("Lập chỉ mục") } },
            dismissButton = { TextButton({ vm.dialog = null }) { Text("Để sau") } }
        )
        is PdfDialog.ConfirmRebuild -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Lập lại chỉ mục?") },
            text = { Text("Xóa toàn bộ chỉ mục của \"${d.folder.name}\" và lập lại từ đầu. Thường chỉ cần bấm \"Cập nhật\" (nhanh hơn nhiều).") },
            confirmButton = { TextButton({ vm.dialog = null; vm.startIndex(d.folder, rebuild = true) }) { Text("Lập lại") } },
            dismissButton = { TextButton({ vm.dialog = null }) { Text("Hủy") } }
        )
        is PdfDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Xóa thư mục khỏi danh sách?") },
            text = { Text("Chỉ xóa chỉ mục của \"${d.folder.name}\" trong app. File PDF trên điện thoại không bị ảnh hưởng.") },
            confirmButton = { TextButton({ vm.dialog = null; vm.deleteFolder(d.folder) }) { Text("Xóa") } },
            dismissButton = { TextButton({ vm.dialog = null }) { Text("Hủy") } }
        )
        is PdfDialog.PageView -> {
            val annotated = rememberHighlighted(d.text, d.terms)
            AlertDialog(
                onDismissRequest = { vm.dialog = null },
                title = { Text("${d.file.name} - Trang ${d.page}", fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        Text(annotated, fontSize = 13.sp)
                    }
                },
                confirmButton = { TextButton({ vm.openFile(d.file.uri, d.page) }) { Text("📂 Mở file") } },
                dismissButton = { TextButton({ vm.dialog = null }) { Text("Đóng") } }
            )
        }
    }
}
