$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$modelDir = Join-Path $repoRoot "models"
$modelPath = Join-Path $modelDir "gemma-4-E4B-it.litertlm"
$releaseBaseUrl = "https://github.com/th9rain/laobai-gemma-demo/releases/download/model-weights-v1"
$partNames = @(
  "gemma-4-E4B-it.litertlm.part01",
  "gemma-4-E4B-it.litertlm.part02"
)
$expectedSize = 3659530240
$expectedSha256 = "0B2A8980CE155FD97673D8E820B4D29D9C7D99B8FA6806F425D969B145BD52E0"

New-Item -ItemType Directory -Force -Path $modelDir | Out-Null

if (Test-Path $modelPath) {
  $currentSize = (Get-Item $modelPath).Length
  if ($currentSize -eq $expectedSize) {
    $currentHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $modelPath).Hash
    if ($currentHash -eq $expectedSha256) {
      Write-Host "Model already exists and passed SHA-256 verification: $modelPath"
      exit 0
    }
  }
  Write-Host "Existing model is incomplete or invalid; replacing it."
  Remove-Item -LiteralPath $modelPath -Force
}

$partPaths = @()
foreach ($partName in $partNames) {
  $partPath = Join-Path $modelDir $partName
  $partUrl = "$releaseBaseUrl/$partName"
  Write-Host "Downloading $partName from the repository release..."
  curl.exe -L --fail --continue-at - --output $partPath $partUrl
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to download $partName"
  }
  $partPaths += $partPath
}

Write-Host "Combining model parts..."
$output = [System.IO.File]::Create($modelPath)
try {
  foreach ($partPath in $partPaths) {
    $input = [System.IO.File]::OpenRead($partPath)
    try {
      $input.CopyTo($output)
    } finally {
      $input.Dispose()
    }
  }
} finally {
  $output.Dispose()
}

$actualSize = (Get-Item $modelPath).Length
if ($actualSize -ne $expectedSize) {
  throw "Model size verification failed: expected $expectedSize bytes, got $actualSize"
}
$actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $modelPath).Hash
if ($actualSha256 -ne $expectedSha256) {
  throw "Model SHA-256 verification failed: expected $expectedSha256, got $actualSha256"
}

foreach ($partPath in $partPaths) {
  Remove-Item -LiteralPath $partPath -Force
}
Write-Host "Done and verified: $modelPath"
