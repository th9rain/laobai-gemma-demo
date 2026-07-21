$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $repoRoot
try {
  Write-Host "Running Always-on form workflow at 390x844..." -ForegroundColor Cyan
  Write-Host "The workflow will stop before 提交报名." -ForegroundColor Yellow
  node .\tools\mobile-workflow-runner.mjs always-on
  if ($LASTEXITCODE -ne 0) { throw "Always-on workflow failed." }
} finally {
  Pop-Location
}
