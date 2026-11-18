# LanguageListener

Android 離線多語言聽力學習 APP（臺灣繁體中文優先）。

## Codex 開發入口
請先閱讀 [AGENTS.md](AGENTS.md)、[產品規格](docs/PRODUCT_SPEC.md)、[UI 設計](docs/UI_SPEC.md) 與 [施工計畫](docs/IMPLEMENTATION_PLAN.md)，按階段實作。

技術：Kotlin、Jetpack Compose、Room、Android TextToSpeech、MediaSession、Google Sheets CSV、無加密 ZIP 備份。

此為公開儲存庫，禁止提交 API Key、OAuth Token、使用者資料、簽章金鑰或私人備份。

**目前狀態：離線 MVP、Sheets 初始化與 AI 審核寫回流程已實作；Google／AI 真實帳號設定及硬體功能仍需實際驗收。**

Phase 2 同步可靠性與效能見 [進度紀錄](docs/PHASE2_PROGRESS.md)。最新 Google 授權與 AI 教材使用方式見 [Google／AI 設定](docs/GOOGLE_AI_SETUP.md)。三語介面與整批驗收方式見 [0.3.0 整合測試指南](docs/TESTING_0_3.md)。

## 取得與建置 APK

Android 8.0（API 26）以上，compile／target SDK 35，JDK 17。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。主要 CI 改用 CircleCI，以單一工作執行測試、lint 與 APK 建置，並快取依賴；需先完成 [CircleCI 一次性連接](docs/CI.md)。GitHub Actions 只保留手動備援，不再因 push／PR 自動執行。

CircleCI 連接後，在成功 job 的 **Artifacts → LanguageListener-debug.apk** 下載安裝；測試結果在 Tests，lint 與測試報告在 Artifacts。保存期限依 CircleCI 帳號方案。若手動使用 GitHub 備援，在 **Actions → Android debug APK (manual fallback) → 成功的 run → Artifacts** 下載，保存 14 天。debug APK 不是 Play Store 正式發行版；不同 runner 的 debug 簽章可能不同，若無法直接更新，先匯出 ZIP 備份再重新安裝。

## 首次使用

建立教材可直接在 App 完成：**貼上原始 Sheets 網址 → Google 授權 → 預覽並初始化缺少欄位 → 設定自己的 AI API key → 產生 → 分頁審核／修改／選取 → 確認寫回 → 依政策同步 → 離線播放**。首次 Google 連接需開發者在 Cloud 啟用 Sheets API、登記 APK 套件及簽章，詳見 [必要設定](docs/GOOGLE_AI_SETUP.md)。AI 產生不等於已搜尋或查證，未審核不寫回。

1. 設定頁檢查離線語音包；缺少時安裝所需的繁中、英文或印尼文 voice。不同 Android TTS 引擎及裝置支援不同。
2. 可用內建三語示範教材、授權連接並初始化 Sheets，或新增公開 CSV 來源後同步。格式見 [Sheets 格式](docs/SHEETS_FORMAT.md)。
3. 設定母語／目標語、朗讀方向、各語速、重複、間隔、數量及篩選，按「建立新場次」。設定只於新場次生效。
4. 下載教材與安裝語音包後可在飛航模式播放。TTS 真正合成本機 WAV，由 Media3 前景媒體服務播放，提供背景、鎖屏、通知與耳機標準控制；首次合成會短暫緩衝。
5. 設定頁可透過 SAF 匯出輕量或完整 ZIP，並預覽匯入內容、選擇完整覆蓋或智慧合併。ZIP 不加密，不含憑證與語音包。

## 本次範圍

- Kotlin、Jetpack Compose、Material 3 四頁，預設臺灣繁體中文，可切換英文／印尼文介面，支援深色模式與可捲動版面。
- Room 教材／來源／穩定鍵進度／持久佇列／設定／每日學習紀錄；DataStore 保存上次頁籤。
- 離線 Android TTS voice 驗證、三語切換、方向／語速／重複／靜音間隔、循環。
- Media3 ExoPlayer + MediaSessionService，音訊焦點、拔除耳機暫停、播放位置持久化；重啟不自動出聲。
- SQL 場次篩選、順序／亂數、20／50／100／自訂／全部、避開近期與優先複習；學會／7 或 30 天暫停／收藏／複習／恢復未學。
- 多來源公開 Sheets CSV、WorkManager 手動與啟動同步、Wi-Fi 政策、批次 upsert、去重／軟刪除與失敗回復。
- SAF ZIP 完整／輕量備份、SHA-256／CRC／schema／資料校驗、Zip Slip／Zip Bomb 防護、交易式覆蓋與合併。

私人 Sheets 使用 Google 原生授權，支援安全初始化與人工確認後附加 AI 教材。Gemini、OpenAI 與 OpenAI 相容 BYOK 金鑰只存裝置加密區，不納入備份；真實網路搜尋尚未實作。公開 CSV 每次下載完整來源；尚未提供遠端變更 token 的增量傳輸。備份解壓安全上限 128 MiB、單筆合成音訊上限 64 MiB；未對教材列數設人為上限，但超大型場次仍受手機資源限制。

## 驗證與文件

- [架構與合併規則](docs/ARCHITECTURE.md)
- [實機驗收表](docs/DEVICE_ACCEPTANCE.md)：斷網初次合成、鎖屏持續播放、耳機、來電中斷及換機 SAF 還原需實機確認，不能以 UI 原型或 JVM 測試代替。
- 自動化涵蓋 CSV 引號／多行／BOM／格式錯誤、來源網址、同步去重與交易回復、穩定鍵進度、場次排除／去重／舊位置寫入、Room 重開、ZIP 來回／合併／輕量、惡意路徑／重複項目／校驗／版本／解壓超限，以及 PCM 音訊與靜音。

未提交任何私有教材、憑證、備份或簽章金鑰。`local.properties`、建置產物、ZIP、keystore 都已排除版本控制。Room schema 1／2／3 與 1→2→3 migration 已納入版本控制；ZIP schema 3 保留來源模式、模型設定與介面語言，並可讀取舊版 schema 1／2。未審核草稿不納入 ZIP，完整覆蓋會清除草稿，換機後須重新授權並設定 API key。
