# LanguageListener 完整產品需求 v1.0

## 產品目標
Android-first、離線優先的多語言聽力學習工具。鎖屏、通勤、走路時仍可連續播放。預設臺灣繁體中文介面，教材支援繁中、英文、印尼文，架構可擴充其他語言。無自建伺服器及固定月費。

## 教材與 Google Sheets
- 單字、短語、句子；分類：日常、酒店、商務、旅遊與自訂。
- 支援多個 Google Sheets 來源，不設人為筆數上限（受手機儲存與效能限制）。
- 建議欄位：ID, Category, Type, zh-TW, en-US, id-ID, level, tags, enabled, updated_at, status, source_url, pronunciation_note。
- 使用 sourceId + itemId 穩定主鍵；文字修改不得改變主鍵。
- Phase 1：公開或發布為 CSV 的 Sheet URL；Phase 2：私人 Sheets OAuth 讀取；Phase 3：授權寫入。
- 手動同步、啟動時同步、選擇僅 Wi-Fi；增量更新、批次處理、去重、軟刪除；失敗保留快取。
- Sheets 同步不能覆蓋本地已學會、跳過、收藏與複習紀錄。

## 離線 TTS 播放
- 母語→目標語、目標語→母語、僅目標語；可選顯示第三語言文字。
- 各語言語速、朗讀重複次數、間隔秒數、循環模式、暫停/繼續。
- Android TTS 檢查 voice 是否實際支援離線；缺少語音包時引導安裝，不可假裝離線。
- MediaSession/Foreground Service、鎖屏與通知媒體控制、耳機媒體鍵、音訊焦點、來電中斷與恢復。
- 上一筆、下一筆、跳過本次、標記已學會、暫停 7/30 天、加入複習、收藏、恢復未學狀態。
- 耳機特殊手勢依裝置支援，不能保證所有耳機可自訂。

## 場次與學習進度
- 20/50/100/自訂正整數/全部；依語言、分類、類型、難度、狀態篩選。
- 順序或亂數；單場次不重複，可避開近期項目、優先複習。
- 預設排除已學會與跳過期限未滿的教材；不足指定筆數時播放全部可用教材並提示。
- 持久化播放佇列、目前位置、播放參數、playCount、lastPlayedAt、skipUntil、reviewPriority。
- 大量資料應以 Room/SQLite 分批查詢，不將全部教材一次載入記憶體。

## AI 教材（後期）
- BYOK：Gemini、OpenAI 或相容 API，主題、難度、語言、筆數→產生單字/句子/翻譯。
- 去重、格式驗證、人工審核後才寫入授權 Google Sheets，隨後同步手機。
- 真實網路搜尋須有搜尋 API/搜尋能力，不可將 LLM 生成冒稱為已查證搜尋。
- API Key 使用安全儲存，不嵌入 APK、Sheets、GitHub 或備份；Google AI Pro 訂閱不等於 Gemini API 額度。
- 未設定 AI 時仍可完整離線學習。

## 無加密 ZIP 備份與換機
- Android Storage Access Framework 一鍵匯出至使用者指定位置；離線可用。
- 輕量備份：進度、已學會、跳過、收藏、複習、播放清單、來源與設定。
- 完整備份：上述資料及下載的離線教材。
- ZIP 包含 manifest.json（schemaVersion、createdAt、appVersion、checksum）及 JSON/CSV 或 SQLite 資料。
- 匯入預覽、完整覆蓋或智慧合併；穩定鍵比對、衝突處理、資料庫交易回復。
- 檢查路徑穿越、解壓大小、檔案損毀及 schema 版本；不得包含 API Key、OAuth Token、憑證。
- 可經 USB、Quick Share、Google Drive 手動轉移；TTS 語音包需新手機重新安裝。
- 定期備份可後續加入，需符合 SAF 持續權限與 Android 背景限制。

## 非功能要求
- Material 3，預設淺色、可切深色，動態字級、TalkBack、單手操作。
- UI 本地化繁中、英文、印尼文。
- 離線、無教材、TTS 不可用、同步失敗、匯入損毀等情境須有明確提示。
- 真正的鎖屏背景播放、換機備份與離線 TTS 需在實機驗收。
