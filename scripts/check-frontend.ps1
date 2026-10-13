$ErrorActionPreference = "Stop"
Write-Host "Checking frontend health..."
Invoke-WebRequest -Uri "http://localhost:3000" -UseBasicParsing | Out-Null
Write-Host "Frontend is reachable."
