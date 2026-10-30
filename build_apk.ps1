param()
$ErrorActionPreference = "Stop"

$scriptRoot = $PSScriptRoot
$javaHome = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
$androidHome = Join-Path $scriptRoot "tools\android-sdk"
$projectDir = Join-Path $scriptRoot "CarHudSpeedTrap"
$gradleBat = Join-Path $scriptRoot "tools\gradle\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradleBat)) {
    $gradleBat = Join-Path $projectDir "gradlew.bat"
}

Write-Host "Starting CarHudSpeedTrap Build..." -ForegroundColor Cyan

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome
$env:JAVA_TOOL_OPTIONS = ""
$env:PATH = "$javaHome\bin;$scriptRoot\tools\gradle\bin;$env:PATH"

Set-Location -LiteralPath $projectDir

& $gradleBat assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build FAILED! Gradle exited with code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

$apkOutput = Join-Path $projectDir "app\build\outputs\apk\debug\app-debug.apk"
$targetApk = Join-Path $scriptRoot "CarHud-debug.apk"

if (Test-Path -LiteralPath $apkOutput) {
    Copy-Item -LiteralPath $apkOutput -Destination $targetApk -Force
    Write-Host "Build SUCCESS! Output: $targetApk" -ForegroundColor Green

    # 自動同步至 Google 雲端硬碟 (G:\我的雲端硬碟\車用 & D:\文件\car)
    $cloudDirs = @(
        (Join-Path "G:\" "我的雲端硬碟\車用"),
        (Join-Path "D:\" "文件\car")
    )
    foreach ($cDir in $cloudDirs) {
        try {
            if (-not (Test-Path -LiteralPath $cDir)) { 
                New-Item -ItemType Directory -Path $cDir -Force -ErrorAction SilentlyContinue | Out-Null
            }
            if (Test-Path -LiteralPath $cDir) {
                $destFile = Join-Path $cDir "CarHud-debug.apk"
                Copy-Item -LiteralPath $targetApk -Destination $destFile -Force
                Write-Host "Synced to Cloud Drive: $destFile" -ForegroundColor Green
            }
        } catch {
            Write-Host "Sync to $cDir failed: $_" -ForegroundColor Yellow
        }
    }
    
    $adb = Join-Path $scriptRoot "tools\platform-tools\adb.exe"
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $devices = & $adb devices 2>$null | Where-Object { $_ -match "\tdevice$" }
    if ($devices.Count -gt 0) {
        Write-Host "Connected carbox detected. Installing..." -ForegroundColor Cyan
        & $adb install -r $targetApk
        Write-Host "Installed successfully on carbox!" -ForegroundColor Green
    } else {
        Write-Host "No carbox connected right now. Run .\connect.ps1 when connected." -ForegroundColor Gray
    }
    $ErrorActionPreference = $prevEAP
} else {
    Write-Host "Error: APK not found." -ForegroundColor Red
    exit 1
}
