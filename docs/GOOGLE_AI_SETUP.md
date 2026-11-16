# Google Sheets 初始化與 AI 教材

## App 使用流程

1. 在 Google Sheets 建立試算表與空白工作表，或選擇已具備 ID 及語言欄位的教材工作表。保留原始編輯網址與 gid，私人教材不需公開。
2. 在教材庫「開始使用」貼上網址，按「連接、儲存並初始化」，選擇 Google 帳號授權。
3. 檢查顯示的工作表與缺少欄位，再按「初始化並儲存」。空白工作表建立標頭，既有教材表只在標頭右側補缺少的欄位，不改寫既有標頭、資料或學習進度。其他用途的工作表會拒絕初始化。
4. 來源同步排程後可在教材庫看到進度；既有教材列需有穩定且唯一 ID，資料格式錯誤保留原快取。公開唯讀 CSV 仍可用原入口新增，不需授權。

若只需讀取已有教材的私人 Sheet，可按「已有教材：授權唯讀加入」，僅請求讀取權限，不初始化欄位；未完整授予寫入權限時，不會執行初始化或寫回。
5. 在 AI 區選擇 Gemini、OpenAI 或 OpenAI 相容服務，輸入模型與自己的 API 金鑰並儲存。相容服務使用 HTTPS 的 `/chat/completions` 協定，base URL 不可含帳密或 query；Azure 等不同驗證協定不在此次支援範圍。
6. 選擇已連接來源、主題、分類、類型、難度、語言與單批 1–100 筆，按「產生待審核教材」。數量是單次生成限制，可多次分批加入，不限制教材庫總筆數。
7. 每頁審核 10 筆，可修改翻譯／發音備註。預設不選取，逐筆或審核後選取本頁；按「確認寫回」並確認目標 Sheet。未選項目不寫入，本批完成後視為不採用。
8. 寫回前重新檢查 Sheets 的 ID／文字去重，以 RAW 與 INSERT_ROWS 附加新列，不執行文字中的公式、不覆寫既有教材。寫入確認後依網路政策重新排程同步；看到同步成功後即可離線播放。

標準欄位為 `ID, Category, Type, zh-TW, en-US, id-ID, level, tags, enabled, updated_at, status, source_url, pronunciation_note`。既有額外欄位保留，不在 App 寫入新列時填值。請勿在 App 寫回期間由其他編輯器同時修改表頭或附加相同教材；Sheets values API 不提供跨用戶、含條件判斷的唯一鍵交易。

## 開發者一次性 Google 設定（必要）

Google 原生 Android 授權不是貼網址就可取得權限。應用程式的簽章必須先登記在 Google Cloud：

1. 選擇你的 Google Cloud 專案，啟用 **Google Sheets API**。
2. 設定 Google Auth Platform／OAuth 同意畫面；開發期間將實際使用帳號加入測試使用者。對外公開時依 Google 要求處理同意畫面、敏感範圍審核與發布狀態。
3. 建立 **Android** OAuth client，套件名稱 `tw.language.listener`，SHA-1 為實際安裝 APK 的簽章指紋。此流程不在 APK 嵌入 OAuth client secret，也不使用聊天或 Google Drive 連接器的 token。
4. 指紋可用 Android SDK 執行：

   ```sh
   apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
   ```

5. 安裝該 APK 的手機需有可用的 Google Play 服務與有權編輯目標檔案的 Google 帳號。常見錯誤碼 10 表示套件、簽章或 Cloud OAuth 設定不符；同意流程取消不會初始化或寫入。

Google 授權讀取使用 `spreadsheets.readonly`，初始化／寫回時使用 `spreadsheets`。後者可授權存取帳號的試算表，App 只操作你指定的來源。SDK 負責需要互動的同意流程；背景同步若需重新同意，會保留快取並提示回到教材庫。token 僅短期保存，遇到 401 需重新連接。

「清除本機 Google 授權」會停止 App 的授權同步，並刪除本機 token；不會撤銷 Google 帳號設定頁的既有授權，也不刪除離線教材。

## CircleCI 的固定 debug 簽章

各 runner 自動產生的 debug keystore 不同，因此若每版都換簽章，Google OAuth 會失效或無法直接更新已安裝 App。請將自己的固定 debug keystore 透過 CircleCI 的安全 Context／專案環境變數提供：

- `LL_DEBUG_KEYSTORE_BASE64`：keystore 檔的 base64。
- `LL_DEBUG_STORE_PASSWORD`、`LL_DEBUG_KEY_ALIAS`、`LL_DEBUG_KEY_PASSWORD`：對應 keystore 設定。

腳本在私有暫存目錄解碼，輸出 APK 後清除；不放入 Git、快取或 artifacts。Gradle 只對 debug signing 使用這些環境變數。本機可直接設定 `LL_DEBUG_KEYSTORE_PATH` 與其他三個變數。未設定時仍可編譯，使用 Android 預設 debug keystore，但須登記該 APK 的實際 SHA-1。

這些變數是簽章身分，不是 AI API 金鑰。不要將簽章金鑰、密碼、API key 或 Google token 貼到聊天或提交到公開儲存庫。

## AI、安全與資料界線

- AI 模型名稱可自行調整；預設值不保證在所有帳號、地區或未來仍供應。404 請檢查模型，401 檢查金鑰，429 檢查配額。Google AI Pro／ChatGPT 訂閱不等於 API 額度。
- 本功能只生成內容，沒有網路搜尋。所有新教材寫入 `status=ai_generated_unverified`，不接受模型提供的 ID 或未驗證來源網址。ID 由 App 生成並持久化。
- 格式與語言驗證失敗不寫入；AI 批次內與目前離線快取去重，寫回前再查目標工作表去重。雙語生成與完整三語教材的文字指紋不同，不會因只有一個語言相同就合併。
- 審核批次保存在 Room，重新啟動可繼續；確認寫入前的修改與選取會先保存。若 HTTP 結果不明，保留草稿原樣重試，先用相同 ID 確認是否已寫入；不要重新生成一批新 ID 來重試同一次寫入。
- API key／Google token 用 Android Keystore AES-GCM 加密，放在 `noBackupFilesDir`。金鑰不進 Room、DataStore、SavedState、ZIP 或系統備份；不同 AI 供應商與 base URL 使用不同金鑰槽。非機密的供應商／模型／base URL 設定保存在 Room Settings 並可備份。API 不跟隨重新導向，避免把憑證送到其他網站，也不自動重送寫入或生成請求。
- ZIP schema 2 保存來源的公開／授權模式，仍能讀取 schema 1 的舊備份。未審核 AI 草稿與憑證不備份；完整覆蓋會清除本機舊草稿，換機後需重新設定金鑰與 Google 授權。Room 1→2 migration 保留教材、佇列與學習紀錄。
- Google Sheets API 沒有標準逐列分頁；本實作用已配置網格的 250 列範圍、每次最多五個範圍讀取，避免一次載入全部教材。大量空白配置列也會計入讀取工作；配額或回應大小限制會明確失敗並保留快取。

## 驗收界線

自動化使用真實本機 TLS 伺服器驗證 API JSON／HTTP 行為，測試資料不是已驗證的 Google 帳號或付費模型回應。Google Play 授權 UI、Cloud 設定、真實模型配額與 Android Keystore／換機仍須在配置完成的手機驗收，不冒稱已完成雲端與實機測試。
