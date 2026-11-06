# Lỗi thường gặp

| Gặp | Thử |
| --- | --- |
| Không kết nối được | Xem [Tự kiểm tra](selfcheck.md). Cùng Wi-Fi? TV bật? |
| ADB của TV không ở cổng 5555 | Gõ IP kèm cổng: `192.168.1.5:5877` (bàn phím số của điện thoại: dấu phẩy thay dấu hai chấm). App nhớ cổng cho TV đó. Cổng 5877 (BeeCube) app tự thử. |
| Phím không phản ứng | [Bật lại ADB](prep-tv.md), hoặc khởi động lại TV. |
| Trang web của TV hỏi mã lại | Ghép lại một lần; đừng dùng cửa sổ riêng tư. |
| Hình giật | Giảm cỡ hình và bitrate ([Chất lượng hình](quality.md)). |
| Tailscale không ra link | Chờ thêm rồi bấm lại **🔑** ([Tailscale](away.md)). |
| Play Protect cảnh báo | Chọn “Vẫn cài”. Mã nguồn công khai. |
| Cài.apks /.xapk /.apkm báo thiếu phần hoặc bị khóa | Dùng đúng file đủ bộ, chưa đặt mật khẩu; app chỉ gửi các phần hợp với TV. |
| Fire TV: TV Setup báo không nối được ADB dù đã bật | Amazon chặn app trên TV dùng ADB của chính TV; dùng app điện thoại hoặc máy tính. |
| Fire TV: nút Home / Alexa chết, các phím khác vẫn chạy | Cài đặt ban đầu chưa xong: app tự báo ở đầu trang, bấm **Sửa** (đặt `user_setup_complete` = 1). |
| Cài đặt của TV đứng ở trang Chuyển văn bản thành giọng nói | Chạy lệnh `pm clear com.google.android.tts` (mục Terminal, hoặc `adb shell`); giọng đọc tải lại ở lần dùng đầu. |
| Mọi app báo “Ứng dụng không sử dụng được… do Google TV quản lý” | Tab **Ứng dụng** có thẻ **🔒 App bị chặn**: bấm **Bỏ chặn** (màn hình chính Google TV được đặt lại). Bị lại đúng giờ cũ thì tắt giới hạn giờ trong hồ sơ. |
| Trang web của TV không hiện mã ghép | TV không có ADB nên chưa tự hiện được: mở app TV Setup trên TV, mã ở màn hình chính (2 phút). Xem [Mở trang web của TV](web-iphone.md). |
