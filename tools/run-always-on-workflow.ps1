$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
  & .\.venv\Scripts\python.exe .\tools\always-on-workflow.py `
    --model .\models\gemma-4-E4B-it.litertlm
} finally {
  Pop-Location
}
