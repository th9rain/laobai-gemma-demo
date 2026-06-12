$ErrorActionPreference = "Stop"

Write-Host "LaoBai Trigger will run the real local Gemma E4B computer-use chain." -ForegroundColor Cyan
Write-Host "The browser may take 1-3 minutes because the LiteRT model is loaded for multiple screenshot rounds." -ForegroundColor Cyan
Write-Host "Do not manually open the external runner URL. Watch the Chromium window opened by this script." -ForegroundColor Yellow

$env:LAOBAI_SKIP_LOCAL_CONFIG = "1"
$env:LAOBAI_PLANNER_ENDPOINT = ""
$env:LAOBAI_PLANNER_MODEL = ""
$env:LAOBAI_PLANNER_API_KEY = ""
$env:LAOBAI_EDGE_ENDPOINT = ""
$env:LAOBAI_EDGE_MODEL = ""
$env:LAOBAI_EDGE_API_KEY = ""
$env:LAOBAI_LOCAL_GEMMA_ENABLED = "1"
$env:LAOBAI_LOCAL_GEMMA_MODEL_PATH = "models/gemma-4-E4B-it.litertlm"
$env:LAOBAI_LOCAL_GEMMA_PYTHON = ".venv/Scripts/python.exe"
$env:LAOBAI_DEMO_PORT = "4175"
$env:LAOBAI_EXTERNAL_BASE_URL = "http://127.0.0.1:4175"
if (-not $env:LAOBAI_HEADLESS) {
  $env:LAOBAI_HEADLESS = "0"
}

Get-CimInstance Win32_Process -Filter "name = 'node.exe'" |
  Where-Object {
    $_.CommandLine -match "demo-server\.mjs" -and
    $_.CommandLine -match "--port\s+4175"
  } |
  ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }

$server = Start-Process -FilePath "node" `
  -ArgumentList @(".\tools\demo-server.mjs", "--host", "127.0.0.1", "--port", "4175", "--no-open") `
  -WorkingDirectory (Get-Location) `
  -WindowStyle Hidden `
  -PassThru

try {
  Start-Sleep -Seconds 2
  Write-Host "Server ready on 127.0.0.1:4175." -ForegroundColor Green
  Write-Host "Starting Trigger runner: cached cloud planner + local Gemma screenshot actions..." -ForegroundColor Green
  Write-Host "A separate Chromium window will open. That window is the real runner-controlled demo." -ForegroundColor Green
  node .\tools\external-trigger-runner.mjs
} finally {
  Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
}
