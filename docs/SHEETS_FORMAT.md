# 公開 Google Sheets 教材格式

Phase 1 不需要 API Key 或 Google 登入。將試算表設定為可公開讀取，或使用「檔案 → 共用 → 發布到網路」發布指定工作表的 CSV，將網址貼到教材庫。勿將私人教材公開，若需要私人來源請等待 Phase 2 OAuth。

範例（全部為非私人示範文字）：

```csv
ID,Category,Type,zh-TW,en-US,id-ID,level,tags,enabled,updated_at,status,source_url,pronunciation_note
hello,日常,word,你好,Hello,Halo,A1,greeting,true,2026-11-15,,,
hotel-1,酒店,sentence,我想辦理入住。,I would like to check in.,Saya ingin check-in.,A1,travel,true,2026-11-15,,,
```

- 必要欄位為 `ID`、`zh-TW`、`en-US`、`id-ID`，欄名大小寫不影響解析。每列 ID 必須非空，至少一種文字非空；場次會篩選所選母語及目標語皆有文字的教材。
- `ID` 必須持續相同，修改翻譯、分類、難度時不可換 ID。不同來源相同 ID 是不同教材。
- `Category` 自訂；`Type` 建議 `word`、`phrase`、`sentence`；`level`、`tags` 可空白。
- `enabled` 可為空白（啟用）、true／false、1／0、yes／no。`status` 為 deleted／removed 表示軟刪除。
- 移除 CSV 中原有的列也會軟刪除該教材；學習進度不刪除。完全只有有效標頭的 CSV 代表該來源目前沒有啟用教材。
- 支援帶引號的逗號、換行及雙引號；同來源重複 ID 以最後一列為準。
- 任一格式錯誤會讓整個來源此次更新失敗，保留上次教材；其他來源可以獨立成功。
- 一般試算表網址中的 `gid` 用來選擇工作表。新增時會轉為標準 CSV export 網址；發布網址保留其發布 ID。
- 手動同步採 WorkManager 背景工作，離線時會等待網路，教材庫顯示最後成功時間及錯誤。
- 移除後重新新增相同標準網址會得到相同 sourceId，因此同 ID 教材可接回既有進度。

每列欄位最多 100,000 字元、最多 100 欄，避免單列異常輸入耗盡記憶體；不另設教材筆數上限。實際容量受裝置儲存、SQLite 及場次佇列記憶體限制。
