$ErrorActionPreference = "Stop"

$backendDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$startScript = Join-Path $backendDir "start-receiptory-backend.ps1"
$envPath = Join-Path $backendDir ".env"
$exampleEnvPath = Join-Path $backendDir ".env.example"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Error "Node.js 18 or newer is required. Install Node.js, then run this script again."
}

if (-not (Test-Path $envPath)) {
    Copy-Item $exampleEnvPath $envPath
    Write-Host "Created backend\.env. Edit OPENAI_API_KEY in that file before the startup task can serve requests."
}

$taskName = "Receiptory AI Backend"
$action = New-ScheduledTaskAction `
    -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$startScript`""
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask `
    -TaskName $taskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Description "Runs the Receiptory local AI backend when this Windows user logs in." `
    -Force | Out-Null

Write-Host "Installed startup task: $taskName"
Write-Host "To start it now, run:"
Write-Host "Start-ScheduledTask -TaskName `"$taskName`""
