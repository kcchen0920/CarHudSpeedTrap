# Local Rule: CarHudSpeedTrap Automatic GitHub Sync

每次在此專案（`android車機` / CarHudSpeedTrap）完成程式碼、設定、腳本或文件修改與驗證後，必須自動執行以下規範：

1. **自動提交與推送至 GitHub**：
   - 驗證完成後，自動將變更檔案加入 staging 並提交。
   - 立即同步推送至 GitHub 遠端儲存庫：
     ```bash
     git push origin main
     ```
   - 遠端網址：`git@github.com:kcchen0920/CarHudSpeedTrap.git`
2. **安全規範**：
   - 嚴格遵守 `.gitignore`，不得提交本機 keystore、金鑰、本地路徑設定或 build 產物。
   - 採用簡明清晰的 Conventional Commit Message 格式。
