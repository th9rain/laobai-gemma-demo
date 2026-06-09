$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$modelDir = Join-Path $repoRoot "models"
$modelPath = Join-Path $modelDir "gemma-4-E4B-it.litertlm"
$modelUrl = "https://files.imxbx.cloud/gemma4%20hackathon/gemma-4-E4B-it.litertlm"

New-Item -ItemType Directory -Force -Path $modelDir | Out-Null

if (Test-Path $modelPath) {
  $currentSize = (Get-Item $modelPath).Length
  if ($currentSize -ge 3659530240) {
    Write-Host "Model already exists: $modelPath"
    exit 0
  }
  Write-Host "Partial model exists: $currentSize bytes. Resuming download."
}

Write-Host "Downloading Gemma LiteRT model to $modelPath"
Write-Host "This file is about 3.66GB and is ignored by git."
curl.exe -L --fail --continue-at - --output $modelPath $modelUrl
Write-Host "Done: $modelPath"
