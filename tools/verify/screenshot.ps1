<#
.SYNOPSIS
    把 Debug APK 装到已启动的模拟器/真机上，启动主界面并截图。

.DESCRIPTION
    Phase 0 起的每一步验收都用它：安装 → 启动 → 等待首帧 → 截图到 docs/screenshots/。
    需要先启动模拟器（emulator -avd <name> ...）。

.EXAMPLE
    pwsh -File tools/verify/screenshot.ps1 -Name 3.0-phase0
#>
param(
    [string]$Apk = 'app/build/outputs/apk/debug/app-debug.apk',
    [string]$Package = 'com.lasergrbl.android',
    [string]$Activity = 'com.lasergrbl.android/.MainActivity',
    [string]$Name = 'shot',
    [int]$SettleSeconds = 6,
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# SDK 位置：ANDROID_SDK → ANDROID_HOME → local.properties → 本机默认
$sdkRoot = @($env:ANDROID_SDK, $env:ANDROID_HOME) | Where-Object { $_ } | Select-Object -First 1
if (-not $sdkRoot) {
    $localProps = Join-Path $repo 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $line = Select-String -LiteralPath $localProps -Pattern '^sdk\.dir=' | Select-Object -First 1
        if ($line) { $sdkRoot = ($line.Line -replace '^sdk\.dir=', '') -replace '\\\\', '\' }
    }
}
if (-not $sdkRoot) { $sdkRoot = 'D:\ANDROID_SDK' }

$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "找不到 adb：$adb（请检查 ANDROID_HOME / local.properties）" }

$apkPath = Join-Path $repo $Apk
if (-not (Test-Path -LiteralPath $apkPath)) { throw "APK not found: $apkPath" }

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

# adb 会把「进度/提示」写进 stderr，PS 5.1 会当成错误记录并让 $ErrorActionPreference='Stop' 抛异常；
# 设备检查做完之后改为 Continue，关键步骤用退出码显式判断。
$ErrorActionPreference = 'Continue'

Write-Host "[1/5] 等待设备…"
& $adb @adbArgs wait-for-device | Out-Null
$booted = ((& $adb @adbArgs shell getprop sys.boot_completed 2>$null) -join '').Trim()
if ($booted -ne '1') { throw "设备尚未启动完成（sys.boot_completed=$booted）" }
$sdk = ((& $adb @adbArgs shell getprop ro.build.version.sdk 2>$null) -join '').Trim()
Write-Host "      设备 API $sdk"

Write-Host "[2/5] 安装 $apkPath"
$installOut = & $adb @adbArgs install -r -t $apkPath 2>&1
$installOut | ForEach-Object { "      $_" }
if ($LASTEXITCODE -ne 0 -or ($installOut -join ' ') -notmatch 'Success') { throw "安装失败" }

Write-Host "[3/5] 启动 $Activity"
& $adb @adbArgs shell am start -n $Activity 2>&1 | ForEach-Object { "      $_" }

Write-Host "[4/5] 等待 $SettleSeconds 秒让玻璃首帧稳定…"
Start-Sleep -Seconds $SettleSeconds

$outDir = Join-Path $repo 'docs\screenshots'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = Join-Path $outDir "$Name.png"

Write-Host "[5/5] 截图 -> $outFile"
# 注意：不能用 `adb exec-out screencap -p` 直接接 PowerShell 变量 —— PS 会把二进制当文本解码，PNG 会损坏。
# 走「设备内落盘 + pull」这条稳的路。
$remote = '/sdcard/igrbl-verify-shot.png'
& $adb @adbArgs shell screencap -p $remote 2>&1 | Out-Null
& $adb @adbArgs pull $remote $outFile 2>&1 | ForEach-Object { "      $_" }
& $adb @adbArgs shell rm -f $remote 2>&1 | Out-Null
if (-not (Test-Path -LiteralPath $outFile)) { throw "截图失败：$outFile 不存在" }
"      完成：$((Get-Item -LiteralPath $outFile).Length) bytes"

Write-Output ''
Write-Output '--- 玻璃能力探针（关键：RuntimeShader 需要 API 33+）---'
& $adb @adbArgs shell getprop ro.build.version.sdk
& $adb @adbArgs shell dumpsys SurfaceFlinger --latency 2>$null | Select-Object -First 1
Write-Output '--- 崩溃检查 ---'
& $adb @adbArgs logcat -d -t 200 '*:E' | Select-String -Pattern 'FATAL|AndroidRuntime|lasergrbl' | Select-Object -First 20
