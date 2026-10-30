# Watch WeChat / Alipay notifications for a period of time and record every new one.
#
# Why: system notification archive is empty on this device, so a notification that
# is dismissed cannot be recovered. We must snapshot while it is still in the shade.
#
# Usage:
#   pwsh -File tools\watch-notifs.ps1 -Seconds 600
#
# Read-only: never taps, never changes device state.

param(
    [int]$Seconds = 300,
    [string]$OutFile = 'notif-watch.txt',
    [string]$DeviceId = ''
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 需要 adb 在 PATH 里；或用 -DeviceId 指定设备
$adb = 'adb'
$pkgs = @('com.tencent.mm', 'com.eg.android.AlipayGphone')

$seen = @{}
$hits = 0
$deadline = (Get-Date).AddSeconds($Seconds)

Write-Output "watching for $Seconds s ..."

while ((Get-Date) -lt $deadline) {
    $lines = & $adb -s $DeviceId shell dumpsys notification --noredact 2>$null
    if ($null -eq $lines) { Start-Sleep -Milliseconds 500; continue }
    $txt = ($lines -join "`n")

    foreach ($pkg in $pkgs) {
        $pat = 'NotificationRecord\([^\r\n]*pkg=' + [regex]::Escape($pkg) + '[\s\S]*?(?=\n\s*NotificationRecord\(|\z)'
        foreach ($m in [regex]::Matches($txt, $pat)) {
            $s = $m.Value
            $title = [regex]::Match($s, 'android\.title=.*?\((.*?)\)\r?\n').Groups[1].Value.Trim()
            $body = [regex]::Match($s, 'android\.text=.*?\(([\s\S]*?)\)\r?\n').Groups[1].Value.Trim()
            if ($title -eq '' -and $body -eq '') { continue }
            $key = "$pkg|$title|$body"
            if ($seen.ContainsKey($key)) { continue }
            $seen[$key] = $true
            $hits++

            $ch = [regex]::Match($s, "mId='([^']*)'").Groups[1].Value
            $rec = @(
                "",
                "# ===== " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + " =====",
                "[app=$pkg]",
                "[channel_id=$ch]",
                "title=$title",
                "text=$body",
                ""
            ) -join "`n"

            [System.IO.File]::AppendAllText(
                $OutFile, $rec, (New-Object System.Text.UTF8Encoding($false))
            )
            Write-Output ("[HIT] $pkg | $title | $body")
        }
    }
    Start-Sleep -Milliseconds 700
}

Write-Output "done. total new notifications captured: $hits"
Write-Output "output: $OutFile"
