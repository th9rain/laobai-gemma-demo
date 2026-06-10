$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
  $screenshot = $args[0]
  if (-not $screenshot) {
    throw "请传入截图路径，例如：.\tools\run-always-on-workflow.ps1 C:\Temp\phone.png"
  }
  & .\.venv\Scripts\python.exe .\tools\always-on-workflow.py `
    --model .\models\gemma-4-E4B-it.litertlm `
    --screenshot $screenshot `
    --page 1 `
    --width 390 `
    --height 845
} finally {
  Pop-Location
}
