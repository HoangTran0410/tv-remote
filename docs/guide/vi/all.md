# Hướng dẫn TV Remote (cho trợ lý AI)

## Dùng cho ai

Tìm dòng giống bạn, bấm từng mục để xem cách làm. Đa số cần [TV Setup](#install-tv) trên TV.

| Ai | Dùng gì |
| --- | --- |
| 👨‍👩‍👧 Gia đình có trẻ nhỏ | [Chế độ trẻ em, khóa app](#lock), [giờ đi ngủ](#bedtime), [giới hạn giờ xem](#limits), [lịch mở app](#schedule), [con xin mở qua Telegram](#telegram) |
| 🏨 Homestay, khách sạn, phòng cho thuê | [khóa Cài đặt TV bằng PIN](#lock), [màn hình chào](#welcome), [TV Home không quảng cáo](#home), [nhiều TV một lúc](#many), [tình trạng mọi TV](#tv-status), [quản lý từ xa](#away) |
| ☕ Quán cà phê, cửa hàng, phòng chờ | [một nút làm nhiều việc](#scenes), [lịch mở app](#schedule), [giữ kết nối khi tắt TV](#awake), [Home Assistant, Siri](#hook) |
| 👵 Ông bà, người lớn tuổi | [màn hình chính gọn](#home), [nói để tìm](#voice), [con cháu xem màn hình](#picture), [giúp từ xa](#away), [chia sẻ remote](#share) |
| 🧹 Muốn TV nhanh, sạch, riêng tư | [Tối ưu TV](#optimize), [kiểm tra quyền riêng tư](#privacy), [đổi màn hình chính](#launchers), [đổi phím remote](#keymap) |
| 🎮 Chơi game, xem kênh | [TV Games](#games), [điện thoại làm tay cầm](#gamepad), [TV Channels](#channels) |
| 📱 Mất hoặc hỏng remote | [điện thoại làm remote](#remote), [hồng ngoại](#ir), [chuột bay](#air) |

### Mẹo

- Homestay: **Khóa app bằng mã PIN** có nút **Khóa luôn Cài đặt TV**, khách không vào được Cài đặt để đổi hay gỡ gì.

## Cài app điện thoại (Android)

Cách dễ nhất: chưa cần cài gì lên TV, bấm phím được ngay ([xem](#nokeys)). Bật ADB để có thêm hình TV, app, tệp.

1. Trên điện thoại Android, mở trang chủ này, bấm tải **tv-remote.apk** rồi mở file. Android hỏi “ứng dụng không rõ nguồn” thì cho phép trình duyệt cài.
2. Mở **TV Remote**, bấm **Quét Wi-Fi** rồi chọn TV (hoặc gõ địa chỉ TV).
3. Lần đầu TV hỏi “Cho phép gỡ lỗi USB?”: tích **Luôn cho phép** rồi OK. Xem [phần này](#allow-usb).
4. Xong: hình TV hiện trên điện thoại. TV chưa bật ADB thì vẫn [bấm phím được](#nokeys).

> 💡 Điện thoại và TV phải cùng Wi-Fi. App tự báo khi có bản mới. Cài lên thiết bị Android khác: mã Downloader **2973135**. Link trực tiếp: [tv-remote.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-remote.apk).

> 💡 Cập nhật trong app: lần đầu Android hỏi quyền “Cài ứng dụng không rõ nguồn”. Bấm **Mở cài đặt**, bật “Cho phép từ nguồn này”, quay lại là xong. Sau đó (Android 12+) app tự cập nhật ngầm.

> 💡 Không cài được: bấm **Tải bằng trình duyệt**, mở file vừa tải rồi bấm Cài đặt. Samsung: tắt Trình chặn tự động, cập nhật xong bật lại.

## Cài app máy tính

1. Tải bản cho máy bạn (link bên dưới) rồi giải nén.
2. Mở app: Mac kéo **TV Remote** vào Applications rồi mở; Windows chạy `TV Remote.exe`; Linux chạy `tv-remote/bin/tv-remote`.
3. Lần đầu máy chặn? Mac: Cài đặt hệ thống → Quyền riêng tư & Bảo mật → **Vẫn mở**. Windows: **More info → Run anyway**.
4. App tự quét mạng tìm TV (hoặc gõ địa chỉ rồi **Kết nối**). Rồi làm theo “[Cho phép gỡ lỗi USB?](#allow-usb)”.

| Máy | Tải |
| --- | --- |
| Mac | [`TV-Remote-mac.zip`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-mac.zip) |
| Windows | [`TV-Remote-windows-x64.zip`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-windows-x64.zip) |
| Linux | [`TV-Remote-linux-x64.tar.gz`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-linux-x64.tar.gz) hoặc [`arm64`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-linux-arm64.tar.gz) |

> 💡 App máy tính tự cập nhật khi có bản mới. Không cần Python, adb hay ffmpeg. Linux cần gói `libwebkit2gtk-4.1-0`.

## Bấm phím khi TV chưa mở ADB

Chưa bật ADB vẫn điều khiển được. App điện thoại và app máy tính bấm phím bằng giao thức Android TV Remote của Google, nên nhiều TV dùng được ngay.

1. Mở app, chọn TV và bấm **Kết nối**.
2. TV hiện một **mã 6 ký tự**.
3. Gõ mã vào app. Xong: các phím dùng được.

| Làm được khi chưa có ADB | Cần ADB |
| --- | --- |
| Mũi tên, OK, Quay lại, Home, âm lượng, nguồn | Xem hình, chạm lên hình |
| Thêm phím cơ bản (phím media, nguồn) | Ứng dụng, tệp, sức khỏe, thông báo, gõ chữ, hẹn giờ trên TV |

Chưa có ADB thì trang Quản lý sẽ chỉ bạn bật nó: bấm **🧭 Bật ADB giúp tôi**.

**Bấm nút không ăn (app máy tính)?** Vào tab **📺 Thiết lập** và ghép nút remote bằng mã 6 ký tự TV hiện. Cũng kiểm tra bạn không mở hai cửa sổ cho cùng một TV.

### Mẹo

- VIDAA (Hisense...) ghi "thử nghiệm, chưa thử trên TV thật" ở danh sách TV tìm thấy.

## Cài app lên TV (TV Setup)

App nhỏ trên TV: chạy trang web của TV, hẹn giờ tắt, giới hạn app và nhiều thứ khác.

> 📱 **Có app điện thoại hoặc máy tính? Không cần Downloader.** Chọn TV (đã bật ADB), vào **Quản lý → ⏰ Tự động**, bấm **⚡ Cài TV Setup lên TV**. Một nút, Play Protect không chặn.

### Chỉ có TV: cài bằng Downloader

1. Trên TV, mở **CH Play**, tìm **Downloader** (AFTVnews, biểu tượng cam) và cài.
2. Mở Downloader (cho phép truy cập tệp nếu nó hỏi), gõ mã **2397346** rồi chọn **Go**.
3. Chọn **Install**. TV chặn nguồn lạ thì chọn Cài đặt, cho phép Downloader, quay lại, cài tiếp.
4. Mở **TV Setup**. Chưa bật ADB thì app mở thẳng chỗ bật: bật **Gỡ lỗi ADB**, quay lại, chọn **Kết nối → Kết nối lại**.
5. Có ADB, TV Setup tự cài **TV Home**, **TV Channels**, **TV Games**, **TV Apps** rồi hỏi giữ TV Home làm màn hình chính không (không bấm gì thì TV quay lại như cũ).
6. TV hiện mã QR và địa chỉ. Quét mã QR (hoặc xem “[Mở trang web của TV](#web-iphone)”).

Link trực tiếp: [tv-setup.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-setup.apk) · [tv-remote.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-remote.apk).

> ⚠️ Play Protect có thể cảnh báo vì app có quyền cài APK. Mã nguồn công khai; tin thì chọn “Vẫn cài”, hoặc cài từ app điện thoại, máy tính như trên.

> 💡 Sửa phím remote chết và “giữ 2 giây để đổi nguồn vào” nằm trong app nhỏ **TV Setup Keys**, đóng sẵn trong TV Setup và tự cài qua ADB khi cần.

### Mẹo

- TV chỉ có **Gỡ lỗi không dây** (không có cổng 5555)? Mở trang web của TV, bấm **📡 Ghép bằng Gỡ lỗi không dây**: xem “[Gỡ lỗi không dây](#wireless)”.
- Màn hình chính của TV Setup có dòng mã ghép (6 số) và menu có mục **Quên mọi khóa ADB**.
- Cài nhầm TV Setup lên điện thoại? App mời mở app điện thoại, hoặc chọn **Vẫn dùng ở đây**.
- Hàng bốn ô **TV Home**, **TV Channels**, **TV Games**, **TV Apps** trên màn hình chính ghi **số bản** (vd v0.9.0), **Chưa cài** hoặc **Có bản mới**: bấm để mở, cài hay cập nhật, không cần ADB.

## Mở trang web của TV (iPhone, trình duyệt)

iPhone không có app. Thay vào đó, TV tự phát một trang web điều khiển. Mở bằng Safari hay trình duyệt nào cũng được.

1. Trên iPhone, quét mã QR mà TV Setup hiện, hoặc gõ `http://<địa chỉ TV>:8765` vào Safari.
2. Trang hiện **🔗 Ghép với TV**. Bấm **📺 Hiện mã trên TV**.
3. Nhìn lên TV: có **mã 6 số**. Gõ mã vào trang rồi bấm **✓ Ghép**.
4. Xong. Lần sau mở lại là dùng được ngay, không hỏi mã nữa.

> 💡 Mẹo iPhone: bấm Chia sẻ → **Thêm vào MH chính** (Add to Home Screen) để có biểu tượng như một app.

> 💡 Trang chạy trên TV nên TV phải bật. Mã ghép chỉ để người trong nhà, đừng đưa ra Internet.

> 💡 TV chỉ có Gỡ lỗi không dây? Bấm **📡 Ghép bằng Gỡ lỗi không dây** ở trang này, gõ IP:cổng và mã 6 số. Chỉ một lần.

> 💡 TV không có ADB thì không tự hiện được mã: mở app **TV Setup** trên TV, mã nằm ở màn hình chính (có hiệu lực 2 phút).

## Remote hồng ngoại (không cần mạng)

Điện thoại có đèn hồng ngoại (nhiều máy Xiaomi, Huawei, Samsung đời cũ) thì dùng được như remote giấy: không cần Wi-Fi, không cần chọn TV. Chỉ có ở app điện thoại.

1. Ở danh sách TV, bấm **Remote hồng ngoại** (chỉ hiện khi máy có đèn hồng ngoại): các remote đã lưu hiện trước, bấm một cái là dùng. Remote mới: **Thêm remote**.
2. Chọn hãng (nhiều kiểu thì **Kiểu 1** phổ biến nhất), đặt tên (vd TV phòng ngủ). Sang máy khác: **Xuất file** rồi **Nhập file**.
3. Không có trong danh sách (box của nhà mạng, FPT Play Box)? Bấm **Dò mã**: app gửi lần lượt từng mã, thấy thiết bị phản hồi thì bấm **Có phản hồi**, app nhớ mã đó.
4. Android TV đã kết nối? **Dò mã** tự chạy: hướng đèn vào TV, TV tự báo mã đúng.
5. Box vẫn không có mã? **Học phím từ remote của box**: bấm từng phím trên remote thật, app đọc mã qua ADB và lưu thành remote riêng.

Mã lấy từ Flipper-IRDB (CC0). Bấm giữ phím mũi tên, âm lượng, kênh để lặp. Có cả phím màu, Netflix, YouTube, Prime khi remote của TV có.

### Mẹo

- Giữ một phím là giữ thật: phím nào (trừ Nguồn) cũng gửi liên tục khi còn giữ, nên giữ OK mở menu thay vì mở app.

## Air Mouse

Cầm điện thoại như điều khiển, xoay cổ tay là con trỏ chạy trên TV, như Magic Remote của LG. Hợp với app không dùng được phím mũi tên (trình duyệt, vài app cũ).

Chỉ có ở app điện thoại. Android TV cần ADB; TV LG dùng con trỏ của TV.

1. Trên remote, bấm nút dưới các phím mũi tên (**Phím mũi tên ▾**), chọn **Air Mouse**. Chỗ phím mũi tên thành ô chuột.
2. Cầm điện thoại xoay cổ tay, hoặc kéo ngón tay trên ô.
3. Xong: bấm lại **Air Mouse**, phím mũi tên trở lại và con trỏ biến mất.

| Thao tác | Làm gì |
| --- | --- |
| Kéo một ngón | Di con trỏ như touchpad, không cần gyroscope; gyroscope nghỉ khi ngón chạm. |
| Chạm ô | Click. |
| Chạm rồi chạm lại và kéo | Kéo thả bằng ngón tay, như touchpad laptop. |
| Kéo dải bên phải ô | Cuộn bằng một ngón, nhanh hơn thì cuộn xa hơn. Ngón khác trên ô vẫn di con trỏ bình thường. |
| 2 ngón kéo | Cuộn. |
| Giữ yên ngón trên ô (rung) rồi di chuyển | Như giữ chuột: xoay điện thoại hoặc kéo ngón để bôi đen chữ hay kéo thả thứ đang giữ. Nhấc ngón là thả. |

### Mẹo

- Người thuận tay trái: nút đổi chỗ ở chân dải cuộn đặt dải sang trái, nút đóng sang phải (nhớ lựa chọn).

## Bật ADB bằng tay (khi cần)

Không bắt buộc, và thường không cần làm: TV Setup tự mở chỗ bật. Chưa bật ADB thì app điện thoại và app máy tính vẫn bấm phím được. Bật ADB để có thêm hình TV, app, tệp, hẹn giờ.

1. **Tự động (dễ nhất):** [cài TV Setup](#install-tv) rồi mở. App mở thẳng Tùy chọn nhà phát triển, kể cả khi TV giấu Cài đặt. Bật **Gỡ lỗi ADB**, quay lại, chọn **Kết nối → Kết nối lại**.
2. **Không thấy Tùy chọn nhà phát triển?** Trang báo “không dùng được”? Tự làm: **Tùy chọn thiết bị (Google TV: Hệ thống) → Giới thiệu**, OK 7 lần vào **Bản dựng**, rồi bật **Gỡ lỗi ADB**.
3. **Chromecast với Google TV / Google TV Streamer:** **Hệ thống → Giới thiệu**, bấm OK 7 lần vào **Bản dựng Android TV OS**; rồi **Hệ thống → Tùy chọn nhà phát triển → Gỡ lỗi USB**.
4. **Box nhà mạng (FPT, Viettel…):** đừng bấm 7 lần, box có thể đòi mật khẩu hoặc khóa máy. Cài TV Setup trước, app mở thẳng Tùy chọn nhà phát triển.
5. **TCL (bản Trung Quốc) không có Tùy chọn nhà phát triển?** Vào **Cài đặt → Giới thiệu**, bấm **Lên, Xuống, Trái, Phải** nhiều vòng tới khi hiện “ADB: địa chỉ:cổng”. Lần nào cần cũng phải làm lại.
6. **Bản dựng đòi mật khẩu** (box FPT Play…)? Cài TV Setup, nó mở thẳng chỗ bật. Vẫn kẹt: **🧭 Bật ADB giúp tôi**, hoặc bỏ qua, [phím vẫn dùng được](#nokeys).

> 📡 TV chỉ có **Gỡ lỗi không dây** (Android 11 trở lên)? Không cần Gỡ lỗi ADB: mở trang web của TV, bấm **📡 Ghép bằng Gỡ lỗi không dây**, làm theo [hướng dẫn](#wireless).

> 🔐 TV Setup báo “TV đang hỏi Cho phép gỡ lỗi USB?”: ADB đã bật, chỉ cần tích **Luôn cho phép** trên TV rồi chọn OK.

> 📡 Nên bật thêm: **Mạng & Internet → Khởi động từ xa** (Remote start), để bật TV từ xa.

> 💡 Địa chỉ TV: TV Setup hiện sẵn. Tự tìm: **Cài đặt → Mạng & Internet** → bấm tên Wi-Fi, dạng `192.168.1.20`. Tên các mục có thể khác chút tùy hãng.

> 💡 TV Xiaomi bản nội địa: **Cài đặt → Tài khoản & Bảo mật → ADB调试**. Nút **Cách bật ADB trên TV** trong app chia từng bước theo hãng.

> 💡 Trong **🧭 Bật ADB giúp tôi**, danh sách **Mở Cài đặt qua màn hình chính khác** có **TV Home** đứng đầu. Trang báo “không dùng được” ở box nhà mạng: nhà mạng đã khóa, hỏi tổng đài.

## Ghép qua Gỡ lỗi không dây (Android 11+)

Một số Google TV đời mới chỉ có “Gỡ lỗi không dây”, không có cổng ADB thường. App điện thoại, app máy tính và trang web của TV (cả iPhone) ghép được, không cần Gỡ lỗi USB.

1. Mở **🧭 Bật ADB giúp tôi** trong app, chọn **📡 Ghép bằng Gỡ lỗi không dây**.
2. Trên TV: **Cài đặt → Tùy chọn nhà phát triển → Gỡ lỗi không dây → Ghép nối thiết bị bằng mã ghép nối**.
3. Nhập **IP:cổng ghép nối** và **mã 6 số** TV hiện ra, bấm **🔗 Ghép**.
4. Nếu app hỏi **cổng kết nối**: lấy ở màn hình Gỡ lỗi không dây, dưới “Địa chỉ IP và cổng”, rồi bấm Ghép lại.

> ⚠️ Mã sai hoặc hết hạn: gõ mã mới. Trên trang web của TV, nút này ghép chính TV Setup; khởi động lại TV, nó tự bật lại.

## Kết nối và “Cho phép gỡ lỗi USB?”

Lần đầu một máy nối vào TV, TV hỏi xem có tin máy đó không. Đây là bước an toàn của Android, chỉ làm một lần cho mỗi máy.

1. Trong app, chọn TV và bấm **Kết nối**.
2. App hiện “**Nhìn lên TV**”. Trên TV hiện hộp **Cho phép gỡ lỗi USB?**.
3. Dùng điều khiển TV: tích **Luôn cho phép (từ máy này)**, rồi chọn **OK**.
4. App tự vào màn hình điều khiển.

> ⚠️ Chỉ bấm Cho phép khi chính bạn vừa bấm Kết nối. Hộp này cho phép máy kia điều khiển sâu TV.

> 💡 Không thấy hộp? Xem [Tự kiểm tra khi không kết nối được](#selfcheck).

## Điều khiển từ xa

Tab **🎮 Remote** giống điều khiển thật. Chạm là bấm.

Trên máy tính và trang web, giữ phím Enter, mũi tên, Esc hay Home 0,4 giây thì TV nhận là giữ phím (menu nhấn giữ, cuộn nhanh), như giữ nút trên trang.

Trên app điện thoại, nút **Thu nhỏ hình** cạnh toàn màn hình thả hình TV nổi ở góc, trên mọi màn hình của app. Kéo để đổi góc, chạm để đặt về chỗ cũ, ✕ để tắt hình.

Đang nổi mà ra màn hình chính hay app khác (Android 8 trở lên) thì hình vào cửa sổ PiP của hệ thống. Đóng cửa sổ, TV ngừng gửi hình.

| Phím | Làm gì |
| --- | --- |
| Nguồn | Bật hoặc tắt TV. TV ngủ sâu: bấm ☀️ Bật TV cạnh TV trong danh sách (app máy tính). |
| Mũi tên + OK | Di chuyển và chọn. App điện thoại: nút dưới mũi tên đổi sang Touchpad hoặc Air Mouse. |
| Ứng dụng · Quay lại · Home · Cài đặt | Hàng dưới mũi tên. Home và Quay lại ở giữa, tay nào cũng dễ bấm. |
| Âm lượng · Kênh | Hai nút dài, bấm trên hoặc dưới. |
| Tắt tiếng · Phát/Dừng · Bài trước/sau · Tua | Hàng phím media. |
| Nói | Nói điều muốn TV làm (vd: mở YouTube). Giữ: mở Trợ lý của TV. |
| Thêm phím | Mở trên TV, Nguồn vào, Air Mouse, Hẹn giờ, Chụp màn hình, Vẽ, Cảnh, Thông báo, Cài đặt, Phụ đề, Thông tin; **Tiện ích**: TV, Giảm độ sáng, Lịch phát, Chia sẻ, Dừng, TV Home, Hồng ngoại, phím game. |

**Mở trên TV** (trong Thêm phím): **Mở app đã cài** (chọn app, có ô tìm), **Tải app** (CH Play, YouTube, Kodi…), mở link, **Gửi lên TV** (ảnh, video, nhạc, clipboard).

> 💡 Dán link luồng (`.m3u8`, `.mp4`) vào Mở trên TV: TV Channels phát luôn.

> 💡 TV treo? Thêm phím → **Tiện ích** → **Cứu TV treo**: đóng app đang mở rồi về Home, không phải rút điện.

> 💡 Trên máy tính, gõ phím trực tiếp ở tab Remote: mũi tên, Esc là Quay lại, Enter, Backspace, Home; Cmd/Ctrl+V để dán chữ.

> 💡 Trên app điện thoại, phím tăng/giảm âm lượng của máy điều khiển âm lượng TV.

> ⌨️ Phím màu bấm ở đây cũng mở app bạn đã gán cho nó (cần TV Setup): xem [Phím trên remote](#keymap).

### Mẹo

- Trên app điện thoại, nút Nói mở [Nói nhanh](#quick-talk): giữ và nói, buông là gửi.

## Gõ chữ lên TV

1. Bấm **⌨️ Bàn phím** ở tab Remote: ô gõ mở ngay dưới hàng phím đầu.
2. Gõ vào ô **Gõ lên TV**. Gõ có dấu tiếng Việt thoải mái.
3. Bật **⚡** để chữ lên TV ngay khi gõ. Tắt thì gõ xong bấm **↵** để gửi.
4. Dùng **⌫** để xóa, **123** cho bàn phím số.

> 💡 Cần ADB. Gõ vào ô tìm kiếm của app trên TV, nên nhớ chọn ô đó trên TV trước.

> 💡 Chọn ô chữ trên TV hoặc khi bàn phím TV bật lên, ô gõ và bàn phím điện thoại tự mở trong chưa tới 1 giây. Rời ô mà chưa gõ gì thì ô gõ tự đóng.

> 💡 Tắt ở Cài đặt (🛠️) → Giới thiệu → “Bàn phím của TV” → **Không tự mở**.

## Clipboard của TV (chữ có dấu, dán chữ)

Clipboard là chỗ TV giữ chữ vừa sao chép. Dùng để xem chữ trên TV, lấy về máy, hoặc đưa chữ lên TV.

1. Thêm phím → **Mở trên TV** → **Bộ nhớ tạm của TV**.
2. Xem chữ TV đang sao chép. Bấm **Chép về máy** để lấy về.
3. Muốn đưa chữ lên TV: gõ chữ, gửi lên, rồi dán vào ô đang gõ trên TV.

> 💡 Mẹo chữ có dấu: đưa vào clipboard của TV rồi dán. Cần ADB.

## Ra lệnh bằng giọng nói

Ô **Hỏi TV: mở YouTube** (trong **⌨️ Bàn phím**) hiểu câu nói thường: gõ rồi bấm **🔍**, hoặc bấm **Nói** để nói.

| Nói | TV làm |
| --- | --- |
| mở Netflix | Mở app đó. |
| tắt tiếng · tạm dừng · phát | Bấm phím tương ứng. |
| về home · quay lại · bài tiếp · bài trước | Điều hướng. |
| tăng âm lượng 5 · giảm âm lượng | Đổi âm lượng. |
| tắt TV · bật TV | Nguồn. |
| hẹn giờ tắt 30 phút · hủy hẹn giờ | Hẹn giờ tắt. |
| mở kênh VTV1 · kênh tiếp | Mở kênh trên [TV Channels](#channels). |
| tên một cảnh | Chạy cảnh đó. |

> 💡 Câu nào lạ thì chuyển cho Trợ lý hoặc tìm trên YouTube.

> Các câu nói là một danh sách có chữ ký trên trang web: thêm câu mới không cần cập nhật app.

> 💻 App máy tính trên Windows, Linux: 🎤 gửi thẳng giọng bạn tới Trợ lý của TV (TV Android), TV tự nghe.

Nút mic của điều khiển TV: bật **Nút mic mở giọng nói của TV Remote** ở tab Thiết lập, thẻ Nút remote (mặc định tắt). Cần TV Setup mới.

### Mẹo

- Trên app điện thoại có [Nói nhanh](#quick-talk) (giữ để nói, xem trước việc sẽ làm). Câu lệnh là danh sách ký trên trang web: có thêm nhiều lệnh thường ngày như đóng app, về Home, mở Cài đặt.

## Nói nhanh (app điện thoại)

Nút Nói trên remote của app điện thoại: giữ, nói, buông là TV làm.

1. Lần đầu chọn **Giữ để nói (nhanh)** hoặc **Bấm rồi nói (như cũ)**; máy nhớ lựa chọn.
2. Giữ nút to và nói, không hộp thoại. Phía TRÊN ngón tay hiện việc câu nói sẽ làm (phím, âm lượng, mở app, hẹn giờ, cảnh), cập nhật theo từng chữ.
3. Buông tay để gửi ngay; trượt ra ngoài rồi buông để hủy.

- Đổi lại cách cũ: chip **Cách cũ**, giữ nút Nói, hoặc Cài đặt → Nút Nói.
- Dùng gói giọng nói offline nếu máy có; hỏi quyền micro lần đầu, từ chối thì dùng cách cũ. Trang web chưa có.

## Xem và chạm lên màn hình TV

Bạn thấy hình TV ngay trên máy và chạm hay bấm chuột như đang chạm vào TV.

1. Ở thẻ **🖥️ Màn hình TV**, bấm **▶ Xem**.
2. Chạm lên hình để bấm. Kéo để vuốt.
3. Bấm **⛶** để xem toàn màn hình.
4. App điện thoại: nút **Thu nhỏ hình** cạnh ⛶ thả hình nổi trên mọi màn hình của app. Kéo đi đâu cũng được, hai ngón để phóng to, chạm để đặt về chỗ cũ.

| Trên máy tính | Trên điện thoại | Kết quả |
| --- | --- | --- |
| Bấm chuột | Chạm | Bấm vào chỗ đó trên TV |
| Kéo chuột | Vuốt | Vuốt trên TV |
| Lăn chuột | Vuốt hai ngón | Cuộn danh sách |
| Chuột phải | (không có) | Quay lại |

> 💡 Video có bản quyền (Netflix…) hiện màu đen và báo “TV không cho chiếu hình lên đây”. Đây là chủ ý của app đó, không phải lỗi.

> 💡 Wi-Fi yếu hoặc iPhone: chọn **Ảnh chụp** thay **Trực tiếp**.

💡 Nhiều máy xem cùng lúc được; góc hình hiện **👁 2**. Cùng một app máy tính hay trang web của TV thì dùng chung một luồng hình.

## Nghe tiếng TV trên máy bạn

Nghe TV bằng tai nghe của bạn để không làm phiền người khác. Cần TV Android 11 trở lên.

1. Bấm **🎧 Nghe** cạnh nút Xem.
2. Chọn **Cách nghe tiếng TV**: **Chỉ máy này** (loa TV im) hoặc **Cả TV và máy này** (TV Android 13 trở lên).
3. Muốn tiếng đẹp hơn, bấm **HQ** (160 kbit/s).
4. Tiếng bị giật: bấm **Đều**. Trễ khoảng nửa giây nhưng không giật.

> 💡 Trên app điện thoại, 🎧 vẫn nghe khi tắt màn hình.

## Chất lượng hình và “Nhịp hình”

Hình giật hoặc mờ? Mở **🖥️ Chất lượng hình** từ nút chất lượng cạnh ▶ Xem. Mọi thứ đặt sẵn đã ổn cho đa số TV.

| Mục | Dùng khi |
| --- | --- |
| Cỡ hình (240p … 960p) | Hình giật: chọn nhỏ hơn. Muốn nét: chọn lớn hơn. |
| Hoặc: Nét nhất / Ảnh chụp | Nét nhất chỉ có ở app máy tính. |
| Khung/giây · Bitrate | Mạng yếu: giảm bitrate. |
| Bộ mã hóa | Tự chọn, Phần cứng hoặc Phần mềm. Máy cũ lỗi hình thì thử đổi. |
| TV dưới 1,5 GB RAM | Tự giữ 720p, 20 hình/giây cho đỡ giật. Chọn mức khác thì theo bạn. |
| Nhịp hình | **Nhanh nhất** (mặc định) hiện ngay. **Đều hơn** thêm khoảng 33 ms để hình mượt đều. Chỉ hiện ở trình duyệt có WebCodecs. |

> 💡 Hình xa nhà (qua Tailscale) giật là bình thường: giảm cỡ hình và bitrate.

## Dùng tay cầm chơi game làm remote

Cắm hoặc nối tay cầm (Xbox, PlayStation…) vào máy đang chạy app là bấm được TV.

1. Nối tay cầm (USB hoặc Bluetooth), mở tab **🎮 Remote** và **bấm một nút bất kỳ**. Hiện **Đã nối tay cầm** là dùng được.
2. **D-pad** (hay cần trái) là các mũi tên: ô chọn trên TV đi theo.
3. **A** là OK: mở mục đang chọn.
4. **B** là Quay lại. Các nút khác: bảng dưới.

| Tay cầm | Phím TV |
| --- | --- |
| D-pad, cần trái | Mũi tên |
| A · B | OK · Quay lại |
| X · Y | Phát/Dừng · Tắt tiếng |
| LB · RB | Giảm · tăng âm lượng |
| LT · RT | Kênh |
| Start · Select | Home · Menu |

Giữ mũi tên, âm lượng hay kênh thì nó lặp lại. Khi đang **xem hình TV**, các nút A/B/X/Y, LB/RB, Start/Select đi lên TV như nút tay cầm thật (để chơi game). LT/RT vẫn là phím kênh.

> 💡 Tay cầm không làm gì khi bạn đang gõ chữ hoặc đang mở một hộp thoại. Dùng được trên app máy tính, trang web của TV và màn hình remote của app điện thoại.

## Ứng dụng trên TV

Tab **📱 Ứng dụng** liệt kê app đã cài. Gõ tên vào ô tìm, hoặc lọc bằng chip: Tất cả, Đang mở, 🚀 Tự chạy, 🔒 Cần giữ, ⏸ Đã tắt.

- **Đang mở**: app gần đây, mới nhất trước, kèm RAM.
- **Chuyển** quay lại app, **Đóng** đóng app.
- **Đóng nhiều app**: tích các app cần đóng (app TV cần giữ không đóng được).

Bấm **⋯** trên một dòng:

| Mục | Làm gì |
| --- | --- |
| Mở · Dừng | Chạy hoặc tắt app. |
| Chi tiết & quyền | Xem quyền app. |
| Sao chép tên gói | Dùng cho phím tắt hay cảnh. |
| Sao lưu (tải về) | Lưu app thành file. |
| Xóa dữ liệu · Tắt app · Gỡ | Dọn, tắt hẳn hoặc gỡ. Tắt thì **Bật lại** được. |

Phía trên có: **🧹 Dọn bộ nhớ đệm**, **📥 Khôi phục** (từ file.tar), **↻ Tải lại**, **🤖 Hỏi AI**, **📝 Nhiều app** (chọn nhiều app một lúc).

> ⚠️ Đừng tắt app có 🔒 Cần giữ. TV có thể không lên hình.

### Mẹo

- 🔒 Cần giữ chỉ đánh dấu TV Setup, Tailscale và TV Home (khi là màn hình chính). Các app khác là app thường.

## Tối ưu TV và Hoàn tác

TV chậm? Tối ưu giúp mở app nhanh hơn. Không gỡ gì cả, và **Hoàn tác** trả lại như cũ.

1. Vào tab **📺 Thiết lập** → thẻ **⚡ Tối ưu TV**.
2. Chọn những gì muốn: **Tắt hiệu ứng chuyển cảnh**, **Dùng [TV Home](#home) làm màn hình chính**, **Tắt app đề xuất**, **Tắt thêm app khác**.
3. Bấm **⚡ Chạy tối ưu** và xác nhận.
4. Không ưng? Bấm **↩ Hoàn tác**.

> 💡 Android TV và Google TV đều đổi được màn hình chính. TV không cho đổi hẳn thì TV Setup vẫn cho nút Home mở màn hình chính đã chọn.

> 💡 Mất màn hình chính sau khi tối ưu? Trên TV: Cài đặt → Ứng dụng → Ứng dụng hệ thống → Android TV Home (hoặc Google TV Home) → **Bật**.

> 💡 Nút Nguồn vào / YouTube / Netflix trên remote chết sau khi tắt launcher gốc? TV Setup tự bật lại chúng (tắt ở **Tối ưu TV** > **Nút điều khiển**). Xem [phím chết](#keyfix).

> 🛡️ App giữ mạng Wi-Fi đã lưu hoặc cổng HDMI / kênh TV không bao giờ bị tắt, xóa dữ liệu hay gỡ: TV sẽ quên mạng hoặc mất nguồn vào.

> 🔁 Sau khi TV cập nhật firmware, TV Setup tắt lại app đã tắt mà bản cập nhật bật lại, và đặt lại TV Home. Hoàn tác thì dừng.

> 💾 Script khôi phục: Hoàn tác bằng adb.

> 💡 Chỉ chạy được trên TV thật, không chạy trên điện thoại hay máy tính bảng.

### Mẹo

## Thư viện màn hình chính

Đổi màn hình chính của TV. Đứng đầu là [TV Home](#home), launcher của dự án.

1. Thẻ **Màn hình chính** (hoặc **Tối ưu TV** > **Màn hình chính** trên TV) → chọn launcher.
2. Bấm **Cài và dùng**.
3. TV hỏi **Giữ**: bấm Giữ. Không bấm gì trong 30 giây thì TV về launcher cũ.

- **Dùng launcher gốc của TV**: trả lại launcher gốc bất cứ lúc nào.
- Google TV: chọn launcher nào cũng tắt luôn launcher Google TV, để phím Home trên remote mở đúng launcher đã chọn. **Dùng launcher gốc của TV** bật lại như cũ.
- TV không cho đổi hẳn (Chromecast, TCL, Kalley, ZTE…): TV Setup cho phím Home mở launcher đã chọn; giữ lâu Home mở launcher gốc.
- Wi-Fi thuộc trình cài đặt ban đầu: **Quên mạng** đó, kết nối lại, chạy lại để đổi hẳn.
- Sony BRAVIA, Xiaomi (PatchWall) và Android TV đời cũ: chọn launcher cũng tắt launcher gốc của hãng, nên phím Home không quay về nó. TV Setup canh chừng và tự làm lại nếu launcher gốc bật lại.
- Launcher lỗi hai lần liền: TV Setup tự trả về launcher cũ.
- Nhãn **CH Play**: bấm **Mở trong CH Play**, cài trên TV, rồi **Dùng**.
- Launcher không có tiếng của TV có nhãn ngôn ngữ nó sẽ hiện (Android 13+: tiếng Anh).
- Nhiều TV: **Nhiều TV** > **Màn hình chính...**

### Mẹo

- Thư viện mở trong khoảng 0,4 giây: danh sách hiện trước, hình tải dần.

## TV Home, màn hình chính của dự án

Màn hình chính không quảng cáo, chỉnh từ điện thoại hoặc máy tính. Cài từ [thư viện launcher](#launchers).

1. Tab **TV** → thẻ **Màn hình chính** → **Tùy chỉnh TV Home**.
2. **Bố cục**: kéo thả app trên lưới, bấm **Lưu thứ tự**. Ẩn app không cần.
3. Chọn hình nền và widget; xem trước ở trên cùng, TV đổi ngay.
4. **Chủ đề**: giao diện có sẵn, hoặc **Lưu giao diện đang có thành chủ đề**.

| Widget | Hiện gì |
| --- | --- |
| Hệ thống | CPU, RAM, bộ nhớ, IP, tốc độ mạng, ADB |
| Thời tiết | Nhiệt độ, 3 ngày tới (cần mạng) |
| Đồng hồ kim | Mặt tròn, kim giờ và kim phút; cỡ, mặt (Cổ điển, Tối giản, La Mã, Hiện đại, Số) và giao diện sáng / tối chọn trong ⚙ |
| Lịch âm | Ngày âm, can chi, còn mấy ngày tới rằm / Tết |
| Lịch tháng | Tháng này, hôm nay khoanh đỏ; kiểu Ngày: thứ và ngày thật to (ngày âm: widget Lịch âm) |
| App dùng nhiều | 7 ngày qua, giờ còn lại của [giới hạn app](#limits) |
| Hẹn giờ | Giờ TV tắt, giờ ngủ |
| Ghi chú | Chữ ghim trên màn hình |

| Hình nền | Là gì |
| --- | --- |
| Gợi ý | Slideshow Việt Nam, video êm, trang web sống; danh sách cập nhật từ site, không cần bản app mới |
| Tự chọn | 1 màu hoặc 2-4 màu chuyển dần, 9 hướng; trên TV đổi bằng ◀ ▶ |
| Hình vẽ | Có sẵn, không cần mạng |
| Bing | Ảnh 8 ngày gần nhất; bấm lại ô Bing trên TV = ảnh hôm trước |
| Hình nền đẹp | Ảnh NASA, đúng cỡ màn hình |
| Ảnh của bạn | Tối đa 10; ảnh dọc: **Hiện trọn ảnh**, hai bên mờ |
| Trang web | Dán liên kết http/https; TV mở thử rồi báo kết quả |

Chọn nhiều hình rồi đặt **Đổi hình nền** (30 phút → mỗi lần mở) để xoay vòng.

**Phủ tối** 0-100 %: chữ dễ đọc trên mọi hình.

| Trên TV Home | Là gì |
| --- | --- |
| Thanh trên cùng | Hai nút: **Mạng** (trạng thái) và **Cài đặt** = [Cài đặt nhanh](#home-control), nơi có Nguồn vào và các nút Tắt nguồn / Khởi động lại / Tắt màn hình / Chế độ chờ. |
| Tùy chỉnh | Danh sách nhóm, OK để vào từng nhóm: Hình nền, **Giao diện**, **Bố cục**, Widget, **Hệ thống** (màn chờ, **Màn hình chính**), **Phím & khởi động**, Ngôn ngữ. |
| Màn hình chính | Trong **Hệ thống**: mở bộ chọn launcher của TV Setup để đổi sang launcher khác (cần TV Setup) |
| Phím & khởi động | Phím remote mở app, app tự mở khi bật TV |

### Mẹo

- Trên TV: biểu tượng bánh răng góc trên phải → Tùy chỉnh TV Home, hoặc phím Menu.
- Giữ OK trên app: mở, ghim, dời, ẩn, đổi tên, gán phím, khóa hoặc giới hạn giờ (hỏi mã PIN phụ huynh), dừng, xóa bộ nhớ đệm.
- Mỗi widget **To** hoặc **Nhỏ**; hai widget nhỏ chung một cột.
- **Số cột**: 4 đến 8 app mỗi hàng (mặc định 5).
- Bấm Home trong một app: về TV Home, đứng đúng ở app đó.
- **Màn chờ TV Home**: đồng hồ lớn và hình nền khi TV rảnh.
- App bị khóa có ổ khóa, app có giới hạn hiện ⏳ phút còn lại.
- Có bản mới: thẻ hiện **Cập nhật TV Home**, cài đặt được giữ.

Chỉ có remote của TV: Downloader, mã **4356026**, rồi chọn TV Home làm màn hình chính. Link trực tiếp: [tv-home.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-home.apk).

Google TV: trang **Tài khoản và hồ sơ** trống khi dùng TV Home. Đổi hồ sơ: **Dùng launcher gốc của TV**, đổi, rồi chọn lại TV Home.

## Widget Thị trường, Tin tức, bản đồ thời tiết và ⚙

TV Home có thêm hai widget lấy số liệu thẳng từ nguồn, không qua máy chủ nào. Cần mạng.

| Widget | Hiện gì |
| --- | --- |
| Thị trường | Giá vàng SJC và nhẫn 9999, tỷ giá Vietcombank, tùy chọn Bitcoin. Dạng ô hoặc dòng chạy chữ. Làm mới ~20 phút; mất mạng thì giữ số cũ kèm giờ |
| Tin tức | Tiêu đề và hình từ RSS của VnExpress, Tuổi Trẻ, Thanh Niên, Dân Trí, VietnamNet (TV tiếng Anh: BBC, NPR, Guardian, Al Jazeera). Thêm tối đa 5 link RSS riêng từ điện thoại. Bấm OK mở bản đọc chữ to |
| Thời tiết | Bấm OK: bản đồ Windy toàn màn hình tại thành phố của bạn (mưa, gió, nhiệt độ, vệ tinh, PM2.5), mỗi lớp một nút, **Xem diễn biến** chạy dự báo; Back để đóng |
| Mở gần đây | 4-6 app vừa mở; Trái / Phải chọn, OK mở |
| Chặn quảng cáo | OK bật / tắt DNS chặn quảng cáo (AdGuard…). Không chặn quảng cáo trong video YouTube. DNS không chạy thì ~10 giây sau tự trả về |
| Giờ cầu nguyện | 5 buổi và giờ mặt trời mọc hôm nay tại nơi widget Thời tiết dùng, buổi sắp tới kèm đếm ngược, ngày Hijri. Tính ngay trên TV, không cần mạng. Chọn cách tính, giờ Asr, chỉnh ngày Hijri ±1 |

### Cài đặt từng widget

1. Tab **TV** → **Tùy chỉnh TV Home** → **Widget**, bật widget cần dùng.
2. Bấm **⚙** trên widget: hộp cài đặt riêng của widget, có xem trước thật và dải bố cục viền widget này. Đổi tới đâu TV áp dụng tới đó.

> 💡 Có trên app TV Home và trang web. Nguồn số liệu nằm trong danh sách ký trên trang web nên đổi nguồn không cần cập nhật app.

> 💡 Trong ⚙ của widget có kiểu **Tonal** (widget ấm, cùng kiểu thời tiết, hệ thống), cho mọi widget trừ đồng hồ.

## Widget QR và Quản lý từ điện thoại trên TV Home

Mã QR to ngay màn hình chính, quét từ ghế sofa: Wi-Fi cho khách, link thực đơn, số điện thoại. Tối đa 4 widget.

1. Tab **TV** → **Tùy chỉnh TV Home** → **Widget** → **Thêm mã QR**.
2. Chọn mẫu: Wi-Fi, Link, Văn bản, Gọi điện, Tin nhắn, E-mail hay Danh bạ, rồi điền ô. Thêm chú thích dưới mã nếu muốn.
3. Lưu. Widget hiện trên TV ngay; thứ tự, cỡ và tắt/bật nằm chung danh sách widget.

### Mẹo

- Mật khẩu Wi-Fi chỉ nằm trên TV; bật **Ghi cả mật khẩu thành chữ** nếu khách không quét được.
- Mã dài quá thì ô báo số byte, rút gọn lại.
- **Quản lý từ điện thoại** (cuối danh sách Tùy chỉnh): TV hiện một mã QR, quét bằng camera điện thoại là mở trang cài đặt của TV Home.
- Không cần app TV Remote, không cần mã ghép đôi. Mã dùng một lần, phiên 60 phút, tự đóng sau 10 phút không dùng.
- Đóng mã QR trên TV để xem thử: điện thoại vẫn đang cài tiếp được; chỉ mã cũ hết dùng.
- Phiên hết hạn: mở lại **Quản lý từ điện thoại** trên TV, quét mã mới.
- Ô app trên lưới (kể cả lối tắt ghim) gỡ được bằng **Bỏ khỏi màn hình chính**: xem [Thêm và gỡ ô](#home-add).

## Live widget trong lưới app của TV Home

Live widget là một ô trong lưới app có số liệu thật, bấm là làm việc luôn. Do TV Setup và TV Channels cung cấp.

1. Tab **TV** → **Tùy chỉnh TV Home** → **Thêm vào màn hình chính**.
2. Mục **Live widget**: bấm một live widget để đưa lên lưới app, bấm lại để gỡ.

| Live widget | Bấm thì |
| --- | --- |
| Dọn RAM (TV Setup) | Đóng app chạy nền, hiện RAM trước / sau; dưới 16 MB là "không còn gì để dọn" |
| Hẹn giờ tắt (TV Setup) | Mỗi lần bấm thêm 15 phút, tới 120 phút thì tắt |
| Xem tiếp kênh (TV Channels) | Logo, kênh đang xem, chương trình tiếp theo; bấm để mở danh sách kênh |

### Mẹo

- Không thấy live widget nào: cài hoặc cập nhật TV Setup (live widget Xem tiếp kênh cần thêm TV Channels), cùng một nguồn cài.
- Live widget ghi "TV Setup khác nguồn cài" nghĩa là hai app ký bằng khóa khác nhau.
- Số liệu tự cập nhật vài giây một lần khi TV Home đang hiện.
- Danh sách **Thêm vào màn hình chính** còn có lối tắt đã ghim (VTV1...): xem [Thêm và gỡ ô](#home-add). Nhãn "Trực tiếp" nay là "Widget".
- Chưa có TV Setup: lưới có ô **Cài TV Setup**, bấm để cài (hoặc **Không gợi ý nữa**).

## Thêm và gỡ ô trên màn hình chính

Một bảng để thêm, chia nhóm: app chưa có trên lưới, ghim kênh / video, live widget, lối tắt, hàng Xem tiếp. App không chọn ở lần chạy đầu nằm ở đây.

1. Trên lưới, bấm ô **＋ Thêm** (hoặc tab TV → Tùy chỉnh → **Thêm vào màn hình chính**).
2. Lối tắt đã ghim (VTV1...) hiện trong danh sách có dấu tích: bấm để gỡ hoặc ghim lại. Mục vừa thêm nằm cuối lưới, đang chọn sẵn để di chuyển ngay.
3. Gỡ: giữ OK trên ô → **Trên màn hình chính** → **Bỏ khỏi màn hình chính**. Lối tắt gỡ là gỡ hẳn, kể cả ghim của hệ thống.

- Gỡ cài đặt một app (như TV Channels) thì lối tắt và live widget của nó tự rời lưới, dock, thứ tự và bảng chọn, cả khi gỡ lúc TV Home không chạy.
- Hàng **Xem tiếp** là phim xem dở ở app khác, cuộn ngang. Giữ OK trên một ô: **Vị trí hàng**, **Ghim vào lưới**, hoặc **Không hiện mục của** app đó.
- Hiện lại mục của app: giữ OK trên app > **Trên màn hình chính**, hoặc **＋ Thêm**.
- Giữ OK trên bất kỳ phần tử nào (thời tiết, nút trên cùng, ＋ Thêm) mở menu nhỏ của nó, đổi là thấy ngay. **Sửa màn hình chính** cho chọn cả đồng hồ và widget; Back để thoát.
- Lối tắt ghim cũng có mục **Di chuyển**, chung thứ tự với app.

## Thư mục trên lưới và thứ tự hàng Xem tiếp

Gom vài app vào một ô thư mục (hiện 4 biểu tượng nhỏ và tên). Sửa được ngay trên TV hoặc ở tab TV → Tùy chỉnh → Thư mục.

1. Giữ OK trên một app → **Trên màn hình chính** → **Cho vào thư mục**: chọn thư mục có sẵn hoặc **Thư mục mới** rồi đặt tên.
2. OK trên thư mục mở một bảng các app của nó; Back đóng. Mỗi app trong đó vẫn có menu riêng, có **Đưa ra khỏi thư mục**, chuyển sang thư mục khác, lên trước / xuống sau.
3. Giữ OK trên ô thư mục để đổi tên, di chuyển trên lưới hoặc xóa (app trong đó về lại lưới). Thư mục trống tự biến mất.

- Khóa PIN và giới hạn giờ của app vẫn áp dụng bên trong thư mục.
- Hàng Xem tiếp: giữ OK trên một mục → **Thứ tự hàng** → **Mới xem nhất trước** (mặc định: theo từng app).

## Ghim link lên TV Home

Dán link web, YouTube hoặc link app: xem TV mở bằng app nào, rồi ghim thành ô trên lưới hoặc thử mở ngay.

1. Tab TV → thẻ Màn hình chính → **📌 Ghim link**, dán link, xem thẻ xem trước.
2. Bấm **Ghim lên TV** hoặc **Thử trên TV**.

- Trên TV: **＋ Thêm → Thêm link**.
- Trong YouTube, bấm Chia sẻ → TV Remote để chọn Ghim hay Mở.
- Remote → Mở trên TV → Mở link: link nào cũng hỏi Ghim hay Thử.
- TV dùng bản YouTube chỉnh sửa (SmartTube, TizenTube…) vẫn mở đúng app.

## Chủ đề TV Home

Một chủ đề đổi cả màn hình chính trong một lần bấm: hình nền, widget, kiểu widget, số cột và hàng, live widget.

1. Trên TV: ⚙ → **Tùy chỉnh TV Home** → **Chủ đề**. Điện thoại, máy tính: tab **TV** → **Tùy chỉnh TV Home** → **Chủ đề**.
2. Mỗi ô là màn hình chính thu nhỏ: nền thật, chỗ widget, lưới app. Chọn một ô là TV đổi ngay.
3. Thích giao diện đang có: **Lưu giao diện đang có thành chủ đề**, đặt tên. Ảnh, video hay trang web của bạn được chụp nhỏ làm ảnh xem trước.
4. Chia sẻ: bấm ⓘ trên một chủ đề → **Xuất chủ đề** ra tệp `.tvtheme.json`; **Nhập chủ đề** đọc lại tệp đó (hộp **Kèm ảnh/video** mang theo ảnh, video riêng, tối đa 60 MB mỗi mục).
5. Gửi cho mọi người: ⓘ trên chủ đề của bạn → **Chia sẻ lên cửa hàng**, nhập tên, **Gửi đi**. Duyệt xong mới hiện ở nhóm **Cộng đồng**; 3 chủ đề một ngày.

| Có sẵn | Cho ai |
| --- | --- |
| Hiện đại, Buổi sáng, Ban ngày | Đồng hồ, thời tiết, lịch âm lớn |
| Ruộng bậc thang, Hoàng hôn biển, Đêm thành phố | Ảnh hoặc video nền từ mạng |
| Game thủ, Bóng đá, Rạp phim | Kèm live widget hợp (Dọn RAM, Xem tiếp kênh) |
| Bé vui, Dễ nhìn, Đi ngủ | Chữ to, ít thứ, hẹn giờ tắt |

### Mẹo

- Đổi chủ đề khi giao diện chưa lưu: nó được giữ ở ô **Giao diện trước**, đổi lại được.
- Giữ OK trên một chủ đề (web: nút ⓘ) để xem nó chỉnh những gì.
- Chủ đề có sẵn được cập nhật từ trang web, không cần cài lại TV Home.

## Bầu trời theo giờ và màn OLED

Hai tùy chọn cho nền của TV Home, tính trên TV, không cần mạng.

1. Chọn nền **Bầu trời theo giờ** (Tùy chỉnh → Hình nền): trời đổi từ đêm sang bình minh, ban ngày, hoàng hôn theo giờ mặt trời mọc, lặn.
2. Nơi tính lấy từ widget Thời tiết, không có thì theo múi giờ. Tính ngay trên TV, không cần mạng, vài phút vẽ lại một lần.
3. Màn OLED: chọn nền **Đen thuần (OLED)**, rồi bật **Nhích nhẹ màn hình mỗi phút** (Tùy chỉnh → Giao diện). Mặc định tắt.
4. Cả màn hình chính dịch 1 dp mỗi phút trong ô vuông 6 dp, không thấy giật; hình nền đứng yên.

## Cài đặt nhanh (TV Home)

Nút Cài đặt trên thanh trên mở bảng cùng kiểu Tùy chỉnh: Mạng (tên Wi-Fi), Nguồn vào (hoặc Bluetooth nếu TV không có nguồn vào), Hẹn giờ tắt, Khoá trẻ em, Cài đặt TV, Tùy chỉnh.

Khoá trẻ em: chạm để bật; bật rồi thì chạm (hỏi mã PIN) để chọn app hoặc tắt. Cần TV Setup. Nút tròn: Dọn RAM, Tắt TV (Màn chờ, Tắt màn hình, Khởi động lại, Tắt hẳn).

**Độ sáng** (biểu tượng mặt trời): OK để chỉnh, Trái / Phải mỗi lần 10 %, Back là xong; 100 % = không phủ.

Nút **Màu lớp phủ** cạnh đó: Tối, Lọc ánh sáng xanh, Vàng, Nâu cổ, Đỏ đêm, Xanh. Cần TV Setup mới.

Tùy chỉnh → Giao diện → **Chỉ hiện cổng HDMI/AV đang cắm** (mặc định tắt): cổng trống nằm trong Nguồn vào khác.

Cài đặt TV vẫn mở được trên TV giấu màn Cài đặt chuẩn (máy chiếu HY300, Xiaomi, TCL, Fire TV).

## Giữ OK trên app ở TV Home

Giữ OK trên một app mở menu của riêng app đó, chia nhóm. Back trên remote về menu chính.

| Nhóm | Có gì |
| --- | --- |
| Trên màn hình chính | Dời, đổi tên, ẩn, gỡ khỏi lưới; Sửa màn hình chính |
| Khởi động & phím | Tự mở khi bật TV; gán phím số hoặc phím màu |
| Khóa & giới hạn | Khóa app, giới hạn mỗi ngày 15-120 phút; hỏi mã PIN phụ huynh (tạo ngay trên TV, 4 số) |
| Bảo trì | Dừng, xóa bộ nhớ đệm, thông tin app, gỡ cài đặt |

### Mẹo

- Gán phím: trang vẽ như remote, mỗi phím hiện app đang giữ nó. Bấm thẳng phím số hoặc phím màu trên remote là gán ngay.
- Đổi tên: phân biệt hai app trùng tên (như hai YouTube). Tên hiện khi chọn ô.
- Chỉ cài TV Home (chưa có TV Setup): khóa vẫn dùng được, TV Home tự giữ mã PIN và danh sách app khóa. Giới hạn mỗi ngày cần TV Setup để đếm giờ.
- Khóa của TV Home chặn mở app từ TV Home (lưới, dock, phím gán). Nút app trên remote, Trợ lý hay màn hình gần đây thì KHÔNG bị chặn: cần TV Setup.
- Cài TV Setup sau đó: lần đầu vào Khóa & giới hạn với đúng mã PIN, các khóa tự chuyển sang TV Setup.
- Quên mã PIN: chỉ TV Home thì vào Cài đặt → Ứng dụng → TV Home → Xóa dữ liệu (mất cả cài đặt TV Home).
- Có TV Setup: đổi mã từ app điện thoại hoặc máy tính, không cần mã cũ.
- Sai 5 lần liên tiếp thì chờ 1, 5, 15, rồi 60 phút.
- Khóa thật cho trẻ: khóa luôn app Cài đặt (chế độ trẻ em), không thì trẻ gỡ được TV Setup từ Cài đặt. TV Setup tự hỏi mã PIN khi mở nó hoặc màn Quản lý của nó.

## Phím trên remote: gán app, sửa phím chết, giữ để đổi

Phím màu, phím số và nút đã hết tác dụng có thể mở app bạn chọn. Cần [TV Home](#home); muốn chạy ở mọi app thì cần thêm TV Setup.

1. Trên TV: biểu tượng **⚙** góc trên → **Cài đặt nhanh** → **Tùy chỉnh** → **Phím & khởi động** → **Phím nhanh**. Điện thoại, máy tính: cùng tên, trong thẻ **Phím & khởi động**.
2. Chọn ô của một phím, rồi chọn app, lối tắt hoặc live widget. Lần đầu chọn, phím tự bật. Ngoài TV Home, TV hiện "TV Setup đã mở…" để biết không phải TV lỗi.
3. Muốn một app tự mở khi bật TV: chọn nó ở ô **Khi bật TV**. Chỉ mở một lần mỗi lần bật, và không mở nếu TV đã vào app khác.

| Phím | Gán được khi nào |
| --- | --- |
| Đỏ, xanh lá, vàng, xanh dương | Luôn luôn: không app nào dùng chúng |
| 0-9 | Ở TV Home: bấm. App khác: giữ 2 giây (cần TV Setup) |
| YouTube, Netflix, Prime Video | Luôn luôn: gán app khác thì nút mở app đó thay app gốc (cần TV Setup) |
| TV, Input, PatchWall, Tất cả app, Cài đặt | Chỉ khi nút đã chết. Nút còn chạy thì không bị lấy |

> 💡 Ô mờ ghi "remote không có": remote này không có phím đó. Chưa cài TV Setup thì phím chỉ chạy khi đang ở TV Home.

> 💡 Nút chết, giữ phím và app phụ TV Setup Keys: xem [Sửa phím chết, giữ để đổi](#keyfix).

### Mẹo

- Phím nhanh dùng chung bàn phím này: xem [Phím nhanh](#home-quickkeys).

## Phím nhanh của TV Home

Phím nhanh mở app ngay từ màn hình chính bằng một phím số hoặc phím màu trên remote.

1. Tab **TV** → **Tùy chỉnh TV Home** → **Phím nhanh**.
2. Bàn phím giống "Gán phím điều khiển": 1-9, 0, 4 phím màu, rồi ô **Khi bật TV** và các phím remote không có. Bấm một ô để chọn app.
3. Ô đã gán hiện banner hoặc icon app to. Bấm đúng phím số hoặc màu trên remote thật thì ô tương ứng được chọn.

- Ô **Khi bật TV**: chọn app = tự mở, xóa = tắt. Nút **Tự mở … khi bật TV**: mở app đó (trống: TV Home) dù TV tự vào HDMI hay app khác, trừ lúc cast.
- Live widget cũng gán được và hiện icon riêng.

## Sửa phím chết, giữ để đổi

Cần TV Setup trên TV và ADB đang bật. Các việc dưới đây tự chạy sau khi bật.

| Việc | Cách làm |
| --- | --- |
| 🔧 Phím chết | Tắt launcher gốc thì Input, YouTube, Netflix, Prime hay Tất cả app có thể không còn tác dụng. TV Setup tự trả việc cũ cho chúng. |
| Việc cũ là gì | Input mở danh sách nguồn vào; YouTube, Netflix, Prime mở app đó; Cài đặt mở Cài đặt; Tất cả app mở TV Home khi không app nào trả lời. Tắt ở [Tối ưu TV](#optimize). |
| ✋ Giữ 2 giây | Bật "Giữ 2 giây mới đổi nguồn vào / mở Cài đặt" ở tab Thiết lập, thẻ Nút remote. Bấm nhanh Input hay Cài đặt thì không làm gì. |
| Từ điện thoại | Bấm từ [remote trên điện thoại](#remote) vẫn đổi ngay, không cần giữ. |
| ⌨️ Thử phím remote | Màn hình trên TV hiện từng phím bấm: tên, mã, remote gửi, nhấn hay giữ, có bị đổi không. Mở: Tối ưu TV, Phím nhanh của TV Home, hoặc trang web. Thoát: giữ Back 2 giây. |
| 🧩 TV Setup Keys | App phụ nhỏ giữ phần bắt phím. TV Setup tự cài nó qua ADB khi cần: có phím cần sửa, hoặc bật giữ phím. |
| Vì sao tách riêng | Để Google Play Protect không chặn TV Setup khi bạn cài bằng Downloader. |

> 💡 Nút lạ của vài đời TV (vd. Xiaomi MiTV-MOOQ0: nút Nguồn vào) được sửa theo danh sách trên site, không cần cập nhật app. Remote của bạn có nút lạ: gửi mã hỗ trợ.

## TV Channels, kênh truyền hình miễn phí

App trên TV phát kênh từ danh sách công khai của iptv-org và các kênh miễn phí của VieON (VTV, HTV, đài tỉnh). Không tài khoản.

1. Tab **TV** → thẻ **Kênh TV** → **Cài TV Channels**.
2. Gõ tên kênh rồi bấm **Mở**, hoặc nói “mở kênh VTV1”.
3. Sắp yêu thích và nhóm trên điện thoại hoặc máy tính: thẻ **Kênh TV** → **Quản lý kênh**. Lưu ngay, TV cập nhật ngay. Cần TV Channels mới.
4. **Xuất file** / **Nhập file** chép yêu thích và nhóm sang TV khác: gộp hoặc thay thế. Ô chọn quốc gia có tìm kiếm và số kênh của từng nước.
5. Link m3u của bạn (tối đa 5): trên TV, TV Channels → **Thêm link m3u**, hoặc **Quản lý kênh** → **🔗 Link của bạn**.
6. Trên TV: ▲▼ đổi kênh, OK hiện thông tin, OK nữa mở danh sách, ▶ hiện chương trình đang chiếu, ▶ nữa mở lịch. Giữ OK trên một kênh: lịch phát sóng, thêm vào nhóm, ghim ra màn hình chính.

| Trên TV | Cách dùng |
| --- | --- |
| 🔍 **Tìm kênh** | Dòng đầu bên trái, OK rồi gõ tên. Kênh của nước đang chọn hiện trước, rồi “Ở nước khác”. Lần đầu tải danh sách khoảng 3 MB. |
| 🔔 **Nhắc chương trình** | Trong lịch phát sóng, OK trên chương trình sắp chiếu (OK lần nữa để bỏ). 2 phút trước giờ chiếu hiện thẻ ở góc: OK xem, Back bỏ qua. |
| 🔔 **Trên app khác** | Cần quyền “hiển thị trên ứng dụng khác”: app điện thoại / máy tính tự bật khi dùng thẻ Kênh TV. Màn hình TV tắt thì không nhắc. |

Phát một link luồng (`.m3u8`, `.mp4`): Remote → Thêm phím → **Mở trên TV**, dán link.

Kênh không phát được thì app tự thử nguồn khác; hết nguồn thì bấm OK thử lại. Một số kênh chỉ xem được trong nước của kênh.

Có bản mới, app tự hỏi: **Cập nhật ngay**, **Để sau** hoặc **Bỏ qua bản này**.

Chỉ có remote của TV: cài bằng Downloader, mã **8135635**. Link trực tiếp: [tv-channels.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-channels.apk).

### Mẹo

## TV Games, game trên TV

App trên TV chơi game retro và game web, dùng điện thoại làm tay cầm (tối đa 4). Chơi không cần ADB hay TV Setup: quét mã QR trên TV bằng điện thoại.

Chỉ có remote của TV: cài bằng Downloader, mã **2294968**. Link trực tiếp: [tv-games.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-games.apk).

1. Tab **TV** → thẻ **Game trên TV** → **Cài TV Games** (cần ADB, chỉ để cài). Chưa có trong bản phát hành mới nhất thì thẻ báo “có trong bản phát hành tới”.
2. Bấm **Mở TV Games**, rồi quét QR trên TV bằng điện thoại để làm tay cầm.
3. Game bài và cờ (Tiến lên, Bài màu, Cờ tỷ phú…): mỗi người cầm bài trên điện thoại, chạm hoặc vuốt chọn lá rồi bấm nút. Ghế trống thì máy chơi.
4. Chơi một mình bằng remote TV: OK là nút A. Start, Select, B: **Back → Gán nút remote**, rồi bấm nút muốn dùng (nút màu, ⏯…). Hoặc nút **🎮 Tay cầm** trên remote.
5. Mời ai ra khỏi phòng chờ: ▲ chọn người, rồi OK.
6. Game nghiêng điện thoại (Bóng bay, Bida, Máy bay giấy…): bấm **Bật nghiêng**. Trình duyệt cảnh báo vì chuyển sang https: bấm **Nâng cao** rồi **Tiếp tục** (iPhone thêm **Cho phép**). Ghế vẫn giữ.
7. Game web bị treo hay mới cập nhật? Trên TV giữ OK ở game web → **Mở bản mới nhất**; trên điện thoại menu ⚙ → **Tải lại game**. Các điện thoại vẫn giữ kết nối.

### Mẹo

## TV Apps, kho app cho TV

Danh sách app miễn phí, mã nguồn mở cho TV (SmartTube, Kodi, VLC, Jellyfin…). Mỗi app tải thẳng từ bản phát hành của nhà làm app, và chỉ được cài khi đúng chữ ký của họ.

Chỉ có remote của TV: cài bằng Downloader, mã **1082201**. Link trực tiếp: [tv-apps.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-apps.apk).

1. Từ điện thoại hay máy tính: tab **📱 Ứng dụng** → thẻ **📦 Kho app** → **Xem app** → **Cài** (cần ADB).
2. Trên TV: mở app **TV Apps** (TV Setup tự cài khi có ADB, hoặc ở hàng app TV của TV Setup). Chọn loại, chọn app, bấm OK. Giữ OK: Mở, Cài lại, Chi tiết.

- Có TV Setup: cài luôn, TV không hỏi. Không có: Android hỏi trước, và lần đầu cần cho phép TV Apps cài app (app chỉ cách).
- Tiện ích thêm (add-on của Stremio, Kodi) không bao giờ được cài thay bạn; trang chi tiết chỉ ghi tên.

## Tệp và cài APK

Tab **📁 Tệp** là trình quản lý tệp của TV.

| Việc | Cách làm |
| --- | --- |
| Xem thư mục | Bấm vào thư mục. Bấm **＋ Thư mục** để tạo mới. |
| Đưa tệp lên TV | Chọn tệp, hoặc kéo thả vào trang. |
| Xem trên TV | Bấm **▶ TV** cạnh tệp. |
| Cài APK đã có trên TV | Bấm **Cài APK này lên TV**. |
| Tải về máy bạn · Đổi tên · Xóa | Trong menu **⋯**. |
| Tải link về TV | **⬇️ Tải file về TV**, dán link. |
| Cài APK từ link hoặc tệp | **📦 Cài APK**. |
| Gõ lệnh | **⌨️ Terminal** (cho người rành). Mỗi lệnh và kết quả có nút chép riêng, cộng **Chép tất cả** và **Xóa**. |

> 💡 Việc gửi lâu có thanh tiến độ và nút **Dừng**.

### Mẹo

- APK trùng app hệ thống của TV: bấm **Cài thành app riêng**, file đổi tên gói (.copy) rồi cài cạnh app có sẵn. Không dùng cho.apks /.xapk.

## Sức khỏe TV

Tab **🩺 Sức khỏe** cho biết TV đang khỏe hay mệt.

| Tab con | Cho thấy |
| --- | --- |
| Hiện tại | Tổng quan (kèm kết quả bảo trì ban đêm 2-5 giờ: dọn cache, tối ưu app hay dùng), tải hệ thống, nhiệt độ, mạng, Bluetooth, phần cứng. |
| Lịch sử | Biểu đồ theo thời gian, và app nào hay bị đóng đột ngột. |
| Giấc ngủ | TV bật hay tắt lúc nào. |
| Khả năng | TV hỗ trợ gì. |

> 💡 Lịch sử trống (“Chưa có dữ liệu”) trên TV không cho ADB đọc thư mục của TV Setup, như TCL Android 11? Cập nhật TV Setup bản mới nhất: app sẽ hiện nút **Cập nhật**.

Mạng chậm? Bấm **⚡ Đo tốc độ** ở thẻ **📶 Mạng**: TV tự đo độ trễ, tải xuống, tải lên của chính nó (khoảng 15 giây) và cho biết đủ xem phim 4K, Full HD hay HD.

TV đầy bộ nhớ? Bấm **🧹 Giải phóng RAM**, hoặc **⏹ Dừng** một app đang chạy trong danh sách.

## Kiểm tra quyền riêng tư

Mở **Sức khỏe → Riêng tư**, chọn **Kiểm tra**. App đọc nền trên TV, nhẹ, không làm chậm app khác; app hiện dần khi tìm thấy.

Mỗi app ghi điều đã THẤY (micro dùng 12 phút trước, có quyền camera, đọc thông báo, nhận diện nội dung…), không khẳng định app đang nghe lén.

- **Cần chú ý**: dùng micro / camera trong 24 giờ, nhận diện nội dung (ACR), trợ năng. **Có quyền nhạy cảm**: chỉ giữ quyền hoặc dùng cũ. **Ít đáng ngại**: gập sẵn.
- **Thu hồi quyền** gỡ quyền micro, camera, vị trí của app (có **Cấp lại**); **Tắt app** dùng chung danh sách hoàn tác với Tối ưu TV. App TV cần giữ thì không có nút.
- Mỗi app có menu **⋯** như tab Ứng dụng. App TV cần (Trợ lý Google, bàn phím) không bị tắt, nhưng thu được từng quyền (vd micro) trong **Chi tiết & quyền**: có hỏi trước, cho lại được.

## Thống kê ẩn danh

Mỗi ngày một lần, mỗi app gửi một ghi chú nhỏ để biết TV nào chạy tốt. Dùng cho mục "TV đã chạy thử" trên trang chủ.

| Gửi | Không gửi |
| --- | --- |
| Hãng, model, chip, bản Android của TV; bản của app; ADB / hình / Tối ưu TV có chạy được không; một mã ngẫu nhiên | Địa chỉ IP, số serial, tên TV, app đã cài, tài khoản |

1. Tắt: **Cài đặt → Gửi thống kê ẩn danh** (trang web, app máy tính, TV) hoặc **Cài đặt → Quyền riêng tư** (app điện thoại).

> 💡 Tắt thì không gửi gì cả. Bật sẵn. Lỗi app, ADB hỏng, bản cập nhật bị gỡ cũng gửi theo, đã xóa IP và đường dẫn. TV Home, Kênh TV, Game TV theo công tắc của TV Setup.

## Quản lý ngay trên TV

Không có điện thoại hay máy tính ở gần? Làm ngay trên TV bằng điều khiển của TV.

1. Mở **TV Setup** trên TV.
2. Chọn **Quản lý trên TV**. TV hỏi tiếp: quét mã QR để quản lý trên điện thoại (gõ dễ hơn), hoặc chọn lại **Quản lý trên TV**.
3. Có các tab **Ứng dụng**, **Tệp**, **Sức khỏe**, **Tự động**, **Thiết lập**. Dùng mũi tên và OK để chọn. Chuột (Air Mouse, chuột USB) bấm và cuộn được khắp màn hình.
4. Bấm **Quay lại** để đóng hay thoát.

Trong TV Setup còn có: **Tối ưu TV** / **Hoàn tác**, **Tailscale**, **Cập nhật**, bật tắt remote qua web, và **Hủy ghép** mọi thiết bị.

> 💡 Dùng được cả khi bạn đã tắt remote qua web.

## TV Setup tự sửa DNS và ADB

TV Setup canh hai chuyện hay làm TV mất kết nối, và tự sửa.

| Chuyện gì | TV Setup làm |
| --- | --- |
| Bộ lọc DNS (thẻ **🛡️ Lọc DNS**) ngừng hoạt động: YouTube báo không kết nối được dù TV vẫn có mạng | Kiểm tra định kỳ và khi mạng đổi. Nếu tên miền hỏng mà địa chỉ vẫn trả lời, nó **tắt bộ lọc** và báo lại ở trang Quản lý. |
| TV tự tắt **Gỡ lỗi ADB** | Bật lại ADB (đã được cấp quyền một lần qua ADB). |

> 💡 Bạn không cần làm gì. Muốn lọc DNS lại, bật lại ở tab Thiết lập (cần Android 9 trở lên).

## Giữ kết nối khi tắt TV

Tắt TV xong, TV có thể ngủ sâu và không trả lời nữa. Tùy chọn này giữ chip thức để bạn vẫn nối được.

1. Vào tab **🩺 Sức khỏe → Giấc ngủ**.
2. Tích **Giữ kết nối khi tắt TV**.
3. Bỏ tích thì TV lại ngủ sâu như cũ.

> 💡 Tốn điện hơn một chút. Cần TV Setup 0.7.1 trở lên. Không có trên TV TCL, vì TV này tự ngủ sâu dù app giữ thế nào.

## Gửi thông báo lên TV

1. Thêm phím → **Tiện ích** → **Thông báo** (🔔 Gửi thông báo lên TV).
2. Gõ **tiêu đề** (không bắt buộc) và **lời nhắn**.
3. Chọn góc ↖ ↗ ↙ ↘ và số giây: 3, 5, 10 hoặc 30.
4. Bấm **Gửi**. Một thẻ nhỏ hiện lên TV rồi tự mất.

> 💡 Cần TV Setup bản mới. Nếu thiếu, app sẽ hỏi cập nhật bằng một nút.

## Vẽ lên TV

1. Bấm **✏️** cạnh hình TV (hoặc Thêm phím → Vẽ lên TV).
2. Chọn màu, rồi vẽ ngón tay lên hình. **↩** hoàn tác nét vừa vẽ.
3. Thêm **💬 Chữ** hoặc **🖼️ Ảnh**. Với ảnh, chỉnh to nhỏ bằng − / + rồi bấm **✓ Đưa lên TV**.
4. Xong bấm **✓ Xong**. Muốn xóa: **🗑 Xóa hết**.

> 💡 Nét vẽ nằm trên mọi app và tự mất sau 10 phút. Cần TV Setup.

## Đưa ảnh lên TV

Có hai cách: đặt một ảnh **phủ lên màn hình TV** (trên mọi app), hoặc **phát** ảnh hay video từ máy.

1. Bấm **✏️** cạnh hình TV, rồi **🖼️ Ảnh**.
2. Chọn ảnh trong máy.
3. Chỉnh to nhỏ bằng **−** / **+**. Đổi ý thì bấm **Hủy**.
4. Bấm **✓ Đưa lên TV**.

Muốn phát hẳn: **Thêm phím → Mở trên TV → Phát ảnh / video từ điện thoại** (trên máy tính: từ máy này).

> 💡 Ảnh phủ tự mất sau 10 phút, hoặc bấm **🗑 Xóa hết**. Cần TV Setup (thiếu thì app hỏi cập nhật).

## Chụp màn hình TV

1. Bấm **Thêm phím** trên tab Remote.
2. Chọn **Chụp màn hình**.
3. Ảnh lưu về máy bạn, tên như `tv-1729512345.png`.

> 💡 Ứng dụng có bản quyền có thể chụp ra màn hình đen.

## Tắt màn hình, giảm độ sáng

**Tắt màn hình** nằm trong **Thêm phím → Hẹn giờ tắt**; **Giảm độ sáng** trong **Thêm phím → Tiện ích**, và trên TV ở **Bảng điều khiển của TV Home** (thanh mặt trời, nút màu kế bên):

| Mục | Làm gì |
| --- | --- |
| Tắt / bật màn hình | Màn hình đen, tiếng vẫn chạy, app vẫn điều khiển được. Bật lại: bấm lần nữa, hoặc bấm remote của TV. |
| Giảm độ sáng | Phủ màu lên mọi app: Tối, Lọc xanh, Vàng, Nâu cổ, Đỏ đêm, Xanh, độ mạnh 5 đến 90%. Giữ tới khi bấm Tắt. |

> 💡 Giảm độ sáng tối thật trên màn OLED; màn LCD vẫn sáng đèn nền nhưng màu ấm vẫn bớt ánh sáng xanh. Cần TV Setup.

## Hẹn giờ tắt TV

1. Bấm **Thêm phím → Hẹn giờ tắt**, hoặc vào tab **⏰ Tự động → 😴 Hẹn giờ tắt TV**.
2. Chọn 30 phút, 1 giờ hay 2 giờ. Mốc khác (15p, 45p, 1g30) và **Tự chọn…** (số phút hay giờ như 23:30) nằm trong **Thời gian khác**.
3. Muốn nghe tiếp nhạc: **Tắt / bật màn hình**. Phím đầu tiên trên remote của TV chỉ bật lại màn hình, không mở app đang chọn.
4. Hủy bằng **Hủy hẹn giờ**. Cần thêm giờ: **15 phút nữa**.

> 💡 Hẹn giờ nằm trên TV, nên vẫn chạy khi điện thoại đã tắt.

## Giờ đi ngủ

1. Tab **⏰ Tự động → 🌙 Giờ đi ngủ**.
2. Bấm **Thêm giờ đi ngủ**, chọn giờ bắt đầu và kết thúc.
3. Lưu. Trong khoảng đó, nếu ai bật TV, TV báo trước rồi tự tắt sau 1 phút.

> 💡 Cần TV Setup trên TV. Việc tắt do chính TV làm.

## Màn hình chào

1. Tab **⏰ Tự động → 🏠 Màn hình chào**, bấm **Chọn video hoặc ảnh** (mp4, webm, mkv, jpg, png, webp; tối đa 200 MB).
2. Chọn **Mỗi lần bật TV** hoặc **Sau khi khởi động lại**; với ảnh, chọn số giây. Bấm **Xem thử trên TV** để xem ngay.
3. Mỗi lần bật TV, màn hình chào hiện lên. Bấm phím bất kỳ để tắt. Nhiều TV: **📦 Nhiều TV cùng lúc → ⋯ → Đặt màn hình chào**.

> 💡 Cần TV Setup mới (0.12.0 trở lên). Không hiện lại trong 2 phút. Việc hiện do chính TV làm.

## Giới hạn app, âm lượng, RAM

1. Tab **⏰ Tự động → ⏳ Giới hạn app mỗi ngày**.
2. Bấm **Thêm giới hạn app**, chọn app và số phút mỗi ngày (30 phút đến 4 giờ).
3. Hết giờ, TV báo trước rồi đóng app đó.
4. **+30p** cho app thêm 30 phút hôm nay; **Đặt lại** bỏ phần cho thêm.

| Thẻ | Làm gì |
| --- | --- |
| 🔊 Giới hạn âm lượng | Theo giờ, tối đa 10 đến 90%, cho loa TV. Không chạy nếu dùng loa HDMI ngoài, hay khi đang nghe tiếng TV qua 🎧 (loa TV im khi đó). |
| 🧹 Báo khi RAM đầy | Báo khi RAM đạt 80 đến 95%. Mặc định tắt. |

> 💡 Tất cả nằm trên TV và cần TV Setup. TV ngủ hay máy bạn tắt vẫn có hiệu lực.

## Khóa app bằng mã PIN

1. Tab **⏰ Tự động → 🔒 Khóa app bằng mã PIN**, bấm **＋ Thêm**.
2. Chọn app. Lần đầu thì đặt thêm mã PIN 4 đến 8 chữ số.
3. Mở app đó trên TV: TV hỏi PIN. Nhập bằng phím số của remote hoặc bàn phím trên màn hình.

Đã mở khóa thì dùng tới khi sang app khác hoặc TV tắt; mở lại là hỏi lại. Sai 5 lần: chờ 1 phút.

> 💡 Cần TV Setup. Mã PIN chỉ lưu trên TV (dạng băm), không gửi đi đâu.

1. **🛡️ Chế độ trẻ em** (cùng tab Tự động): bật thì mọi app đều hỏi mã trừ vài app bạn cho phép; Cài đặt TV luôn khóa.
2. Chọn cách mở khóa: PIN, **Google Authenticator** (quét QR, nhập mã 6 số; đồng hồ TV phải đúng, trang báo nếu lệch) hoặc hỏi bố mẹ qua Telegram.
3. Lưu 8 **mã dự phòng** khi thêm Authenticator (mỗi mã dùng 1 lần). Nghi trẻ biết PIN: đổi PIN, hoặc tắt PIN khi đã có Authenticator.
4. **Mở khóa 30 phút / 1 giờ / 2 giờ / Hết hôm nay**, **Khóa lại ngay**, và thẻ **📺 Tổng giờ xem TV** mỗi ngày (+30p khi cần).

> 💡 TV Home → **Cài đặt nhanh** có ô **Khoá trẻ em**; tắt nó thì TV hỏi mã PIN.

## Lịch mở app theo ngày và giờ

Ví dụ: YouTube chỉ mở từ thứ Hai đến thứ Sáu, 16:00 đến 18:00, tối đa 30 phút. Ngoài giờ app bị đóng ngay và TV hiện thông báo.

1. Tab **⏰ Tự động → 📅 Lịch mở app**, bấm **＋ Thêm**.
2. Chọn app, các ngày được mở, giờ bắt đầu và kết thúc. Muốn giới hạn phút trong giờ đó thì chọn **Tối đa**. Muốn khóa hẳn app thì bấm **Chặn hẳn**.
3. Giờ khác nhau theo ngày (T2–T6 16:00–18:00, T7 và CN 08:00–22:00)? Bấm **＋ Thêm khung giờ**, mỗi khung có ngày và giờ riêng, bỏ bằng **✕**. Tối đa 8 khung; nằm trong một khung là mở được.

Giờ qua nửa đêm (22:00 đến 02:00) tính cho ngày bắt đầu.

Trong giờ không hỏi PIN, trừ app đã khóa bằng [mã PIN](#lock). Cho thêm phút hôm nay: **＋30 phút** ở dòng của app.

> 💡 Cần TV Setup bản mới (nhiều khung giờ cần bản mới hơn). Lịch chạy ngay trên TV, không cần điện thoại bật. Không chặn được riêng YouTube Shorts: chỉ chặn được cả app.

## Cảnh (một nút làm nhiều việc)

Một cảnh là chuỗi việc chạy một lần bấm. Ví dụ “Xem phim”: bật TV, mở Netflix, đặt âm lượng 20.

1. Tab **⏰ Tự động → 🎬 Cảnh → ＋ Tạo cảnh**.
2. Đặt tên và biểu tượng.
3. Bấm **＋ Thêm bước** và chọn loại bước (bảng dưới).
4. Bấm **▶ Thử** để chạy thử, **💾 Lưu** để giữ.

| Bước | Làm gì |
| --- | --- |
| Bật TV · Tắt TV | Nguồn. |
| Mở app · Mở link | Chạy app hay link trên TV. |
| Đặt âm lượng · Bấm phím | Đặt mức hay bấm phím. |
| Chuyển ngõ vào | HDMI hay nguồn khác. |
| Chờ | Đợi vài giây giữa hai bước. |
| Hẹn giờ tắt · Gửi thông báo | Như các tính năng riêng. |

Chạy cảnh từ **Thêm phím → Cảnh**, bằng giọng nói (nói tên cảnh), hoặc widget, ô Cài đặt nhanh, phím tắt trên điện thoại.

**📌 Thêm vào TV Home** (trong cảnh): cảnh thành một ô trên TV Home, cũng có trong ＋ Thêm → **Cảnh & lối tắt**. Cần TV Setup mới.

Sao lưu: **⬇️ Xuất file sao lưu** và **⬆️ Nhập từ file**.

## Điều khiển TV bằng Telegram

Nhắn bot của riêng bạn để tắt/bật TV, chỉnh tiếng, mở app, chụp màn hình. Bot chạy trong TV Setup, không mở cổng ra Internet.

1. Trong Telegram, mở **@BotFather**, gửi `/newbot`, đặt tên và username kết thúc bằng `bot`.
2. BotFather trả về một **token** (`123456789:AAH…`). Ai có token là điều khiển được bot: đừng chia sẻ.
3. 🛠️ Cài đặt trên TV hoặc trang web của TV → **Điều khiển bằng Telegram** → **Cho dùng**, dán token, **Lưu**.
4. Bấm **Tạo mã ghép đôi**: mã 6 số hiện trong Cài đặt và trên màn hình TV, dùng một lần, 5 phút.
5. Mở bot (chat riêng hoặc nhóm), gửi `/pair 123456`. Chat lạ gửi gì bot cũng im lặng.
6. Sai 5 lần: mã bị huỷ và khoá ghép đôi 15 phút; tạo mã mới để mở khoá.

Lệnh:

```
/status  /off  /on  /vol 30  /key HOME
/open netflix  /scene Xem phim  /timer 30 (0 = huỷ/cancel)
/say Ăn cơm nhé  /shot  /remote  /help
```

Chat mới ghép chỉ có quyền **Chỉ remote**; chủ TV nâng lên **Toàn quyền** trong danh sách chat.

Ảnh chụp màn hình đi qua máy chủ Telegram. Dừng bất cứ lúc nào: **Không bật**, **Xoá token** (quên luôn chat) hoặc xoá từng chat.

## Telegram: báo tin và giờ ngủ

Thêm lệnh cho bot, và TV tự nhắn khi có việc. Cần đã ghép đôi một chat (xem bài trước).

```
/apps  /usage  /now  /scenes
/channel VTV3  /type xin chào
/bedtime  /limits  /schedule  /alerts
```

1. **/apps** hiện app dùng nhiều nhất hôm nay; **/usage** cho 5 app dùng lâu nhất và tổng giờ màn hình.
2. **/now** cho biết đang phát gì, kèm nút chụp màn hình. **/channel** mở kênh trong TV Channels.
3. **/bedtime**, **/limits**, **/schedule** cho xem luật. Chat **Toàn quyền** bật/tắt giờ ngủ, chặn hoặc mở app; mỗi thay đổi có nút **Hoàn tác**.
4. **/alerts** chọn tin TV tự gửi: bật/tắt TV, app bị đóng, giờ ngủ, hẹn giờ tắt, thiết bị mới ghép. Tối đa một tin mỗi loại mỗi phút.

Trong **🛠️ Cài đặt** bạn thấy trạng thái bot, mã QR để ghép đôi bằng một lần quét, từng chat với quyền và loại tin, và lịch sử 7 ngày. Nội dung /type và /say không được lưu.

## Home Assistant · Phím tắt (Siri)

Cho iPhone Phím tắt, Siri hay Home Assistant điều khiển TV bằng một đường link.

1. **App máy tính** hoặc **trang web của TV** (xem “[iPhone](#web-iphone)”) → nút **🛠️ Cài đặt** ở trên. App điện thoại không có.
2. Thẻ **Home Assistant · Phím tắt** → **Cho dùng**.
3. Bấm **📋 Sao chép** đường link.

Link có dạng:

```
http://192.168.1.20:8765/api/hook?k=3f9a1c7e5b2d4a60b8c1d9e7f2a4b6c8&key=HOME
```

Địa chỉ TV và cổng, `k=` là mã bí mật, `key=HOME` là việc cần làm. Mở link trong trình duyệt để thử.

**iPhone: Phím tắt và Siri**

1. Gửi link sang iPhone (Ghi chú, AirDrop), hoặc chép từ trang web của TV mở trong Safari.
2. App **Phím tắt** → **+** → **Thêm hành động**.
3. Chọn **Lấy nội dung của URL**, dán link vào ô **URL** (giữ **GET**).
4. Đặt tên (vd “TV Home”), bấm **Xong**.
5. Nói **“Hey Siri, tên phím tắt”** hoặc bấm phím tắt. iPhone cùng Wi-Fi với TV; lần đầu chọn **Cho phép** mạng cục bộ.

**Home Assistant**

1. Bấm **📋 YAML cho Home Assistant**.
2. Mở `configuration.yaml` (add-on File editor), dán vào cuối tệp, lưu: ``` rest_command:   tv_key:     url: "http://192.168.1.20:8765/api/hook"     method: post     headers:       x-tvr-hook: "3f9a1c7e5b2d4a60b8c1d9e7f2a4b6c8"     content_type: "application/json"     payload: '{"key": "{{ key }}"}' ```
3. Khởi động lại (**Công cụ nhà phát triển → YAML → Khởi động lại**).
4. Dùng trong script hay automation. Bấm Home: ``` action: rest_command.tv_key data:   key: HOME ```
5. Mở app / chạy cảnh: chép khối `tv_key` thành `tv_app`, đổi `payload` thành `'{"app": "{{ app }}"}'` hoặc `'{"scene": "{{ scene }}"}'`.

Đổi đuôi link để làm việc khác (khoảng trắng viết `%20`):

| Thêm vào link | TV làm |
| --- | --- |
| `key=HOME` | Bấm một phím (HOME, OK, BACK, MUTE…). |
| `app=com.netflix.ninja` | Mở app theo tên gói. |
| `scene=Xem phim` | Chạy một cảnh. |
| `text=xin chào` | Gõ chữ. |
| `volume=20` / `up` / `down` / `mute` | Đặt âm lượng (0 đến 100) hoặc tăng, giảm, tắt tiếng. |
| `power=on` / `off` / `toggle` | Bật, tắt hay đổi trạng thái. |
| `/api/hook/state` | Chỉ đọc: TV đang bật không, đang chạy gì. |

Home Assistant gửi mã qua header `x-tvr-hook`, không lộ trong log.

| Chạy ở đâu | Cổng |
| --- | --- |
| TV Setup (nên dùng, vì TV luôn bật) | 8765 |
| App máy tính (chỉ mở khi bật) | 8766 |

> ⚠️ Link là chìa khóa. Lộ thì bấm **🔄 Đổi mã mới**, link cũ chết ngay.

> 💡 Chỉ trong mạng nhà; ở xa dùng Tailscale.

### Mẹo

## Nhiều TV cùng lúc

1. Lưu từng TV: nối vào TV một lần, nó nằm trong **💾 TV đã lưu**.
2. Đổi TV bằng **📺 Đổi TV**.
3. Cần làm cho nhiều TV: mở **📦 Nhiều TV cùng lúc**, tích các TV rồi chọn **📦 Cài APK** hoặc **🔁 Khởi động lại**.

> 💡 App điện thoại có danh sách TV với cùng các nút.

> 💡 **🧹 Trả phòng** (nhà nghỉ): xoá dữ liệu app đã chọn, đặt âm lượng, đăng xuất trình duyệt đã ghép, về Home. Có hỏi trước.

## Xem tình trạng mọi TV

Mỗi TV đã thử có một dòng: **Đang bật**, **Chế độ chờ** hoặc **Không thấy TV**, kèm kiểu máy và app đang mở. Tự làm mới 10 giây một lần.

**Xem màn hình** (mặc định tắt): ảnh nhỏ của từng TV, 5 giây một lần. LG, Samsung, Roku và TV không ADB không có ảnh.

## Chia sẻ cho người khác

1. Thêm phím → **Tiện ích** → **📲 Chia sẻ**.
2. Chọn **📺 Chia sẻ TV**: hiện mã QR dùng một lần, hết hạn sau 10 phút.
3. Người kia (cùng Wi-Fi) quét QR là dùng được remote.
4. Muốn họ có app: chọn **📱 Chia sẻ app**.

| Quyền | Được làm |
| --- | --- |
| 🛡️ Toàn quyền | Mọi thứ, kể cả Quản lý. |
| 🎮 Chỉ remote | Phím, gõ chữ, hình, mở app, hẹn giờ, chạy cảnh, kênh TV. Không có Quản lý. |
| 👁️ Chỉ xem | Xem hình và đang phát gì. |

> 💡 Đổi hay thu hồi quyền ở **Thiết lập → 📱 Thiết bị đã ghép** (Hủy ghép hoặc Gỡ tất cả).

## Điều khiển khi không ở nhà (Tailscale)

Tailscale là mạng riêng miễn phí nối các thiết bị của bạn. Không mở cổng ra Internet.

1. Tab **📺 Thiết lập → 🌐 Tailscale**, bấm **⚡ Cài và đăng nhập Tailscale**.
2. Chờ khoảng nửa phút, bấm **🔑 Mở trang đăng nhập Tailscale** và chọn Connect.
3. Trong trang quản trị Tailscale, **Disable key expiry** cho TV để khỏi mất kết nối.
4. Cài Tailscale trên máy bạn, đăng nhập cùng tài khoản.
5. Kết nối tới địa chỉ `100.x.x.x` của TV.

TV đang ngủ nhẹ thì phím Nguồn đánh thức được, cả qua Tailscale. TV tắt hẳn thì phải Wake-on-LAN từ trong nhà.

## TV LG, Samsung, Roku, VIDAA

Thử nghiệm (beta). Chỉ có trong app điện thoại và app máy tính.

1. Bật TV và nối cùng Wi-Fi.
2. Quét mạng, chọn TV.
3. TV hỏi cho phép thì chọn Cho phép (VIDAA hỏi mã 4 số).

|  | LG | Samsung | Roku | VIDAA |
| --- | --- | --- | --- | --- |
| Phím, gõ chữ, mở app, bật TV, cảnh | ✓ | ✓ | ✓ | ✓ |
| Chuyển ngõ vào | ✓ |  | ✓ | ✓ |
| Âm lượng theo số | ✓ |  |  | ✓ |
| Link YouTube · tắt màn hình | ✓ |  |  |  |
| Air Mouse (con trỏ) | ✓ |  |  |  |

> 💡 Xem hình, tệp, quản lý app chỉ có ở Android TV. TV lạ cư xử sai: Giới thiệu → **📋 Gửi nhật ký**.

> 💡 Sony BRAVIA, Xiaomi, TCL… chạy Android TV, nên dùng như mọi TV Android (không phải bảng trên). Đổi màn hình chính trên các TV này: [Thư viện màn hình chính](#launchers).

## Tắt theo dõi trên TV Samsung, LG, Roku, VIDAA

Các TV này không cho app tắt hộ: bấm remote một lần. Tên menu đổi theo đời TV: không thấy đúng chữ thì tìm trong **Quyền riêng tư**.

Trong app điện thoại hay máy tính: **Thêm** › **Tiện ích** › **Tắt theo dõi** mở Cài đặt và hiện các đường dẫn này.

|  | Theo dõi | Quảng cáo | Micro |
| --- | --- | --- | --- |
| Samsung | Cài đặt › Tất cả cài đặt › Chung & Quyền riêng tư › Điều khoản & Quyền riêng tư › **Viewing Information Services**: Tắt. | Cùng chỗ đó: tắt **Quảng cáo theo sở thích** (Interest-Based Advertisements), bật **Không theo dõi**. | Giọng nói › Bixby › **Đánh thức bằng giọng nói**: Tắt. |
| LG | Tất cả cài đặt › Chung › Hệ thống › Cài đặt bổ sung › **Live Plus**: Tắt. | Thỏa thuận người dùng: bỏ **quảng cáo theo sở thích** và **Who.Where.What?**; Cài đặt bổ sung › Cài đặt Home: tắt **Home Promotion** và **Content Recommendation**. | Tắt **Hands-free Voice** ("Hi LG"). |
| Roku | Home › Settings › Privacy › Smart TV Experience › **Automatic Content Recognition**: Tắt. Không thấy menu: my.roku.com › Privacy Settings. Que Roku không có mục này. | Settings › Privacy › Advertising › **Personalize ads**: Tắt. | Settings › Privacy › Microphone: chặn từng kênh. |
| Hisense VIDAA | Cài đặt › (Hệ thống ›) Cài đặt nâng cao › Enhanced Viewing › **Automatic Content Recognition**: Tắt, rồi từ chối các bảng hỏi. | Cùng chỗ đó: tắt **Personalized Ads** và **Content Recommendation**. | Hệ thống › Voice Service › **Hands-Free**: Tắt. |

- **Theo dõi nội dung** (ACR): TV chụp mẫu hình và tiếng của mọi thứ đang chiếu, kể cả HDMI, để bán quảng cáo. Tắt không ảnh hưởng xem TV.
- Đời cũ: Samsung 2019–2021 ở Cài đặt › Hỗ trợ › Điều khoản & Quyền riêng tư; LG 2025 ở Hỗ trợ › Quyền riêng tư & Điều khoản › Thỏa thuận người dùng › **Thông tin xem**.
- Chặn thêm ở mạng: đặt DNS trong Cài đặt mạng của TV là `94.140.14.14` (AdGuard DNS), hoặc dùng Pi-hole / NextDNS với danh sách [HaGeZi](https://github.com/hagezi/dns-blocklists) riêng cho Samsung, LG, Roku.
- Đừng từ chối các điều khoản bắt buộc: TV sẽ mất app.
- TV Android / Google TV: [Kiểm tra quyền riêng tư](#privacy) và [Tối ưu TV](#optimize) làm tự động.

Đường dẫn menu theo [cleanyour.tv](https://cleanyour.tv/not-android).

## Chỉ có trên app điện thoại

| Tính năng | Cách dùng |
| --- | --- |
| Phím âm lượng của điện thoại | Chỉnh âm lượng TV. |
| Widget · ô Cài đặt nhanh · phím tắt | Chạm là bấm phím hay chạy cảnh, không cần mở app. |
| 🎧 Nghe | Vẫn nghe khi tắt màn hình. |
| Phản chiếu màn hình điện thoại lên TV | Mở trên TV → Chiếu màn hình điện thoại lên TV. |
| Tay cầm | Xem [Dùng tay cầm](#gamepad). |

## Cài đặt của app

1. Bấm **🛠️** ở góc trên để mở **Cài đặt**.
2. **Giao diện**: theo máy, sáng hoặc tối. **Ngôn ngữ**: Tiếng Việt, English hoặc Русский.
3. **Khi bấm nút** (điện thoại): rung hoặc không rung. **Bàn phím của TV**: có [tự mở ô gõ](#typing) khi TV chọn ô chữ hay không.
4. **App trên máy tính**: **🔄 Kiểm tra bản mới** và **Tắt** (dừng chương trình, TV không bị ảnh hưởng).
5. Cuối trang: [Home Assistant · Phím tắt](#hook), và mục Giới thiệu có [Gửi nhật ký](#log).

### Mẹo

- **Gửi thống kê ẩn danh** (bật sẵn): xem [Thống kê ẩn danh](#census). Trên app điện thoại nằm ở Quyền riêng tư.

## Tự kiểm tra khi không kết nối được

Không kết nối được? App tự kiểm tra trong chưa đầy 4 giây rồi nói **một kết luận kèm cách sửa**. Bạn không cần đoán.

| App nói | Cách sửa |
| --- | --- |
| TV không trả lời | TV tắt hẳn hoặc ngủ sâu. Bật TV rồi bấm **🔄 Thử lại**. |
| TV đã đổi địa chỉ | Bấm nút **📍 Dùng địa chỉ …**. |
| TV này chỉ có Gỡ lỗi không dây | Ghép một lần bằng mã 6 số: Tùy chọn nhà phát triển → Gỡ lỗi không dây → Ghép nối bằng mã. |
| Gỡ lỗi ADB đang tắt trên TV | Phím vẫn bấm được. Muốn xem hình, mở app thì bấm **🧭 Bật ADB giúp tôi** hoặc bật trong Tùy chọn nhà phát triển. |
| TV đang chờ bạn cho phép | Nhìn lên TV: tích “Luôn cho phép”, chọn OK. |
| Máy này đang ở mạng khác với TV | Nối vào đúng Wi-Fi của TV, không phải mạng khách hay 4G. |
| Máy này chưa vào Wi-Fi | Bật Wi-Fi. |
| Tailscale đang tắt trên máy này | Bật Tailscale rồi thử lại. |
| VPN đang chặn đường tới TV | Trong app VPN bật cho phép mạng LAN (Local network), hoặc tắt VPN. |
| Ngày giờ trên TV đang sai | Trên TV: Cài đặt → Hệ thống → Ngày và giờ, bật tự động. Đồng hồ sai làm kết nối bảo mật bị từ chối. |
| TV đang bật mạng nhưng chưa nhận lệnh | TV ngủ nhẹ. Bấm nút nguồn trên điều khiển thật, chờ vài giây. |
| Fire TV chưa hoàn tất cài đặt ban đầu | Nút Home và Alexa không bấm được. Bấm **Sửa** để đánh dấu đã cài đặt xong. |

Các nút trong hộp: **🔄 Thử lại**, **🔍 Quét mạng**, **🧭 Bật ADB giúp tôi**, **Sửa** (chỉ Fire TV chưa cài đặt xong). Mở danh sách **Đã kiểm tra** để xem app đã thử những gì.

> 💡 Hộp này hiện ở app máy tính, màn hình Quản lý của app điện thoại và danh sách TV của app điện thoại.

> 💡 Box của nhà mạng (FPT, Viettel) khóa Tùy chọn nhà phát triển bằng mật khẩu: app nói thẳng là nhà mạng khóa, hỏi tổng đài hoặc người bán box.

## “Cần cập nhật trình duyệt”

Màn hình Quản lý trong app điện thoại dùng trình duyệt của Android (WebView). Nếu nó quá cũ, app hiện thông báo này.

1. Bấm **Mở Play Store** ngay trong thông báo.
2. Cập nhật **Android System WebView** (và Chrome nếu có).
3. Mở lại app.

## Gửi nhật ký để được giúp

1. Mở **Cài đặt (🛠️) → Giới thiệu**.
2. Bấm **📋 Gửi nhật ký**. Nhật ký được chép.
3. App chép nhật ký và mở [trang gửi nhật ký](https://tvremote.pages.dev/log): dán vào, viết vài dòng, rồi gửi qua GitHub issue hoặc nhắn Facebook cho tác giả.

> 💡 Nhật ký có lệnh đã gửi, câu trả lời của TV và lỗi. Không có khóa ghép, địa chỉ MAC hay chữ bạn đã gõ.

> 💡 App Android bị sập thì lần mở sau hiện “Lần trước app bị lỗi” kèm tên lỗi và vài dòng đầu. Bấm **Sao chép** hoặc **Chia sẻ**, rồi dán vào trang gửi nhật ký.

> 💡 Lỗi trong hộp “Có lỗi xảy ra” cũng được gửi ẩn danh (đã xóa IP, đường dẫn, khóa), nếu công tắc **Gửi thống kê ẩn danh** bật.

> 💡 TV không ai với tới được (box nhà mạng, không có ADB): trên TV mở **TV Setup → Kết nối → Gửi cho hỗ trợ**, đọc mã 8 số cho tác giả.

> 💡 **Cho hỗ trợ xem 30 phút** chỉ cho đọc trạng thái, không bấm hay đổi gì; dừng được bất cứ lúc nào.

> 💡 Mở cho TV từ điện thoại hay máy tính: tab **Thiết lập**, thẻ TV, **👁️ Cho hỗ trợ xem TV này**. Cho xem chính app điện thoại hay máy tính: **Giới thiệu**.

## Lỗi thường gặp

| Gặp | Thử |
| --- | --- |
| Không kết nối được | Xem [Tự kiểm tra](#selfcheck). Cùng Wi-Fi? TV bật? |
| Phím không phản ứng | [Bật lại ADB](#prep-tv), hoặc khởi động lại TV. |
| Trang web của TV hỏi mã lại | Ghép lại một lần; đừng dùng cửa sổ riêng tư. |
| Hình giật | Giảm cỡ hình và bitrate ([Chất lượng hình](#quality)). |
| Tailscale không ra link | Chờ thêm rồi bấm lại **🔑** ([Tailscale](#away)). |
| Play Protect cảnh báo | Chọn “Vẫn cài”. Mã nguồn công khai. |
| Fire TV: nút Home / Alexa chết, các phím khác vẫn chạy | Cài đặt ban đầu chưa xong: app tự báo ở đầu trang, bấm **Sửa** (đặt `user_setup_complete` = 1). |
| Cài đặt của TV đứng ở trang Chuyển văn bản thành giọng nói | Chạy lệnh `pm clear com.google.android.tts` (mục Terminal, hoặc `adb shell`); giọng đọc tải lại ở lần dùng đầu. |
| Trang web của TV không hiện mã ghép | TV không có ADB nên chưa tự hiện được: mở app TV Setup trên TV, mã ở màn hình chính (2 phút). Xem [Mở trang web của TV](#web-iphone). |
