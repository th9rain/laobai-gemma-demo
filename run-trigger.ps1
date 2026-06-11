$ErrorActionPreference = "Stop"

Write-Host "LaoBai Trigger will run the real local Gemma E4B computer-use chain." -ForegroundColor Cyan
Write-Host "The browser may take 1-3 minutes because the LiteRT model is loaded for multiple screenshot rounds." -ForegroundColor Cyan

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

$server = Start-Process -FilePath "node" `
  -ArgumentList @(".\tools\demo-server.mjs", "--host", "127.0.0.1", "--port", "4175", "--no-open") `
  -WorkingDirectory (Get-Location) `
  -WindowStyle Hidden `
  -PassThru

try {
  Start-Sleep -Seconds 2
  Write-Host "Server ready: http://127.0.0.1:4175/trigger-health.html?external=1" -ForegroundColor Green
  Write-Host "Starting Trigger runner: cached cloud planner + local Gemma screenshot actions..." -ForegroundColor Green
  node .\tools\external-trigger-runner.mjs
} finally {
  Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
}
