$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$venvDir = Join-Path $repoRoot ".venv"
$pythonExe = Join-Path $venvDir "Scripts\python.exe"

if (-not (Test-Path $pythonExe)) {
  python -m venv $venvDir
}

& $pythonExe -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) { throw "Failed to upgrade pip." }
& $pythonExe -m pip install "litert-lm==0.13.1"
if ($LASTEXITCODE -ne 0) { throw "Failed to install litert-lm." }
& $pythonExe -c "import litert_lm; print('litert-lm import verified')"
if ($LASTEXITCODE -ne 0) { throw "litert-lm import verification failed." }

Write-Host "LiteRT-LM environment ready: $pythonExe"
