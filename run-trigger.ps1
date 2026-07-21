$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $repoRoot
try {
  Write-Host "Running 京医通 workflow with historical cloud-plan replay..." -ForegroundColor Cyan
  Write-Host "No cloud model will be called; execution stops before 确认挂号." -ForegroundColor Yellow
  node .\tools\mobile-workflow-runner.mjs trigger
  if ($LASTEXITCODE -ne 0) { throw "Trigger workflow failed." }
} finally {
  Pop-Location
}
