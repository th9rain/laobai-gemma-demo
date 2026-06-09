$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$venvDir = Join-Path $repoRoot ".venv"
$pythonExe = Join-Path $venvDir "Scripts\python.exe"

if (-not (Test-Path $pythonExe)) {
  python -m venv $venvDir
}

& $pythonExe -m pip install --upgrade pip
& $pythonExe -m pip install "litert-lm==0.13.1"

Write-Host "LiteRT-LM environment ready: $pythonExe"
