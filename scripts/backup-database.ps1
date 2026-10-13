$ErrorActionPreference = "Stop"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpPath = Join-Path $PSScriptRoot "..\database\backup\smart_assessment_ai-$timestamp.sql"
New-Item -ItemType Directory -Path (Split-Path -Parent $dumpPath) -Force | Out-Null
& pg_dump -d smart_assessment_ai -f $dumpPath
Write-Host "Database backup created: $dumpPath"
