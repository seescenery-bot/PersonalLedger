# 从已连接的小米 14 上抓取微信 / 支付宝的**当前活跃通知**，提取标题与正文。
#
# 为什么需要它：
#   微信 / 支付宝不会开放记账接口，只能从通知栏侧信道读取。
#   通知内容无法回溯（系统通知归档默认是空的），所以必须在通知出现的那一刻抓。
#
# 用法：
#   1. 在手机上完成一次「会产生通知」的操作（收款、支付、退款等）
#   2. 手机保持解锁、不要清通知栏
#   3. 运行： pwsh -File tools\capture-notifs.ps1
#   4. 结果追加写入 tools\notif-samples.txt
#
# 只读取、不注入任何输入事件，不修改手机任何状态。

param(
    [string]$OutFile = 'notif-samples.txt',
    [string]$DeviceId = ''
)

$ErrorActionPreference = 'Stop'

# adb 输出是 UTF-8，而中文 Windows 的默认控制台编码是 GBK，
# 不显式指定会把「支付成功」解码成乱码，导致样本全部作废。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
# 需要 adb 在 PATH 里
$adb = 'adb'
if (-not (Get-Command $adb -ErrorAction SilentlyContinue)) { throw "找不到 adb，请把 platform-tools 加入 PATH" }

$adbArgs = @()
if ($DeviceId -ne '') { $adbArgs += @('-s', $DeviceId) }

# 只关心这两个 App
$targets = @{
    'com.tencent.mm'               = 'WECHAT'
    'com.eg.android.AlipayGphone'  = 'ALIPAY'
}

Write-Host '读取通知列表...'
$lines = & $adb @adbArgs shell dumpsys notification --noredact 2>&1

$samples = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -notmatch '^\s*NotificationRecord\(') { continue }
    if ($lines[$i] -notmatch 'pkg=([\w\.]+)') { continue }
    $pkg = $matches[1]
    if (-not $targets.ContainsKey($pkg)) { continue }

    # 收集本条记录（直到下一条 NotificationRecord）
    $block = @()
    for ($j = $i; $j -lt $lines.Count; $j++) {
        if ($j -gt $i -and $lines[$j] -match '^\s*NotificationRecord\(') { break }
        $block += $lines[$j]
        if ($block.Count -gt 200) { break }
    }
    $text = ($block -join "`n")

    $title = ''
    $body  = ''
    $channelId = ''
    $channelName = ''

    if ($text -match "android\.title=(?:String|SpannableString) \((.*)\)") { $title = $matches[1] }
    if ($text -match "android\.text=(?:String|SpannableString) \(([\s\S]*?)\)\r?\n") { $body = $matches[1] }
    if ($text -match "mId='([^']*)'") { $channelId = $matches[1] }
    if ($text -match "mName=([^,]*)") { $channelName = $matches[1] }

    if ($title -eq '' -and $body -eq '') { continue }

    $samples += [pscustomobject]@{
        App         = $targets[$pkg]
        Package     = $pkg
        Title       = $title.Trim()
        Text        = $body.Trim()
        ChannelId   = $channelId
        ChannelName = $channelName.Trim()
    }
}

if ($samples.Count -eq 0) {
    Write-Host '没有捕获到微信 / 支付宝的活跃通知。'
    Write-Host '提示：通知必须还挂在通知栏里。请在完成支付/收款后立刻运行本脚本。'
    exit 0
}

$stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("")
[void]$sb.AppendLine("# ===== 采集于 $stamp =====")
foreach ($s in $samples) {
    [void]$sb.AppendLine("[app=$($s.App)]")
    [void]$sb.AppendLine("[package=$($s.Package)]")
    [void]$sb.AppendLine("[channel_id=$($s.ChannelId)]")
    [void]$sb.AppendLine("[channel_name=$($s.ChannelName)]")
    [void]$sb.AppendLine("title=$($s.Title)")
    [void]$sb.AppendLine("text=$($s.Text)")
    [void]$sb.AppendLine("expect=")
    [void]$sb.AppendLine("")
}

# 用 .NET 显式 UTF-8（无 BOM）追加，避免系统代码页把中文写坏
[System.IO.File]::AppendAllText(
    $OutFile,
    $sb.ToString(),
    (New-Object System.Text.UTF8Encoding($false))
)
Write-Host ("已捕获 " + $samples.Count + " 条，追加写入：" + $OutFile)
$samples | Format-List
