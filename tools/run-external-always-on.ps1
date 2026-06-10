$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
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
  $env:LAOBAI_DEMO_PORT = "4174"
  $env:LAOBAI_EXTERNAL_BASE_URL = "http://127.0.0.1:4174"
  if (-not $env:LAOBAI_HEADLESS) {
    $env:LAOBAI_HEADLESS = "0"
  }

  $server = Start-Process -FilePath "node" `
    -ArgumentList @(".\tools\demo-server.mjs", "--host", "127.0.0.1", "--port", "4174", "--no-open") `
    -WorkingDirectory $repoRoot `
    -WindowStyle Hidden `
    -PassThru

  try {
    Start-Sleep -Seconds 2
    node .\tools\external-always-on-runner.mjs
  } finally {
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
  }
} finally {
  Pop-Location
}
