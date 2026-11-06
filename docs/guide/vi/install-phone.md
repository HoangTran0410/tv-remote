# Cài app điện thoại (Android)

Cách dễ nhất: chưa cần cài gì lên TV, bấm phím được ngay ([xem](nokeys.md)). Bật ADB để có thêm hình TV, app, tệp.

1. Trên điện thoại Android, mở trang chủ này, bấm tải **tv-remote.apk** rồi mở file. Android hỏi “ứng dụng không rõ nguồn” thì cho phép trình duyệt cài.
2. Mở **TV Remote**, bấm **Quét Wi-Fi** rồi chọn TV (hoặc gõ địa chỉ TV; ADB ở cổng khác 5555 thì gõ `ip:cổng`, ví dụ `192.168.1.5:5877`).
3. Lần đầu TV hỏi “Cho phép gỡ lỗi USB?”: tích **Luôn cho phép** rồi OK. Xem [phần này](allow-usb.md).
4. Xong: hình TV hiện trên điện thoại. TV chưa bật ADB thì vẫn [bấm phím được](nokeys.md).

> 💡 Điện thoại và TV phải cùng Wi-Fi. App tự báo khi có bản mới. Cài lên thiết bị Android khác: mã Downloader **2973135**. Link trực tiếp: [tv-remote.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-remote.apk).

> 💡 Cập nhật trong app: lần đầu Android hỏi quyền “Cài ứng dụng không rõ nguồn”. Bấm **Mở cài đặt**, bật “Cho phép từ nguồn này”, quay lại là xong. Sau đó (Android 12+) app tự cập nhật ngầm.

> 💡 Không cài được: bấm **Tải bằng trình duyệt**, mở file vừa tải rồi bấm Cài đặt. Samsung: tắt Trình chặn tự động, cập nhật xong bật lại.
