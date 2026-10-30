# Local Rule: CarHudSpeedTrap Automatic GitHub Sync & Multi-Platform Build Policy

每次在此專案（`android車機` / CarHudSpeedTrap）完成程式碼、設定、腳本或文件修改與驗證後，必須自動執行以下規範：

1. **自動提交與推送至 GitHub**：
   - 驗證完成後，自動將變更檔案加入 staging 並提交。
   - 立即同步推送至 GitHub 遠端儲存庫：
     ```bash
     git push origin main
     ```
   - 遠端網址：`git@github.com:kcchen0920/CarHudSpeedTrap.git`

2. **多平台 Build Code 規範（平板預設方式一）**：
   - **Redmi Pad 5G (Android 平板 / Termux)**：
     - **預設一律採用方式一 (`./termux_ci.sh`)** 進行建置。
     - 嚴禁在平板 Termux 內直接執行本地 `./gradlew assembleDebug`，不得在專案中引入 Termux 本地 aapt2 覆寫 (`android.aapt2FromMavenOverride`)。
     - 平板端提交並觸發建置時，統一執行 `./termux_ci.sh`，由腳本自動 push 並在 Termux 終端機內即時回報 GitHub Actions 雲端 CI/CD 狀態（Pass/Fail），並自動產出可直接點擊的 APK 下載連結。
   - **筆電 (Notebook / Windows)**：
     - 本地執行 `.\build_apk.ps1` 進行編譯驗證，並自動同步至 Google 雲端硬碟備份路徑。

3. **安全規範**：
   - 嚴格遵守 `.gitignore`，不得提交本機 keystore、金鑰、本地路徑設定或 build 產物。
   - 採用簡明清晰的 Conventional Commit Message 格式。
