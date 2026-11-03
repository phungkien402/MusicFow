# Nâng Cấp Trích Xuất YouTube Đa Nguồn & Khắc Phục Vòng Đời Xoá Bài Hát

Kế hoạch toàn diện nhằm giải quyết triệt để 2 vấn đề: (1) Nhạc đang phát vẫn tiếp tục chạy khi bị xoá khỏi app; (2) Lỗi tải nhạc YouTube bị rơi vào giai điệu nhạc mẫu thay vì bài hát gốc do hệ thống chuyển đổi bị ngắt sớm hoặc thiếu nguồn dự phòng.

## User Review & Critical Decisions

> [!IMPORTANT]
> Dựa trên phản hồi xác nhận từ bạn trong phiên hỏi đáp (Phase 1):
> - **Nguyên nhân lỗi âm thanh tải về**: File phát ra nhạc mẫu 4 hợp âm tổng hợp thay vì bài hát gốc do bộ trích xuất mạng chỉ chờ tối đa 8 giây trước khi tự động kích hoạt bộ tạo sóng dự phòng (`SampleAudioGenerator`).
> - **Hướng xử lý đã chọn**: Nâng cấp bộ máy trích xuất YouTube đa nguồn dự phòng (Multi-Tier Resilient Architecture) kết hợp tăng thời gian chờ xử lý thông minh với thanh trạng thái tiến trình thực tế từ server.
> - **Xử lý khi xoá bài đang phát**: Khi xoá một bài hát đang phát khỏi ứng dụng (từ Thư viện, Danh sách tải về, hoặc Hàng đợi), ứng dụng phải ngắt phát bài hát đó ngay lập tức, giải phóng tài nguyên phát và cập nhật giao diện mà không để nhạc âm thầm chạy tiếp.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. **Đồng bộ vòng đời phát và xoá bài**: Khi người dùng nhấn xoá bài hát từ bất kỳ màn hình nào (Library, Downloads, Queue, Playlist), hệ thống so khớp bài hát đa tiêu chí (ID cơ sở dữ liệu, đường dẫn file vật lý, tiêu đề & nghệ sĩ). Nếu bài hát bị xoá trùng khớp với bài đang phát hoặc đang tạm dừng, dịch vụ phát nhạc `AudioPlayerManager` & `MusicPlaybackService` sẽ lập tức dừng phát, thu hồi Audio Focus, giải phóng bộ nhớ đệm MediaPlayer, ẩn MiniPlayer/NowPlayingSheet và đồng bộ lại danh sách phát.
  2. **Bộ máy trích xuất YouTube đa tầng dự phòng**: Nâng cấp `AudioLinkExtractor` từ cơ chế đơn nguồn mong manh thành hệ thống 4 tầng dự phòng (Multi-Source Fallback System):
     - **Tầng 1 (Primary High-Speed Converter)**: Kết nối API chuyển đổi MP3 chất lượng cao với chu kỳ thăm dò thích ứng (adaptive polling) lên tới 35–45 giây, phản ánh chính xác tỷ lệ hoàn thành từ máy chủ (10% -> 50% -> 90% -> 100%) thay vì hết hạn sau 8 giây.
     - **Tầng 2 (Redundant Server Mirrors)**: Dự phòng các máy chủ mirror chuyển đổi chuyên dụng khi máy chủ chính quá tải hoặc gặp lỗi mạng.
     - **Tầng 3 (Direct Stream Proxy & Media Parsers)**: Trích xuất trực tiếp luồng stream âm thanh gốc (M4A/WebM Opus) qua mạng lưới proxy âm thanh mã nguồn mở.
     - **Tầng 4 (Direct Stream Extraction)**: Trích xuất luồng trực tiếp với tiêu đề, nghệ sĩ và ảnh bìa chất lượng cao được lưu cục bộ.
- **Target Audience / Persona**: Người dùng yêu thích nghe nhạc offline và tải nhạc trực tiếp từ YouTube/TikTok mà không lo bị lỗi nhạc mẫu hay trục trặc phát nhạc ngầm.
- **Key Value**: Đảm bảo 100% bài hát tải về là âm thanh thực từ video gốc với chất lượng cao nhất, đồng thời trải nghiệm điều khiển phát nhạc luôn minh bạch, chính xác tuyệt đối khi quản lý dữ liệu.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Luồng tải nhạc YouTube đa nguồn an toàn**:
   ```
   [Người dùng dán link YouTube]
               │
               ▼
   [Phân tích siêu dữ liệu: Tiêu đề, Ca sĩ, Ảnh bìa HQ]
               │
               ▼
   [Người dùng chọn định dạng & chất lượng (320kbps MP3 / Hi-Fi)]
               │
               ▼
   [Bắt đầu tải: Hiển thị trạng thái chi tiết "Đang kết nối máy chủ...", 
    "Máy chủ đang nén audio 65%...", "Đang tải dữ liệu: 3.8 MB/s"]
               │
               ▼
   [Nếu Tầng 1 bận -> Tự động chuyển Tầng 2 mà không gián đoạn]
               │
               ▼
   [Hoàn tất: Lưu trữ vào bộ nhớ máy, phát ngay âm thanh gốc chân thực]
   ```

2. **Luồng xoá bài hát đang phát**:
   ```
   [Người dùng đang nghe Bài hát A]
               │
               ▼
   [Người dùng chọn menu 3 chấm -> Chọn "Xoá bài hát"]
               │
               ▼
   [Hộp thoại xác nhận xoá hiển thị -> Người dùng nhấn "Xoá"]
               │
               ▼
   [AudioPlayerManager phát hiện bài đang phát trùng với bài bị xoá]
               │
               ├──► Dừng ngay lập tức MediaPlayer & ngắt thông báo Service
               ├──► Giải phóng Audio Focus
               ├──► Loại bỏ bài hát khỏi PlayQueue
               └──► Cập nhật giao diện: Đóng NowPlayingSheet / ẩn MiniPlayer
   ```

### Visual Identity & Theme
- **Phong cách**: Cyberpunk Neon Dark Theme đồng bộ (Nền tối sâu `#0D0E15`, viền Neon Cyan `#00F5FF`, điểm nhấn Purple `#A855F7` và cảnh báo xoá Neon Pink `#FF007F`).
- **Giao diện Downloader**: Hiển thị thẻ tác vụ tải với thanh tiến trình mượt mà (LinearProgressIndicator) kèm nhãn trạng thái trực quan: hiển thị rõ ràng nguồn trích xuất ("YouTube Audio Engine v2") và tốc độ tải thực tế.

---

## 3. Key Product Decisions & Trade-Offs

- **Quyết định 1: Loại bỏ việc ngầm tạo nhạc mẫu giả lập khi trích xuất thất bại**
  - *Phương án chọn*: Tăng thời gian thăm dò chuyển đổi lên 35–45 giây với đa nguồn dự phòng. Nếu tất cả các nguồn trực tuyến đều không thể kết nối do đứt mạng, hiển thị thông báo lỗi rõ ràng kèm nút "Thử lại", tuyệt đối không âm thầm thay thế bài hát của người dùng bằng nhạc mẫu tổng hợp.
  - *Lý do*: Người dùng muốn nghe đúng bài hát gốc họ đã chọn tải, việc phát ra nhạc mẫu gây hiểu nhầm là lỗi âm thanh ứng dụng.
- **Quyết định 2: Cơ chế dừng phát triệt để khi xoá bài**
  - *Phương án chọn*: Kiểm tra đa tiêu chí (`id == deletedSong.id` HOẶC `filePath == deletedSong.filePath` HOẶC `(title == deletedSong.title && artist == deletedSong.artist)`). Nếu bài bị xoá là bài đang phát, thực hiện `stopPlaybackCompletely()` (hoặc chuyển bài tiếp theo một cách an toàn nếu người dùng còn danh sách chờ, nhưng dừng ngay lập tức luồng âm thanh bài cũ trước khi giải phóng file).
  - *Lý do*: File âm thanh sau khi bị xoá khỏi bộ nhớ máy sẽ không thể đọc được nữa; nếu MediaPlayer tiếp tục giữ handle file cũ đã giải phóng, hệ điều hành Android sẽ phát sinh lỗi hoặc tiếp tục chạy trong nền gây khó chịu cho người dùng.

---

## 4. Technical Architecture & Data Strategy

### Kiến trúc hệ thống và luồng xử lý

```
┌───────────────────────────────────────────────────────────────┐
│                       Giao diện (UI)                          │
│   LibraryScreen  │  DownloaderScreen  │   NowPlayingSheet     │
└──────────────┬──────────────────┬─────────────────┬───────────┘
               │                  │                 │
               ▼                  ▼                 ▼
┌───────────────────────────────────────────────────────────────┐
│                       MusicViewModel                          │
│   - deleteSong(song)                                          │
│   - startDownload(url, quality)                               │
└──────────────┬────────────────────────────────────┬───────────┘
               │                                    │
               ▼                                    ▼
┌──────────────────────────────┐   ┌────────────────────────────┐
│     AudioPlayerManager       │   │    AudioLinkExtractor      │
│  - onSongDeleted(song)       │   │  [Multi-Tier YouTube Core] │
│  - Multi-match verification  │   │  - Tier 1: Fast Converter  │
│  - stopPlaybackCompletely()  │   │  - Tier 2: Redundant Mirror│
│  - abandonAudioFocus()       │   │  - Tier 3: Direct Stream   │
│  - update MediaSession       │   │  - Adaptive Progress Poll  │
└──────────────┬───────────────┘   └─────────────┬──────────────┘
               │                                 │
               ▼                                 ▼
┌──────────────────────────────┐   ┌────────────────────────────┐
│     MusicPlaybackService     │   │     MusicRepository & DB   │
│   (Foreground Notification)  │   │   (Room Database & Files)  │
└──────────────────────────────┘   └────────────────────────────┘
```

### Chi tiết các thay đổi kỹ thuật
1. **`AudioPlayerManager.kt`**:
   - Cải tiến hàm `onSongDeleted(deletedSong: Song)` với thuật toán so khớp chặt chẽ (`id`, `filePath`, `title + artist`).
   - Ngắt ngay luồng phát bài bị xoá, dừng `MusicPlaybackService`, thu hồi Audio Focus và reset trạng thái UI.
2. **`AudioLinkExtractor.kt`**:
   - Mở rộng số lần thăm dò chuyển đổi từ 8 lần (8 giây) lên 30 lần (với bước nhảy 1.5 giây = 45 giây), phù hợp với thời gian xử lý video dài từ YouTube.
   - Thêm bộ phân giải dự phòng đa nguồn: khi nguồn sơ cấp gặp lỗi hoặc timeout, tự động chuyển đổi sang nguồn thứ cấp.
   - Báo cáo chính xác phần trăm xử lý âm thanh từ máy chủ lên thanh tiến trình UI của `DownloaderScreen`.
3. **`MusicViewModel.kt`**:
   - Đảm bảo khi gọi `deleteSong`, trạng thái của `NowPlayingSheet` và `activeDownloads` được đồng bộ tức thì.
