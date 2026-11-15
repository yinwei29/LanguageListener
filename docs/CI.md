# CircleCI 建置與 APK 交付

依開發偏好，以 CircleCI 執行自動檢查；GitHub 用於程式碼與 PR，Actions 僅留手動備援。不要同時為每次 push 與 PR 執行兩套 Android CI。

## 一次性連接

1. 到 https://app.circleci.com/ 登入 CircleCI，授權 GitHub 整合存取 `yinwei29/LanguageListener`。
2. 新增／設定此專案，使用儲存庫既有的 `.circleci/config.yml`。此設定不需要 CircleCI token、Android 簽章金鑰或任何 Google 憑證。
3. 設定單一 push 觸發器，避免再加相同提交的 PR opened／updated 重複觸發器；若帳號仍用舊版 GitHub OAuth 整合，使用其專案的分支觸發設定即可。
4. 開啟專案的自動取消過期 pipeline 功能（若方案／整合有提供）。不要啟用 SSH 除錯或額外平行測試工作作為預設。
5. 對 `ci/circleci-primary` 分支啟動首次 pipeline。確認測試、lint、APK、Artifacts 與 Tests 分頁皆成功後再合併 CI 切換 PR。
6. 若儲存庫分支保護原本要求 GitHub Actions 檢查，首次成功後將必要檢查改成 CircleCI 實際回報的名稱；本次不在未驗證前修改帳號或分支保護設定。

連接授權必須由有權限的 CircleCI 帳號完成，將 YAML 放入 GitHub 不代表 CircleCI 已啟用。不要將 CircleCI API token 貼進 PR、設定檔或聊天；若要讓開發環境直接觸發／讀取私人 pipeline，另外透過安全的連線或環境祕密設定。

## 流水線

- 使用固定 `cimg/android:2025.04` image 與明確 JDK 17，安裝 API 35／build-tools 35.0.0。使用專案 Gradle Wrapper，不使用 image 內建 Gradle 版本。
- 單一 medium Docker job，不啟動模擬器或平行複製的 Android build；兩個 Gradle worker、2 GiB heap、Kotlin 編譯在同一程序，降低 4 GiB executor 的記憶體壓力。
- 一次執行 `testDebugUnitTest lintDebug assembleDebug`；`--continue` 保留其他可執行的診斷，任何測試或 lint 錯誤仍令 job 失敗。
- 快取 Gradle Wrapper、依賴與 Robolectric runtime。快取鍵包含根／app Gradle 設定與 wrapper checksum；不快取 app 資料庫、備份、debug keystore 或 token。
- 只執行一個 workflow，略過 `docs/` 命名的分支。其餘程式碼分支由 CircleCI 專案的單一觸發器建置。
- 上傳 JUnit 到 Tests，上傳 lint／測試報告與 `LanguageListener-debug.apk` 到 Artifacts。CircleCI 的標準上傳步驟可保留失敗建置的報告；只有全部檢查通過才將 APK 放入 `ci-artifacts/apk` 交付目錄，失敗時不發布 APK。

APK 的保存天數與存取權限由 CircleCI 帳號方案／組織政策控制；此 YAML 不假設與 GitHub 的 14 天設定相同。需要安裝時，在成功 job 的 **Artifacts → LanguageListener-debug.apk** 下載。

## GitHub Actions 手動備援

`.github/workflows/android.yml` 只有 `workflow_dispatch`，不會因 push 或 PR 自動消耗 Actions 執行量。需要備援時才到 Actions 手動執行，仍保存 APK 與報告 14 天。

公開儲存庫使用標準 GitHub-hosted runner 的計費規則與私人儲存庫不同；CircleCI 也有自身的 credits、並行度與保存政策。實際額度請以帳號方案為準。本專案仍依指定優先使用 CircleCI，避免兩邊重複建置。

## 本機驗證

```sh
circleci config validate .circleci/config.yml
bash -n .circleci/build-android.sh
bash .circleci/build-android.sh
```

設定驗證與本機 Gradle 成功，只證明配置及程式碼能通過對應檢查；CircleCI Docker image、快取、授權、回報狀態及 artifact 上傳仍以實際 pipeline 結果為準。

## 切換驗收狀態

- [x] 官方 CircleCI CLI 1.15.0 checksum 核對及 config validate 通過。
- [x] `bash -n` 與同一份建置腳本在 JDK 17 的本機執行成功；22 項既有通過測試由 Gradle 判定 up-to-date，lint／APK 任務亦成功。
- [x] 失敗路徑檢查：保留非零結束碼，清除舊交付 APK，不發布失敗產物。
- [ ] CircleCI 帳號／GitHub 儲存庫授權及首次 pipeline：目前環境沒有 CircleCI 憑證或可操作的登入視窗，未登入的專案 API 回傳 404，無法確認專案已啟用。
- [ ] 成功 pipeline 的測試回報、快取及 APK artifact 實際下載驗證。

CI 切換 PR 在上述雲端驗收完成前保持草稿；既有分支的 GitHub CI 不會因草稿設定檔而被遠端關閉。
