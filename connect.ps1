# 車機 ADB 連線與 Layout 檢測輔助腳本
param(
    [string]$Ip = ""
)

$adb = Join-Path $PSScriptRoot "tools\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    Write-Host "[Error] 找不到 ADB: $adb" -ForegroundColor Red
    exit 1
}

if ($Ip -ne "") {
    Write-Host "[Info] 正在透過 Wi-Fi 連線至車機: $Ip:5555 ..." -ForegroundColor Cyan
    & $adb connect "$($Ip):5555"
}

Write-Host "[Info] 檢查目前連線裝置..." -ForegroundColor Cyan
& $adb devices

$deviceCount = (& $adb devices | Where-Object { $_ -match "\tdevice$" }).Count

if ($deviceCount -eq 0) {
    Write-Host "[Warning] 尚未偵測到已就緒的車機裝置。" -ForegroundColor Yellow
    Write-Host "請檢查："
    Write-Host "  1. 若走 USB：確認線材具備傳輸功能，並在車機螢幕上勾選「永遠允許這部電腦進行偵錯」。"
    Write-Host "  2. 若走 Wi-Fi：請傳入車機 IP，例如: .\connect.ps1 -Ip 192.168.1.xxx"
    exit 0
}

Write-Host "`n[Success] 車機已連線！正在擷取硬體與螢幕佈局資訊..." -ForegroundColor Green

# 1. 取得螢幕解析度與密度
Write-Host "`n--- 螢幕參數 ---" -ForegroundColor Magenta
& $adb shell wm size
& $adb shell wm density

# 2. 取得預設 Launcher
Write-Host "`n--- 預設 Launcher ---" -ForegroundColor Magenta
& $adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -First 5

# 3. 截取主畫面 XML 結構與截圖儲存至 output 目錄
$outputDir = Join-Path $PSScriptRoot "output"
if (-not (Test-Path $outputDir)) { New-Item -ItemType Directory -Path $outputDir -Force | Out-Null }

Write-Host "`n--- 擷取畫面中 ---" -ForegroundColor Magenta
& $adb shell uiautomator dump /sdcard/screen_dump.xml
& $adb pull /sdcard/screen_dump.xml (Join-Path $outputDir "screen_dump.xml")

& $adb shell screencap -p /sdcard/screen.png
& $adb pull /sdcard/screen.png (Join-Path $outputDir "screen.png")

Write-Host "`n[OK] 畫面已存入: output\screen.png 與 output\screen_dump.xml" -ForegroundColor Green
