param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$RemoteApk
)

$ErrorActionPreference = "Stop"
$adbPath = (Get-Command adb).Source
$stdoutPath = Join-Path ([System.IO.Path]::GetTempPath()) "laobai-pm-install.out"
$stderrPath = Join-Path ([System.IO.Path]::GetTempPath()) "laobai-pm-install.err"

$processArguments = @{
    FilePath = $adbPath
    ArgumentList = @("-s", $Serial, "shell", "pm", "install", "-r", "-t", "-g", $RemoteApk)
    PassThru = $true
    WindowStyle = "Hidden"
    RedirectStandardOutput = $stdoutPath
    RedirectStandardError = $stderrPath
}
$process = Start-Process @processArguments

$allowedPackages = @(
    "com.miui.packageinstaller",
    "com.miui.securitycenter",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.permissioncontroller",
    "android"
)
$positiveText = "^(安装|继续安装|允许|继续|确定|确认)$"
$lastTap = ""

for ($attempt = 0; $attempt -lt 20 -and -not $process.HasExited; $attempt++) {
    Start-Sleep -Milliseconds 700
    & adb -s $Serial shell uiautomator dump /sdcard/laobai-install-ui.xml 1>$null 2>$null
    $rawXml = & adb -s $Serial shell cat /sdcard/laobai-install-ui.xml 2>$null
    if (-not $rawXml) {
        continue
    }

    try {
        [xml]$document = $rawXml
        $nodes = $document.SelectNodes("//node[@enabled='true']")
        foreach ($node in $nodes) {
            $packageName = $node.GetAttribute("package")
            $text = $node.GetAttribute("text").Trim()
            $description = $node.GetAttribute("content-desc").Trim()
            $label = if ($text) { $text } else { $description }
            if (
                $allowedPackages -notcontains $packageName -or
                $label -notmatch $positiveText
            ) {
                continue
            }

            $bounds = $node.GetAttribute("bounds")
            $match = [regex]::Match($bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
            if (-not $match.Success) {
                continue
            }

            $tapKey = "$packageName|$label|$bounds"
            if ($tapKey -eq $lastTap) {
                continue
            }

            $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
            $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
            Write-Output "TAPPING package=$packageName label=$label bounds=$bounds"
            & adb -s $Serial shell input tap ([int]$x) ([int]$y)
            $lastTap = $tapKey
            break
        }
    }
    catch {
        Write-Output "INSTALL_UI_PARSE_RETRY attempt=$attempt"
    }
}

if (-not $process.HasExited) {
    $process.WaitForExit(30000) | Out-Null
}

if (-not $process.HasExited) {
    throw "Package installer did not finish within the expected time"
}

$stdout = if (Test-Path -LiteralPath $stdoutPath) {
    Get-Content -LiteralPath $stdoutPath -Raw
} else {
    ""
}
$stderr = if (Test-Path -LiteralPath $stderrPath) {
    Get-Content -LiteralPath $stderrPath -Raw
} else {
    ""
}

Write-Output "PM_INSTALL_EXIT_CODE=$($process.ExitCode)"
if ($stdout) { Write-Output $stdout.Trim() }
if ($stderr) { Write-Output $stderr.Trim() }
exit $process.ExitCode
