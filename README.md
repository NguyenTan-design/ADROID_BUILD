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

## Cập nhật v1.3 - Advanced search (PDF)
Nút **🔎 Tìm nâng cao** mở một màn hình riêng, hoạt động độc lập (không cần Load File / Quét thư mục).
- **➕ Thêm thư mục**: chọn thư mục chứa PDF → app đọc chữ từng trang của mọi PDF (kể cả thư mục con) và lập chỉ mục. Thêm được nhiều thư mục, chỉ mục được lưu lại.
- Tích chọn thư mục nào thì tìm trong thư mục đó. Tìm không phân biệt hoa/thường và dấu tiếng Việt (`bao cao` ra `báo cáo`).
- Kết quả gom theo file, ghi rõ số trang chứa từ khóa; chạm vào 1 trang để xem nội dung (từ khóa được tô vàng), có nút 📂 Mở file.
- **⟳ Cập nhật**: chỉ xử lý file mới/đã sửa và bỏ file đã xóa. **♻ Lập lại**: xóa chỉ mục thư mục rồi làm lại từ đầu. **🗑 Xóa**: bỏ thư mục khỏi danh sách (không xóa PDF).
- Giới hạn: PDF scan (ảnh, không có lớp chữ) không tìm được vì chưa có OCR.

## Cập nhật v1.4
- Chạm vào ô **Title**: hiện popup đầy đủ nội dung (bôi chọn/copy được), danh sách file đính kèm, nút **📂 Mở link**, nút **🙈 Hide / 👁 Unhide**.
- **Hide nhiều dòng**: nhấn giữ để chọn các dòng → chạm ô Title của 1 dòng đã chọn → popup hiện **Hide (n)** / **Unhide (m)** cho tất cả dòng đã chọn. (Khi đã chọn dòng, chạm ô khác sẽ bật/tắt chọn dòng; ô Title luôn mở popup.)
- Dòng đã Hide được ghi nhớ (theo Document No) và biến mất khỏi bảng. Bấm nút **HIDE (n)** trong bộ lọc để xem riêng các dòng đã Hide; mở popup ở đó để Unhide. Nút Clear quay về chế độ bình thường.
- Cuộn mượt hơn: cuộn ngang dùng 1 phép dịch đồ họa chung thay vì xếp lại từng ô, mỗi dòng chỉ có 1 bộ nhận cú chạm, kẻ ô vẽ trực tiếp, tái sử dụng dòng khi cuộn dọc.

## Cập nhật v1.5 - Ghi nhớ trạng thái giống lần đóng cuối
Màn hình chính: file Excel, thư mục quét, bộ lọc đang bật (TYPE/Category/NOTE/HIDE), nội dung 3 ô tìm kiếm, cột sắp xếp + chiều, trang đang xem,
các dòng đang chọn, vị trí cuộn dọc + ngang, cột ẩn, độ rộng cột, ẩn/hiện vùng lọc, ghi chú, dòng Hide.
Tìm nâng cao: từ khóa, kiểu tìm (tất cả từ / cụm từ), thư mục đang tích chọn, ẩn/hiện danh sách thư mục; mở lại sẽ tự chạy lại lần tìm cuối.
Khi chọn file Excel khác thì sắp xếp/trang/vị trí cuộn/dòng chọn của file cũ được đặt lại.
