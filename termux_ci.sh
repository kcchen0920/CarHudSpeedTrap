#!/usr/bin/env bash
# ==============================================================================
# Redmi Pad Termux 專用：自動 Push 並監控 GitHub CI/CD 建置狀態與下載連結
# ==============================================================================

set -e

REPO="kcchen0920/CarHudSpeedTrap"
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "main")

echo "=================================================="
echo "📱 [Redmi Pad Termux] 準備推送程式碼至 GitHub..."
echo "=================================================="

# 1. 執行 git push
git push origin "$BRANCH"

COMMIT_SHA=$(git rev-parse HEAD)
SHORT_SHA=${COMMIT_SHA:0:7}

echo ""
echo "🚀 程式碼已推送！(Commit: $SHORT_SHA)"
echo "📡 正在監控 GitHub Actions 雲端建置狀態..."
echo "--------------------------------------------------"

# 2. 輪詢 GitHub API 取得本次 Commit 的 Workflow Run
MAX_WAIT_SECONDS=300
ELAPSED=0
RUN_ID=""
HTML_URL=""

while [ $ELAPSED -lt $MAX_WAIT_SECONDS ]; do
    sleep 5
    ELAPSED=$((ELAPSED + 5))

    # 查詢最近的 runs
    RESPONSE=$(curl -s "https://api.github.com/repos/$REPO/actions/runs?head_sha=$COMMIT_SHA" || true)

    # 擷取 run_id 與 html_url
    RUN_ID=$(echo "$RESPONSE" | grep -o '"id": [0-9]*' | head -n 1 | awk '{print $2}' || true)
    STATUS=$(echo "$RESPONSE" | grep -o '"status": "[^"]*"' | head -n 1 | cut -d'"' -f4 || true)
    CONCLUSION=$(echo "$RESPONSE" | grep -o '"conclusion": "[^"]*"' | head -n 1 | cut -d'"' -f4 || true)
    HTML_URL=$(echo "$RESPONSE" | grep -o '"html_url": "https://github.com/[^"]*"' | head -n 1 | cut -d'"' -f4 || true)

    if [ -z "$RUN_ID" ]; then
        echo -ne "\r⏳ 等待 GitHub Actions 接收觸發... (${ELAPSED}s)"
        continue
    fi

    if [ "$STATUS" = "completed" ]; then
        echo -e "\r                                                      \r"
        echo "=================================================="
        if [ "$CONCLUSION" = "success" ]; then
            echo "✅ 【建置成功】CI/CD 已完成編譯打包！"
            echo ""
            echo "📦 APK 構件與下載頁面："
            echo "   $HTML_URL"
            echo ""
            echo "💡 提示：在 Termux 點擊或長按上方連結，即可直接在瀏覽器下載 CarHud-debug-apk。"
        else
            echo "❌ 【建置失敗】CI/CD 編譯發生錯誤 (狀態: $CONCLUSION)！"
            echo ""
            echo "🔍 錯誤日誌檢視連結："
            echo "   $HTML_URL"
        fi
        echo "=================================================="
        exit 0
    else
        echo -ne "\r⚙️ 雲端編譯中... (狀態: $STATUS, 已耗時 ${ELAPSED}s)"
    fi
done

echo ""
echo "⚠️ 等待逾時 (超過 5 分鐘)。請手動至瀏覽器查看："
if [ -n "$HTML_URL" ]; then
    echo "   $HTML_URL"
else
    echo "   https://github.com/$REPO/actions"
fi
