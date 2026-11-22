# Document Management – bản Android

Chuyển từ `main.py` (PyQt6) sang Kotlin + Jetpack Compose. Không cần thư viện ngoài (tự đọc .xlsx bằng ZipFile + XmlPullParser của Android).

## Build
1. Cài Android Studio (Koala trở lên), chọn **Open** thư mục `DocManager`.
2. Chờ Gradle sync xong → cắm điện thoại (bật USB debugging) → bấm **Run ▶**.
   Hoặc **Build > Build APK(s)** rồi copy file `app-debug.apk` vào điện thoại để cài.

## Cách dùng (luồng mới)
1. **📁 Quét thư mục** → chọn thư mục chứa tài liệu. App quét đệ quy toàn bộ file (cả thư mục con), lưu tên + link (content://) vào bộ nhớ app.
2. **📂 Load File** → chọn file Excel (.xlsx/.xlsm), KHÔNG cần cột LINK.
3. App tự ghép: Document No ↔ tên file, rồi điền cột **LINK** (link thật trên điện thoại). Quét lại thư mục hoặc Refresh thì ghép lại tự động.

Quy tắc ghép: tên file (bỏ đuôi) trùng Document No thì ưu tiên; nếu không có thì Document No phải xuất hiện nguyên cụm trong tên file
(`ABC-123` khớp `ABC-123.pdf`, `ABC-123_Rev2.pdf`; không khớp `ABC-1234.pdf`). Không phân biệt hoa/thường, dấu `- _ . khoảng trắng` coi như nhau.
Nhiều file khớp → cột LINK chứa nhiều link, chạm vào Document No sẽ hiện danh sách để chọn.

## Tương ứng với bản PC
| PC | Android |
|---|---|
| Click Document No mở file | Chạm Document No |
| Shift/Ctrl+click chọn dòng | Nhấn giữ 1 dòng để chọn, chạm để chọn thêm; có nút ☑ Chọn tất cả |
| Click đúp / chuột phải ô Comment | Chạm (hoặc nhấn giữ) ô Comment → menu |
| Click header "Release date" sắp xếp | Chạm header (giảm → tăng → bỏ) |
| Nút ✕ ẩn cột | ✕ trên header + menu 👁 Ẩn/Hiện cột |
| config.json + các file lọc *.json | ⚙ Reload Config: lần đầu chọn thư mục chứa config.json và các *.json; nhấn giữ nút để đổi thư mục |
| Xuất file (copy) | 📤 Xuất file → chọn thư mục đích |

## Cập nhật v1.1
- Kéo mép phải tiêu đề cột (thanh dọc xanh) để co/giãn; độ rộng được nhớ. Menu 👁 có mục "Đặt lại độ rộng cột". Cột Document No luôn cố định, cuộn ngang ở phần còn lại.
- Nút lọc nhỏ gọn; nút 🎛 Bộ lọc ▾/▸ để ẩn/hiện cả vùng lọc.
- Ghép LINK nhiều bước: tên file ghi trong Excel (cột LINK cũ hoặc ô dạng xxx.pdf) → Document No → Title → so sánh bỏ dấu cách/gạch. Cột LINK hiển thị tên file (📎), chạm Document No để mở.
- Sau khi quét có hộp thoại chẩn đoán: tên file mẫu, Document No mẫu, danh sách bản ghi chưa có file.

## Cập nhật v1.2
- Hiển thị theo trang, mỗi trang 50 bản ghi (⏮ ◀ Trang x/y ▶ ⏭; chạm vào "Trang x/y" để nhập số trang cần tới).
- Bộ lọc: bấm tiêu đề "▾ Bộ lọc" để ẩn/hiện các nút lọc (bỏ nút 🎛 trên thanh trên cùng).
- Tối ưu: sắp xếp ngày nhanh + cache, đọc Excel và ghi chú song song, ghép file nhanh hơn, tìm "tất cả cột" không còn khớp nhầm vào đường dẫn LINK.
- Sửa lỗi: có thể crash khi Refresh/Load file khác lúc danh sách cũ dài hơn bảng mới; trạng thái treo "Đang tải" khi lỗi; lọc NOTE không cập nhật khi tích/bỏ tích cờ.
