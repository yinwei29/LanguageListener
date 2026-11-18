# 0.4.1 AI 生成、ChatGPT／OpenAI 與模型選單

本版針對「產生教材時只顯示 HTTP 400」改善請求格式、診斷及大批生成。原始截圖無法辨識供應商及服務端原因，因此不把所有 400 都歸因於額度或宣稱已驗證特定使用者帳號。

## 開始使用

1. 更新 APK，確認右上版本 **v0.4.1**。
2. 在教材庫 AI 區選「ChatGPT／OpenAI API」、Gemini 或 OpenAI 相容 API。
3. 輸入自己的該供應商 API 金鑰，按「載入模型清單／檢查金鑰」。Gemini 會列出支援 generateContent 的文字模型；OpenAI 會列出帳號回傳的 GPT／推理文字模型。相容 API 可使用自己的 `/models` 清單。
4. 從「模型選單」選模型，或手動填完整模型 ID；按「儲存 AI 設定」。未載入前的選單只是範例，不能代表帳號權限、額度或實際可用性。
5. 選本機、檔案或 Google Sheets 來源，填主題、類型、難度、語言及數量，先以 10 筆確認設定，再產生更多。
6. 人工審核、選取、確認後才儲存或寫回；本機檔案來源先存 App，再按檔案同步按鈕寫回。

使用 OpenAI API 金鑰及 API 帳戶額度，費用依供應商計費。App 不接收 ChatGPT 網站登入資料，也不把生成內容冒稱為已搜尋或查證。

## API 與輸出格式

- ChatGPT／OpenAI 的自動模式改用 **Responses API**，停用伺服器 response 儲存（`store=false`）；也可手動選 Chat Completions。
- OpenAI 相容 API 的自動模式保留 Chat Completions，避免把既有伺服器改成不支援的端點；可手動選 Responses。
- 可選 JSON、JSON Schema 或僅提示詞 JSON。Gemini 的 JSON 使用 `generationConfig`；Responses 使用 `text.format`；Chat Completions 使用 `response_format`。相容 Chat 的預設 JSON 保留提示詞模式，需要明確 schema 時選 JSON Schema。
- 如果模型拒絕格式參數，可改為「僅提示詞 JSON」；仍會嚴格檢查回應與教材格式，格式錯誤不會被當作可寫回教材。
- 回應截斷、拒答或空內容有明確錯誤，不解析成不完整草稿。沒有自動切換模型、降級格式或重試付費生成請求。

## 大量生成與失敗續傳

每個 API 請求最多 10 筆，100 筆會規劃為 10 個依序請求。每個成功批次先保存穩定 UUID 草稿及進度；失敗或停止後，重開 App 仍可按「繼續產生剩餘批次」，不重跑已成功的批次。繼續時需保持原模型／API／輸出設定；要換設定請重新產生。未審核草稿仍不進 ZIP。

畫面上的「已完成的請求量」是已處理的請求目標，不等於最後可用教材數。模型少回、跨批重複或與既有教材重複，會使審核筆數少於要求；不自動增加付費請求補滿。已有內容會作為後續批次的去重提示。審核畫面隨批次更新顯示目前內容；未儲存的人工審核編輯應先確認寫入，或等生成完成後再修改。

若連線在回應確認前中斷，供應商仍可能已計費。請自行決定是否手動繼續，不將未知結果自動重送。新的成功批次會替代原本未寫回草稿，重新產生前仍有確認。

## HTTP 400 與其他錯誤

新增持續顯示的錯誤區塊，依安全的服務錯誤代碼分類：金鑰無效／失效、模型不存在或無權限、參數不支援、計費／額度、請求頻率、地區或 API 啟用資格。識別得出的參數只顯示允許名單中的欄位名稱，沒有識別到具體原因時會明確保留一般診斷。

不回顯供應商完整 error body 或 message，以免其中含金鑰或私人主題；不把完整錯誤記錄到儲存庫。Key 僅經 HTTPS header 傳送，保持供應商／API origin 隔離，不含於 APK、Sheets 或 ZIP。

## 相容性

Room 仍為 schema 4，AI 設定是既有 TEXT 欄位，不需刪除資料庫。舊設定補上 API／輸出格式預設值，原 key slot 保持不變。舊草稿預設已完成生成，可繼續人工審核。ZIP 升為 schema 5，可讀 1–4，保存非機密模型／格式設定，金鑰與未審核草稿排除；舊版 App 不支援新版備份。

## 官方 API 參考

- [OpenAI Responses](https://developers.openai.com/api/reference/resources/responses/methods/create)
- [OpenAI 模型清單](https://developers.openai.com/api/reference/resources/models/methods/list)
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Gemini 模型清單](https://ai.google.dev/api/models)

參考資料查核日期：2026-11-18。帳號可用模型以實際 API 回傳為準；真實金鑰、額度、地區限制及 Android 實機仍需外部驗收。

## 本次驗證（2026-11-18）

使用 CircleCI 同一份 `.circleci/build-android.sh`，JDK 17／SDK 35：78 tests，0 failures/errors/skipped；lint 0 errors、19 warnings；assembleDebug 與 APK 簽章驗證成功，476 組三語資源一致。

新增測試涵蓋 Responses API 的實際 HTTPS 請求／回應結構、JSON Schema、截斷／拒答、OpenAI／Gemini 模型清單與分頁、金鑰／模型／額度錯誤分類及敏感內容不回顯、失敗後從成功批次續傳、跨批去重、取消時先完成已回應批次的存檔，以及舊設定與草稿相容。測試使用本機 HTTPS 伺服器，未以真實金鑰驗證各帳號可用模型、額度或原始 HTTP 400；沒有 Android 實機與已授權 CircleCI 雲端 pipeline 驗收。
