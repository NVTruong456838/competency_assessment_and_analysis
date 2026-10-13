$ErrorActionPreference = "Stop"
Write-Host "Checking backend health..."
Invoke-WebRequest -Uri "http://localhost:8081/api/health" -UseBasicParsing | Out-Null
Write-Host "Backend is reachable."
