# Bật ADB bằng tay (khi cần)

Không bắt buộc, và thường không cần làm: TV Setup tự mở chỗ bật. Chưa bật ADB thì app điện thoại và app máy tính vẫn bấm phím được. Bật ADB để có thêm hình TV, app, tệp, hẹn giờ.

1. **Tự động (dễ nhất):** [cài TV Setup](install-tv.md) rồi mở. App mở thẳng Tùy chọn nhà phát triển, kể cả khi TV giấu Cài đặt. Bật **Gỡ lỗi ADB**, quay lại, chọn **Kết nối → Kết nối lại**.
2. **Không thấy Tùy chọn nhà phát triển?** Trang báo “không dùng được”? Tự làm: **Tùy chọn thiết bị (Google TV: Hệ thống) → Giới thiệu**, OK 7 lần vào **Bản dựng**, rồi bật **Gỡ lỗi ADB**.
3. **Chromecast với Google TV / Google TV Streamer:** **Hệ thống → Giới thiệu**, bấm OK 7 lần vào **Bản dựng Android TV OS**; rồi **Hệ thống → Tùy chọn nhà phát triển → Gỡ lỗi USB**.
4. **Box nhà mạng (FPT, Viettel…):** đừng bấm 7 lần, box có thể đòi mật khẩu hoặc khóa máy. Cài TV Setup trước, app mở thẳng Tùy chọn nhà phát triển.
5. **TCL (bản Trung Quốc) không có Tùy chọn nhà phát triển?** Vào **Cài đặt → Giới thiệu**, bấm **Lên, Xuống, Trái, Phải** nhiều vòng tới khi hiện “ADB: địa chỉ:cổng”. Lần nào cần cũng phải làm lại.
6. **Bản dựng đòi mật khẩu** (box FPT Play…)? Cài TV Setup, nó mở thẳng chỗ bật. Vẫn kẹt: **🧭 Bật ADB giúp tôi**, hoặc bỏ qua, [phím vẫn dùng được](nokeys.md).

> 📡 TV chỉ có **Gỡ lỗi không dây** (Android 11 trở lên)? Không cần Gỡ lỗi ADB: mở trang web của TV, bấm **📡 Ghép bằng Gỡ lỗi không dây**, làm theo [hướng dẫn](wireless.md).

> 🛡 **NVIDIA SHIELD:** trong Tùy chọn nhà phát triển bật thêm **Gỡ lỗi mạng** (Network debugging), rồi chọn **Reconnect**.

> 🔥 **Fire TV:** ADB đã bật mà TV Setup vẫn báo không nối được? Amazon chặn app trên TV dùng ADB của chính nó; dùng app điện thoại hoặc máy tính.

> 🔐 TV Setup báo “TV đang hỏi Cho phép gỡ lỗi USB?”: ADB đã bật, chỉ cần tích **Luôn cho phép** trên TV rồi chọn OK.

> 📡 Nên bật thêm: **Mạng & Internet → Khởi động từ xa** (Remote start), để bật TV từ xa.

> 💡 Địa chỉ TV: TV Setup hiện sẵn. Tự tìm: **Cài đặt → Mạng & Internet** → bấm tên Wi-Fi, dạng `192.168.1.20`. Tên các mục có thể khác chút tùy hãng.

> 💡 TV Xiaomi bản nội địa: **Cài đặt → Tài khoản & Bảo mật → ADB调试**. Nút **Các bước bật ADB** trong app chia từng bước theo hãng.

> 💡 Trong **🧭 Bật ADB giúp tôi**, danh sách **Mở Cài đặt qua màn hình chính khác** có **TV Home** đứng đầu. Trang báo “không dùng được” ở box nhà mạng: nhà mạng đã khóa, hỏi tổng đài.
