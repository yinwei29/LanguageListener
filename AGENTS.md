# Codex 開發指示 — LanguageListener

- 使用臺灣繁體中文撰寫預設 UI、文件與驗收說明，程式命名採英文。
- 開工前閱讀 docs/PRODUCT_SPEC.md、docs/UI_SPEC.md、docs/IMPLEMENTATION_PLAN.md；不得刪減已確認需求。
- Android 原生 Kotlin + Jetpack Compose + Material 3 + Room + DataStore + Android TextToSpeech + Media3/MediaSession + WorkManager。
- 優先實作 Phase 1 MVP；每階段以獨立 PR 提交，說明實際測試結果與未完成事項。
- UI 原型不是功能完成證明。必須實作真正的離線 TTS、背景播放、通知、鎖屏與耳機媒體鍵。
- 所有學習狀態以 sourceId + itemId 穩定鍵保存；Sheets 更新不得重置進度。
- 專案為 Public：不可提交 API Key、OAuth Token、真實教材中的私人資訊、備份檔、簽章金鑰或 local.properties。
- 無加密 ZIP 備份不得包含憑證；匯入需防 Zip Slip、Zip Bomb，檢查版本、完整性與資料合法性。
- Android 系統限制須如實標註，例如不同廠牌耳機手勢與 TTS 離線語音包支援差異。
- 使用可用的 Gradle test、lint、assembleDebug 驗證，不可虛報通過。
