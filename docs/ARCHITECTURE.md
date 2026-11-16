# 架構與資料相容性

## 資料與持久化

Room schema 2 保存 sources、items、progress、queue、study_days、session、settings、ai_drafts。schema 1／2 JSON 與 1→2 Migration 納入版本控制，不使用 destructive migration。來源增加公開／Google 授權模式，非機密 AI 模型設定可備份，未審核草稿保存在獨立資料表；金鑰與 token 只放在 Keystore 加密的 noBackupFilesDir。

教材與學習狀態以 `(sourceId, itemId)` 分開儲存。來源以標準 CSV 網址衍生穩定 UUID；移除後重新加入相同網址可沿用進度。同步只改教材與來源資訊，刪除教材採軟刪除。公開 CSV 逐行解析，每 250 列批次比對，僅 upsert 有變更的教材；TEMP 表記錄當次已見 ID，完整解析成功後只軟刪除缺少的教材。整份來源快照在單一交易內更新，任何一列驗證失敗都回復舊快取。相同 ID 以 CSV 最後一列為準。公開來源每次仍下載完整 CSV；授權來源以 Google Sheets API 讀取範圍後交給同一匯入器，不把資料庫差異更新冒稱為伺服器增量 API。

場次以 SQL 篩選、排序、限額，僅將穩定鍵放入佇列。Media3 保留佇列索引與簡短標題；音訊載入時才讀取單筆完整教材。教材庫每次查詢 100 筆，備份每次讀取 250 筆。SQLite 的自然容量及可用手機記憶體仍限制超大型佇列，未宣稱無限容量。

播放位置每 2 秒及播放狀態／項目切換時寫入 Room。場次識別碼防止舊播放器覆寫新場次；備份匯入也產生新的場次識別碼。強制終止可能遺失最後約 2 秒的位置。重啟回復佇列但不自動出聲。每日實際開始播放會記入獨立 study_days 表，避免只用最後播放時間推算連續天數而遺失歷史。DataStore 保存上次介面頁籤；可備份的產品設定以 Room 為準。

## 真正離線的播放路徑

`OfflineSpeech` 選擇語言相符、`isNetworkConnectionRequired == false` 且沒有 `notInstalled` 的 Android voice。找不到就回報缺少語音包，不能退回網路語音。使用 `synthesizeToFile` 合成本機 WAV，各語言語速、重複及靜音間隔都實際寫入音訊。標準 PCM 8/16 bit、單聲道／雙聲道轉成 22.05 kHz、16 bit 單聲道，其他格式明確失敗。

`SpeechDataSource` 在 ExoPlayer loader 執行緒依需要合成並讀取音訊；合成過程不阻塞 UI。音訊快取約 64 MiB，單筆輸出不得超過 64 MiB，文字教材不受快取淘汰影響。首次播放／變更場次參數可能需要重新合成。合成音訊與引擎語音包不納入 ZIP。

`ListeningService` 是真正的 Media3 `MediaSessionService`，由 ExoPlayer 提供佇列、上一筆／下一筆、暫停／繼續、媒體通知、鎖屏與耳機標準控制。啟用音訊焦點、speech content type、耳機拔除暫停及本機 wake lock。暫時失去焦點由 ExoPlayer 暫停，焦點回來時恢復；永久失去焦點不自動恢復。特殊耳機手勢、來電時焦點行為、各廠商省電限制與語音包仍須實機測試。

場次編輯器所有參數於「建立新場次」時生效，儲存設定不改寫正在播放的佇列。單次跳過不改進度；已學會與 7／30 天暫停會寫入穩定鍵並移到下一筆。場次最後一筆不會偷偷重建佇列。

## 公開 Sheets 與背景工作

WorkManager 執行手動及啟動同步；離線時等待網路。Wi-Fi 模式同時設定 unmetered constraint 並檢查 Wi-Fi transport。每個來源各自成功或失敗，錯誤與上次同步時間保留在來源表，失敗不修改教材及進度。移除來源後，仍在下載的工作不能將它重新加入。

## ZIP 安全與合併規則

SAF 選擇輸出位置與輸入檔案，無需全檔案存取權限。ZIP 只允許八個固定平面檔名：manifest、sources、items、progress、queue、days、session、settings；各資料採型別明確的 JSON Lines。manifest 包含 schemaVersion、createdAt、appVersion、筆數及各資料檔 SHA-256 checksum。

匯入串流解壓至 app 私有暫存，禁止任意檔名、絕對路徑、重複項目、目錄；檢查 CRC、SHA-256、schema、必要資料、穩定鍵、數值範圍、單筆長度及解壓總量（128 MiB）。成功驗證後才提供預覽。超限備份不能匯出成一個自己無法讀取的檔案。總量上限屬解壓安全限制，不是 Sheets 教材筆數上限。

完整覆蓋在單一 Room 交易中取代資料；智慧合併以進度 modifiedAt 較新者為準，相同時間保留本機。已有教材文字與來源保留本機資料，缺少者才加入；設定採較新修改時間，本機非空佇列保留。不同來源 ID 即使教材文字相同也不混為同一筆。

輕量備份保留來源、設定、佇列與進度，不含教材。還原後可先保留孤立進度，待同來源 ID 的教材同步後接續使用；內建公開示範教材可重新建立。完整備份可在完全離線時恢復教材與進度。憑證、API 金鑰及 OAuth token 沒有資料欄位，不會被備份。

## Google／AI 教材流程

Google 原生授權、非破壞式初始化、AI BYOK、人工審核與附加寫回見 [Google／AI 設定](GOOGLE_AI_SETUP.md)。ZIP schema 2 可讀 schema 1，保存非機密模型設定及來源存取模式；未審核草稿不匯出，完整覆蓋會清除本機草稿。金鑰、短期 token 與簽章檔均不在備份或 CI 快取範圍。
