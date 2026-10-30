# Hướng dẫn TV Remote (cho trợ lý AI)

Điều khiển Android TV / Google TV (và LG, Samsung, Roku, VIDAA) không qua máy chủ nào: app điện thoại, app máy tính, trang web của TV và các app trên TV. Tìm mục dưới đây rồi chỉ đọc trang của mục đó. Bản cho người đọc: https://tvremote.pages.dev/guide

## Bắt đầu
- [Dùng cho ai](uses.md): Tìm dòng giống bạn, bấm từng mục để xem cách làm. Đa số cần TV Setup trên TV. _(dung cho ai doi tuong gia dinh tre em homestay khach san phong cho thue quan ca phe cua hang ong ba nguoi gia mat remote use cases who families hotels cafe kids grandparents)_
- [Cài app điện thoại (Android)](install-phone.md): Cách dễ nhất: chưa cần cài gì lên TV, bấm phím được ngay (xem). Bật ADB để có thêm hình TV, app, tệp. _(tv remote apk dien thoai android)_
- [Cài app máy tính](install-pc.md): Tải bản cho máy bạn (link bên dưới) rồi giải nén. _(mac windows linux zip tar webkit unverified developer)_
- [Bấm phím khi TV chưa mở ADB](nokeys.md): Chưa bật ADB vẫn điều khiển được. App điện thoại và app máy tính bấm phím bằng giao thức Android TV Remote của Google, nên nhiều TV dùng… _(atv remote protocol 6466 6467 khong adb ghep remote ma 6 ky tu bam nut khong an pairing)_
- [Cài app lên TV (TV Setup)](install-tv.md): App nhỏ trên TV: chạy trang web của TV, hẹn giờ tắt, giới hạn app và nhiều thứ khác. _(downloader 2397346 2973135 tv setup apk cai dat)_
- [Mở trang web của TV (iPhone, trình duyệt)](web-iphone.md): iPhone không có app. Thay vào đó, TV tự phát một trang web điều khiển. Mở bằng Safari hay trình duyệt nào cũng được. _(iphone safari ma ghep 6 so pairing code web cua tv ios)_
- [Remote hồng ngoại (không cần mạng)](ir.md): Điện thoại có đèn hồng ngoại (nhiều máy Xiaomi, Huawei, Samsung đời cũ) thì dùng được như remote giấy: không cần Wi-Fi, không cần chọn… _(hong ngoai ir remote infrared khong mang mat mang remote hong do ma fpt play box dien thoai den ir)_
- [Air Mouse](air.md): Cầm điện thoại như điều khiển, xoay cổ tay là con trỏ chạy trên TV, như Magic Remote của LG. Hợp với app không dùng được phím mũi tên… _(chuot bay air mouse con tro con quay hoi chuyen gyroscope nhap cuon magic remote lg)_
- [Bật ADB bằng tay (khi cần)](prep-tv.md): Không bắt buộc, và thường không cần làm: TV Setup tự mở chỗ bật. Chưa bật ADB thì app điện thoại và app máy tính vẫn bấm phím được. Bật… _(adb developer options tuy chon nha phat trien remote start dia chi ip fpt play mat khau password ban dung build box nha mang)_
- [Ghép qua Gỡ lỗi không dây (Android 11+)](wireless.md): Một số Google TV đời mới chỉ có “Gỡ lỗi không dây”, không có cổng ADB thường. App điện thoại, app máy tính và trang web của TV (cả… _(go loi khong day wireless debugging ghep noi ma ghep noi google tv doi moi canpair)_
- [Kết nối và “Cho phép gỡ lỗi USB?”](allow-usb.md): Lần đầu một máy nối vào TV, TV hỏi xem có tin máy đó không. Đây là bước an toàn của Android, chỉ làm một lần cho mỗi máy. _(allow usb debugging luon cho phep always allow nhin len tv ket noi)_

## Điều khiển
- [Điều khiển từ xa](remote.md): Tab 🎮 Remote giống điều khiển thật. Chạm là bấm. _(phim nguon power home back menu volume kenh channel more keys them phim)_
- [Gõ chữ lên TV](typing.md): Bấm ⌨️ Bàn phím ở tab Remote: ô gõ mở ngay dưới hàng phím đầu. _(ban phim keyboard go chu co dau tieng viet enter)_
- [Clipboard của TV (chữ có dấu, dán chữ)](clipboard.md): Clipboard là chỗ TV giữ chữ vừa sao chép. Dùng để xem chữ trên TV, lấy về máy, hoặc đưa chữ lên TV. _(clipboard dan chu copy sao chep dan vao o gõ chu co dau paste)_
- [Ra lệnh bằng giọng nói](voice.md): Ô Hỏi TV: mở YouTube (trong ⌨️ Bàn phím) hiểu câu nói thường: gõ rồi bấm 🔍, hoặc bấm Nói để nói. _(giong noi micro hoi tv mo youtube voice assistant)_
- [Nói nhanh (app điện thoại)](quick-talk.md): Nút Nói trên remote của app điện thoại: giữ, nói, buông là TV làm. _(noi nhanh quick talk giu de noi hold to talk giong noi nut noi dien thoai)_
- [Xem và chạm lên màn hình TV](picture.md): Bạn thấy hình TV ngay trên máy và chạm hay bấm chuột như đang chạm vào TV. _(pip thu nho noi touchpad cham man hinh xem hinh scrcpy chuot keo cuon watch fullscreen toan man hinh tv khong cho chieu)_
- [Nghe tiếng TV trên máy bạn](listen.md): Nghe TV bằng tai nghe của bạn để không làm phiền người khác. Cần TV Android 11 trở lên. _(nghe 🎧 tai nghe am thanh sound audio headphones opus)_
- [Chất lượng hình và “Nhịp hình”](quality.md): Hình giật hoặc mờ? Mở 🖥️ Chất lượng hình từ nút chất lượng cạnh ▶ Xem. Mọi thứ đặt sẵn đã ổn cho đa số TV. _(chat luong hinh nhip hinh bitrate khung giay bo ma hoa sharpest quality frame pace giat lag)_
- [Dùng tay cầm chơi game làm remote](gamepad.md): Cắm hoặc nối tay cầm (Xbox, PlayStation…) vào máy đang chạy app là bấm được TV. _(tay cam gamepad joystick xbox ps5 ps4 controller game pad do nhan)_

## Quản lý TV
- [Ứng dụng trên TV](apps.md): Tab 📱 Ứng dụng liệt kê app đã cài. Gõ tên vào ô tìm, hoặc lọc bằng chip: Tất cả, Đang mở, 🚀 Tự chạy, 🔒 Cần giữ, ⏸ Đã tắt. _(dang mo da nhiem recents ram ung dung app go uninstall tat disable don bo nho dem sao luu backup quyen permission)_
- [Tối ưu TV và Hoàn tác](optimize.md): TV chậm? Tối ưu giúp mở app nhanh hơn. Không gỡ gì cả, và Hoàn tác trả lại như cũ. _(toi uu tv home animation hieu ung hoan tac undo manh nhanh hon cham)_
- [Thư viện màn hình chính](launchers.md): Đổi màn hình chính của TV. Đứng đầu là TV Home, launcher của dự án. _(launcher man hinh chinh flauncher home thu vien sony bravia xiaomi patchwall android tv cu leanback tvlauncher setupwraith chromecast quen mang)_
- [TV Home, màn hình chính của dự án](home.md): Màn hình chính không quảng cáo, chỉnh từ điện thoại hoặc máy tính. Cài từ thư viện launcher. _(downloader 4356026 tv home launcher man hinh chinh khong quang cao hinh nen anh app yeu thich an app tre em dong ho kim analog clock widget lich am cpu ghi chu hen gio thoi tiet weather phu toi dim shade trang web web page tuy chinh customize giao dien look bo cuc layout he thong system man cho screensaver)_
- [Widget Thị trường, Tin tức, bản đồ thời tiết và ⚙](home-widgets.md): TV Home có thêm hai widget lấy số liệu thẳng từ nguồn, không qua máy chủ nào. Cần mạng. _(thi truong gia vang ty gia vietcombank bitcoin tin tuc rss thoi tiet ban do windy widget cai dat the market news weather map gear gan day recent chan quang cao adblock dns)_
- [Widget QR và Quản lý từ điện thoại trên TV Home](home-qr.md): Mã QR to ngay màn hình chính, quét từ ghế sofa: Wi-Fi cho khách, link thực đơn, số điện thoại. Tối đa 4 widget. _(qr ma qr wifi wi-fi mat khau khach thuc don link sms tin nhan dien thoai quet the card guest menu scan quan ly tu dien thoai manage from phone trang cai dat)_
- [Live widget trong lưới app của TV Home](home-tiles.md): Live widget là một ô trong lưới app có số liệu thật, bấm là làm việc luôn. Do TV Setup và TV Channels cung cấp. _(o song tile live widgets don ram clean ram free ram xem tiep kenh keep watching hen gio tat sleep timer them vao man hinh chinh add to home screen luoi app grid tv setup tv channels)_
- [Thêm và gỡ ô trên màn hình chính](home-add.md): Một bảng để thêm, chia nhóm: app chưa có trên lưới, ghim kênh / video, live widget, lối tắt, hàng Xem tiếp. App không chọn ở lần chạy đầu… _(them go o xoa khoi luoi remove from grid pin go ghim loi tat shortcut app go cai dat don o live widget)_
- [Thư mục trên lưới và thứ tự hàng Xem tiếp](home-folders.md): Gom vài app vào một ô thư mục (hiện 4 biểu tượng nhỏ và tên). Sửa được ngay trên TV hoặc ở tab TV → Tùy chỉnh → Thư mục. _(thu muc folder nhom app luoi gom xem tiep play next moi xem nhat latest first thu tu)_
- [Ghim link lên TV Home](home-pin.md): Dán link web, YouTube hoặc link app: xem TV mở bằng app nào, rồi ghim thành ô trên lưới hoặc thử mở ngay. _(youtube ghim pin kenh channel video playlist danh sach phat link chia se share mo tren tv open)_
- [Chủ đề TV Home](home-themes.md): Một chủ đề đổi cả màn hình chính trong một lần bấm: hình nền, widget, kiểu widget, số cột và hàng, live widget. _(chu de themes giao dien look preview xem truoc luu save ruong bac thang sapa video nen web)_
- [Cài đặt nhanh (TV Home)](home-control.md): Nút Cài đặt trên thanh trên mở bảng cùng kiểu Tùy chỉnh: Mạng (tên Wi-Fi), Nguồn vào (hoặc Bluetooth nếu TV không có nguồn vào), Hẹn giờ… _(trung tam dieu khien control center nhanh mang am thanh hen gio khoa tre em khoi dong lai don ram)_
- [Giữ OK trên app ở TV Home](home-app-menu.md): Giữ OK trên một app mở menu của riêng app đó, chia nhóm. Back trên remote về menu chính. _(giu ok long press menu app doi ten rename khoa lock gioi han limit pin gan phim assign key bao tri force stop cache go uninstall)_
- [Phím trên remote: gán app, sửa phím chết, giữ để đổi](keymap.md): Phím màu, phím số và nút đã hết tác dụng có thể mở app bạn chọn. Cần TV Home; muốn chạy ở mọi app thì cần thêm TV Setup. _(phim gan phim phim mau phim chet phim so giu phim nut remote khong an khong chay nguon vao input youtube netflix prime tat ca app cai dat khoi dong mo app khi bat tv tu mo app phim nhanh tv setup keys tro nang play protect keys key map color keys dead keys number keys hold key power on startup quick keys accessibility)_
- [Phím nhanh của TV Home](home-quickkeys.md): Phím nhanh mở app ngay từ màn hình chính bằng một phím số hoặc phím màu trên remote. _(phim nhanh quick keys ban phim keypad 1-9 phim mau khi bat tv power on gan phim)_
- [Sửa phím chết, giữ để đổi](keyfix.md): Cần TV Setup trên TV và ADB đang bật. Các việc dưới đây tự chạy sau khi bật. _(phim chet nut remote khong an khong chay nguon vao input youtube netflix prime tat ca app cai dat giu phim giu 2 giay bam nham tv setup keys tro nang play protect downloader chan app dead keys hold key accessibility add-on)_
- [TV Channels, kênh truyền hình miễn phí](channels.md): App trên TV phát kênh từ danh sách công khai của iptv-org và các kênh miễn phí của VieON (VTV, HTV, đài tỉnh). Không tài khoản. _(downloader 8135635 lich phat song epg guide kenh tv truyen hinh iptv iptv-org vtv htv xem tivi mien phi nhom yeu thich ghim quan ly kenh manage channels)_
- [TV Games, game trên TV](games.md): App trên TV chơi game retro và game web, dùng điện thoại làm tay cầm (tối đa 4). Chơi không cần ADB hay TV Setup: quét mã QR trên TV bằng… _(downloader 2294968 game tro choi retro libretro tay cam phone pad qr nes snes gba ps1 homebrew games)_
- [TV Apps, kho app cho TV](tvapps.md): Danh sách app miễn phí, mã nguồn mở cho TV (SmartTube, Kodi, VLC, Jellyfin…). Mỗi app tải thẳng từ bản phát hành của nhà làm app, và chỉ… _(downloader 1082201 kho app store cai app mien phi ma nguon mo open source smarttube kodi vlc jellyfin moonlight localsend tvapps)_
- [Tệp và cài APK](files.md): Tab 📁 Tệp là trình quản lý tệp của TV. _(tep file apk cai upload tai len keo tha terminal thu muc doi ten xoa)_
- [Sức khỏe TV](health.md): Tab 🩺 Sức khỏe cho biết TV đang khỏe hay mệt. _(suc khoe ram cpu nhiet do temperature mang bluetooth giai phong bo nho health toc do mang speed test)_
- [Kiểm tra quyền riêng tư](privacy.md): Mở Sức khỏe → Riêng tư, chọn Kiểm tra. App đọc nền trên TV, nhẹ, không làm chậm app khác; app hiện dần khi tìm thấy. _(quyen rieng tu privacy micro camera vi tri acr theo doi tracking nghe len)_
- [Thống kê ẩn danh](census.md): Mỗi ngày một lần, mỗi app gửi một ghi chú nhỏ để biết TV nào chạy tốt. Dùng cho mục "TV đã chạy thử" trên trang chủ. _(thong ke an danh census gui hang model tv chip ay tat telemetry anonymous stats privacy)_
- [Quản lý ngay trên TV](ontv.md): Không có điện thoại hay máy tính ở gần? Làm ngay trên TV bằng điều khiển của TV. _(quan ly tren tv khong co dien thoai may tinh remote cua tv back tv setup man hinh tv)_
- [TV Setup tự sửa DNS và ADB](tvguard.md): TV Setup canh hai chuyện hay làm TV mất kết nối, và tự sửa. _(dns loc dns keepadb adb tu bat lai tat loc dns cat mang youtube khong ket noi dns filter)_
- [Giữ kết nối khi tắt TV](awake.md): Tắt TV xong, TV có thể ngủ sâu và không trả lời nữa. Tùy chọn này giữ chip thức để bạn vẫn nối được. _(giu ket noi khi tat tv awake keepawake chip ngu sau tcl standby tailscale)_
- [Gửi thông báo lên TV](notify.md): Thêm phím → Tiện ích → Thông báo (🔔 Gửi thông báo lên TV). _(thong bao notification the nhan goc man hinh message card)_
- [Vẽ lên TV](draw.md): Bấm ✏️ cạnh hình TV (hoặc Thêm phím → Vẽ lên TV). _(ve len tv but pen anh chu text hinh draw)_
- [Đưa ảnh lên TV](photo.md): Có hai cách: đặt một ảnh phủ lên màn hình TV (trên mọi app), hoặc phát ảnh hay video từ máy. _(dua anh len tv hinh anh phu len man hinh pic phat anh video)_
- [Chụp màn hình TV](screenshot.md): Bấm Thêm phím trên tab Remote. _(chup man hinh screenshot anh png)_
- [Tắt màn hình, giảm độ sáng](screen-dim.md): Tắt màn hình nằm trong Thêm phím → Hẹn giờ tắt; Giảm độ sáng trong Thêm phím → Tiện ích, và trên TV ở Bảng điều khiển của TV Home (thanh… _(tat man hinh giam do sang loc anh sang xanh toi screen off dim night)_

## Tự động
- [Hẹn giờ tắt TV](sleep.md): Bấm Thêm phím → Hẹn giờ tắt, hoặc vào tab ⏰ Tự động → 😴 Hẹn giờ tắt TV. _(hen gio tat sleep timer tat man hinh van nghe)_
- [Giờ đi ngủ](bedtime.md): Tab ⏰ Tự động → 🌙 Giờ đi ngủ. _(gio di ngu bedtime tre em tat tv ban dem)_
- [Màn hình chào](welcome.md): Tab ⏰ Tự động → 🏠 Màn hình chào, bấm Chọn video hoặc ảnh (mp4, webm, mkv, jpg, png, webp; tối đa 200 MB). _(man hinh chao welcome video anh khach homestay khach san bat tv khoi dong)_
- [Giới hạn app, âm lượng, RAM](limits.md): Tab ⏰ Tự động → ⏳ Giới hạn app mỗi ngày. _(gioi han app moi ngay am luong ram day limit tre em)_
- [Khóa app bằng mã PIN](lock.md): Đã mở khóa thì dùng tới khi sang app khác hoặc TV tắt; mở lại là hỏi lại. Sai 5 lần: chờ 1 phút. _(tre em kids google authenticator totp ma du phong recovery tong gio xem khoa app ma pin tre em lock pin parental)_
- [Lịch mở app theo ngày và giờ](schedule.md): Ví dụ: YouTube chỉ mở từ thứ Hai đến thứ Sáu, 16:00 đến 18:00, tối đa 30 phút. Ngoài giờ app bị đóng ngay và TV hiện thông báo. _(lich mo app theo ngay gio thu hai den thu sau khung gio tre em chan app schedule weekday window parental block)_
- [Cảnh (một nút làm nhiều việc)](scenes.md): Một cảnh là chuỗi việc chạy một lần bấm. Ví dụ “Xem phim”: bật TV, mở Netflix, đặt âm lượng 20. _(canh scene lenh nhanh tu dong hoa kich ban)_
- [Điều khiển TV bằng Telegram](telegram.md): Nhắn bot của riêng bạn để tắt/bật TV, chỉnh tiếng, mở app, chụp màn hình. Bot chạy trong TV Setup, không mở cổng ra Internet. _(telegram bot botfather token pair ghep doi nhan tin chat nhom group lenh command shot remote)_
- [Telegram: báo tin và giờ ngủ](telegram-more.md): Thêm lệnh cho bot, và TV tự nhắn khi có việc. Cần đã ghép đôi một chat (xem bài trước). _(telegram bao tin alert thong bao gio ngu bedtime gioi han limit chan app qr ghep doi lich su history usage thoi gian man hinh scenes channel type)_
- [Home Assistant · Phím tắt (Siri)](hook.md): Cho iPhone Phím tắt, Siri hay Home Assistant điều khiển TV bằng một đường link. _(home assistant phim tat shortcuts siri hook rest_command yaml webhook ios tu dong hoa nha thong minh token)_

## Thêm nữa
- [Nhiều TV cùng lúc](many.md): Lưu từng TV: nối vào TV một lần, nó nằm trong 💾 TV đã lưu. _(nhieu tv fleet cai hang loat danh sach)_
- [Xem tình trạng mọi TV](tv-status.md): Mỗi TV đã thử có một dòng: Đang bật, Chế độ chờ hoặc Không thấy TV, kèm kiểu máy và app đang mở. Tự làm mới 10 giây một lần. _(tinh trang trang thai bat tat cho man hinh homestay nhieu tv status standby thumbnail)_
- [Chia sẻ cho người khác](share.md): Thêm phím → Tiện ích → 📲 Chia sẻ. _(chia se qr quyen scope toan quyen chi remote chi xem khach)_
- [Điều khiển khi không ở nhà (Tailscale)](away.md): Tailscale là mạng riêng miễn phí nối các thiết bị của bạn. Không mở cổng ra Internet. _(tailscale xa nha vpn 100 key expiry wake on lan dem)_
- [TV LG, Samsung, Roku, VIDAA](brands.md): Thử nghiệm (beta). Chỉ có trong app điện thoại và app máy tính. _(sony bravia xiaomi tcl android tv lg webos samsung tizen roku vidaa hang khac beta)_
- [Tắt theo dõi trên TV Samsung, LG, Roku, VIDAA](tracking-other.md): Các TV này không cho app tắt hộ: bấm remote một lần. Tên menu đổi theo đời TV: không thấy đúng chữ thì tìm trong Quyền riêng tư. _(theo doi acr quang cao quyen rieng tu samsung lg roku vidaa hisense viewing information live plus tat theo doi dns chan quang cao privacy tracking ads)_
- [Chỉ có trên app điện thoại](extras.md):  _(widget tile shortcut phim tat man hinh chinh am luong cap nhat)_

## Khi có lỗi
- [Cài đặt của app](settings.md): Bấm 🛠️ ở góc trên để mở Cài đặt. _(cai dat settings giao dien sang toi theme ngon ngu language rung vibrate kiem tra ban moi cap nhat tat thoat quit)_
- [Tự kiểm tra khi không kết nối được](selfcheck.md): Không kết nối được? App tự kiểm tra trong chưa đầy 4 giây rồi nói một kết luận kèm cách sửa. Bạn không cần đoán. _(tu kiem tra loi diagnose khong ket noi vpn dong ho clock mang khac thu lai quet mang bat adb)_
- [“Cần cập nhật trình duyệt”](webview.md): Màn hình Quản lý trong app điện thoại dùng trình duyệt của Android (WebView). Nếu nó quá cũ, app hiện thông báo này. _(webview play store cap nhat trinh duyet android system webview cu old)_
- [Gửi nhật ký để được giúp](log.md): Mở Cài đặt (🛠️) → Giới thiệu. _(nhat ky log gui loi bao loi wirelog ho tro support ma 8 so)_
- [Lỗi thường gặp](faq-fix.md):  _(loi thuong gap faq khong bam duoc ma ghep hoi lai giat play protect)_
