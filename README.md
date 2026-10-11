# Novel Reader (小說閱讀與離線下載 Android App)

本專案為使用 **Kotlin + Jetpack Compose + Room + WorkManager** 開發的 Android 小說閱讀器，支援貼上網址或透過內建瀏覽器過人機驗證後，整部下載離線閱讀。

---

## 核心功能特色

1. **直接貼網址匯入**：
   - 點選右上角 `+` 直接輸入小說首頁或目錄網址，自動抓取小說名稱、作者、簡介與完整章節目錄。
2. **內建 WebView 瀏覽器（過人機驗證）**：
   - 若遇到 Cloudflare 驗證（如 czbooks.net 等防爬網站），可點擊右上角「地球」圖示進入內建瀏覽器正常瀏覽。
   - 瀏覽到喜歡的小說後，直接點擊右上角「**加入書架**」，自動擷取 DOM 匯入章節目錄。
3. **整部小說離線下載**：
   - 透過 Android **WorkManager** 在背景排隊抓取每一章節內文。
   - 支援關閉 App 繼續背景下載、中斷後自動斷點續傳。
4. **離線閱讀器**：
   - 全本地 Room SQLite 儲存，無網路狀態下隨時打開看。
   - 支援字級放大/縮小調整（`12sp` ~ `36sp`）。
   - 支援深色/夜間閱讀模式（深底灰字護眼）。
   - 自動記錄最後閱讀章節與閱讀時間。

---

## 專案架構

- `app/src/main/java/com/novelreader/app/data/local/`: Room Database、DAO、實體資料庫模型（`NovelEntity`, `ChapterEntity`）。
- `app/src/main/java/com/novelreader/app/data/parser/`: `UniversalNovelParser`（使用 Jsoup 自動分析並抽取標題、目錄與內文）。
- `app/src/main/java/com/novelreader/app/data/download/`: `NovelDownloadWorker`（WorkManager 背景下載排程）。
- `app/src/main/java/com/novelreader/app/data/repository/`: `NovelRepository` 資料存取層。
- `app/src/main/java/com/novelreader/app/ui/`:
  - `BookshelfScreen`: 書架首頁、已下載章節進度、整部下載與刪除。
  - `NovelDetailScreen`: 小說簡介、完整章節目錄。
  - `ReaderScreen`: 離線閱讀器、字級與夜間模式切換。
  - `WebBrowserScreen`: 內建瀏覽器模式。

---

## 如何建置與執行

1. 使用 **Android Studio** 開啟目錄：
   ```
   D:\git\novel-reader
   ```
2. 等待 Gradle Sync 完成。
3. 連接 Android 手機（或啟動模擬器），點擊 **Run (Shift + F10)** 即可安裝執行。
