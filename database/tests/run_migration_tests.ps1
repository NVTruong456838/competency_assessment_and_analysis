param(
    [string]$PostgreSqlBin = "$env:ProgramFiles\PostgreSQL\16\bin",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 5432,
    [string]$Username = "postgres",
    [switch]$RunBackendIntegration
)

$ErrorActionPreference = "Stop"
$psql = Join-Path $PostgreSqlBin "psql.exe"
$createdb = Join-Path $PostgreSqlBin "createdb.exe"
$dropdb = Join-Path $PostgreSqlBin "dropdb.exe"

foreach ($tool in @($psql, $createdb, $dropdb)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required PostgreSQL client was not found: $tool"
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$connectionArgs = @("-h", $HostName, "-p", "$Port", "-U", $Username, "-w")
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$testDatabase = "saa_phase05_test_$suffix"
$rollbackDatabase = "saa_phase05_rollback_$suffix"
$injectedMigration = $null
$createdDatabases = [System.Collections.Generic.List[string]]::new()
$originalTestPostgresUrl = $env:TEST_POSTGRES_URL
$originalTestPostgresUsername = $env:TEST_POSTGRES_USERNAME
$originalTestPostgresPassword = $env:TEST_POSTGRES_PASSWORD
$originalJavaToolOptions = $env:JAVA_TOOL_OPTIONS

function Invoke-PsqlFile {
    param([string]$Database, [string]$FilePath)

    & $psql @connectionArgs -d $Database -v ON_ERROR_STOP=1 -f $FilePath
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed for '$FilePath' on isolated database '$Database' (exit $LASTEXITCODE)."
    }
}

function Invoke-PsqlScalar {
    param([string]$Database, [string]$Sql)

    $result = & $psql @connectionArgs -d $Database -v ON_ERROR_STOP=1 -Atc $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "psql query failed on isolated database '$Database' (exit $LASTEXITCODE)."
    }
    return (($result | Out-String).Trim())
}

try {
    $serverVersion = Invoke-PsqlScalar "postgres" "SHOW server_version;"
    Write-Output "PostgreSQL server: $serverVersion"

    & $createdb @connectionArgs $testDatabase
    if ($LASTEXITCODE -ne 0) { throw "Could not create isolated database '$testDatabase'." }
    $createdDatabases.Add($testDatabase)

    $migrationFiles = @(
        "database\smart_assessment_ai.sql",
        "database\migrations\V001__add_phone_number_to_users.sql",
        "database\migrations\V002__allow_email_or_phone_registration.sql",
        "database\migrations\V003__taxonomy_release_provenance.sql",
        "database\migrations\V004__question_bank_pattern_foundation.sql",
        "database\migrations\V005__question_version_assets_and_history.sql",
        "database\migrations\V006__assessment_blueprints_and_attempt_integrity.sql",
        "database\migrations\V007__assessment_optional_branch_contract.sql",
        "database\migrations\V008__question_group_versions.sql",
        "database\migrations\V009__scoring_runs_and_analytics.sql"
    )
    foreach ($relativePath in $migrationFiles) {
        $path = Join-Path $repoRoot $relativePath
        Write-Output "Applying $relativePath"
        Invoke-PsqlFile $testDatabase $path
    }

    Invoke-PsqlFile $testDatabase (Join-Path $PSScriptRoot "V003_V004_constraints.sql")
    Write-Output "Schema and constraint assertions: PASS"
    $v006State = Invoke-PsqlScalar $testDatabase @"
SELECT to_regclass('public.test_blueprints') IS NOT NULL
   AND EXISTS (
       SELECT 1 FROM information_schema.columns
       WHERE table_schema = 'public'
         AND table_name = 'attempts'
         AND column_name = 'ends_at'
   )
   AND EXISTS (
       SELECT 1 FROM pg_trigger
       WHERE tgname = 'trg_test_items_immutable'
   );
"@
    if ($v006State -ne "t") {
        throw "V006 assessment schema assertions failed."
    }
    Write-Output "V006 assessment schema assertions: PASS"
    $v007State = Invoke-PsqlScalar $testDatabase @"
SELECT to_regclass('public.assessment_optional_section_groups') IS NOT NULL
   AND to_regclass('public.assessment_optional_section_branches') IS NOT NULL
   AND to_regclass('public.assessment_optional_section_branch_sections') IS NOT NULL
   AND to_regclass('public.test_blueprint_optional_branches') IS NOT NULL
   AND EXISTS (
       SELECT 1 FROM pg_trigger
       WHERE tgname = 'trg_blueprint_optional_branch_contract_choice'
   );
"@
    if ($v007State -ne "t") {
        throw "V007 optional-branch schema assertions failed."
    }
    Write-Output "V007 optional-branch schema assertions: PASS"
    $v008State = Invoke-PsqlScalar $testDatabase @"
SELECT to_regclass('public.question_groups') IS NOT NULL
   AND to_regclass('public.question_group_versions') IS NOT NULL
   AND to_regclass('public.question_group_version_items') IS NOT NULL
   AND to_regclass('public.question_group_version_reviews') IS NOT NULL
   AND EXISTS (
       SELECT 1 FROM pg_trigger
       WHERE tgname = 'trg_question_group_version_immutable'
   )
   AND EXISTS (
       SELECT 1 FROM pg_trigger
       WHERE tgname = 'trg_question_group_publication_gate'
   )
   AND EXISTS (
       SELECT 1 FROM pg_trigger
       WHERE tgname = 'trg_test_item_group_consistency'
   )
   AND EXISTS (
       SELECT 1 FROM information_schema.columns
       WHERE table_schema = 'public'
         AND table_name = 'test_items'
         AND column_name = 'question_group_version_id'
   );
"@
    if ($v008State -ne "t") {
        throw "V008 Question Group schema assertions failed."
    }
    Write-Output "V008 Question Group schema assertions: PASS"
    $v009State = Invoke-PsqlScalar $testDatabase @"
SELECT to_regclass('public.scoring_runs') IS NOT NULL
   AND to_regclass('public.scoring_question_results') IS NOT NULL
   AND to_regclass('public.scoring_section_results') IS NOT NULL
   AND to_regclass('public.scoring_skill_results') IS NOT NULL
   AND to_regclass('public.scoring_competency_results') IS NOT NULL
   AND to_regclass('public.scoring_dimension_results') IS NOT NULL
   AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_scoring_runs_immutable')
   AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_scoring_question_results_immutable');
"@
    if ($v009State -ne "t") {
        throw "V009 scoring schema assertions failed."
    }
    Write-Output "V009 scoring schema assertions: PASS"

    if ($RunBackendIntegration) {
        $env:TEST_POSTGRES_URL = "jdbc:postgresql://${HostName}:${Port}/${testDatabase}"
        $env:TEST_POSTGRES_USERNAME = $Username
        $env:TEST_POSTGRES_PASSWORD = $env:PGPASSWORD
        $env:JAVA_TOOL_OPTIONS = (($originalJavaToolOptions, "-Duser.timezone=UTC") -join " ").Trim()
        $mavenWrapper = Join-Path $repoRoot "backend\mvnw.cmd"
        & $mavenWrapper -f (Join-Path $repoRoot "backend\pom.xml") `
            "-Dtest=QuestionFoundationPostgresIntegrationTest,AssessmentPostgresIntegrationTest" test
        if ($LASTEXITCODE -ne 0) {
            throw "PostgreSQL integration tests failed (exit $LASTEXITCODE)."
        }
        Write-Output "Question Bank and assessment PostgreSQL integration tests: PASS"
    }

    $repeatMigration = $null
    try {
        $repeatMigration = & $psql @connectionArgs -d $testDatabase -v ON_ERROR_STOP=1 `
            -f (Join-Path $repoRoot "database\migrations\V003__taxonomy_release_provenance.sql") 2>&1
    }
    catch {
        $repeatMigration = $_.ToString()
    }
    $repeatExitCode = $LASTEXITCODE
    if ($repeatExitCode -eq 0 -or ($repeatMigration | Out-String) -notmatch "target objects already exist") {
        throw "Reapplying V003 did not fail with its already-applied migration guard."
    }
    Write-Output "Repeated migration detection: PASS"

    & $createdb @connectionArgs $rollbackDatabase
    if ($LASTEXITCODE -ne 0) { throw "Could not create rollback probe database '$rollbackDatabase'." }
    $createdDatabases.Add($rollbackDatabase)
    foreach ($relativePath in @(
        "database\smart_assessment_ai.sql",
        "database\migrations\V001__add_phone_number_to_users.sql",
        "database\migrations\V002__allow_email_or_phone_registration.sql"
    )) {
        Invoke-PsqlFile $rollbackDatabase (Join-Path $repoRoot $relativePath)
    }

    $v003Path = Join-Path $repoRoot "database\migrations\V003__taxonomy_release_provenance.sql"
    $v003Text = [System.IO.File]::ReadAllText($v003Path)
    $commitPattern = [System.Text.RegularExpressions.Regex]::new("(?m)^COMMIT;\s*$")
    $injectedText = $commitPattern.Replace($v003Text, "SELECT 1 / 0;`r`nCOMMIT;", 1)
    if ($injectedText -eq $v003Text) {
        throw "Could not prepare the rollback probe from V003."
    }
    $injectedMigration = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($injectedMigration, $injectedText)

    try {
        & $psql @connectionArgs -d $rollbackDatabase -v ON_ERROR_STOP=1 -f $injectedMigration 2>&1 | Out-Null
    }
    catch {
        # The injected SQL error is expected; verify the database was rolled back below.
    }
    if ($LASTEXITCODE -eq 0) {
        throw "Injected migration failure unexpectedly succeeded."
    }
    $rollbackState = Invoke-PsqlScalar $rollbackDatabase @"
SELECT to_regclass('public.taxonomy_catalogs') IS NULL
   AND NOT EXISTS (
       SELECT 1 FROM information_schema.columns
       WHERE table_schema = 'public'
         AND table_name = 'test_versions'
         AND column_name = 'taxonomy_release_id'
   );
"@
    if ($rollbackState -ne "t") {
        throw "A failed V003 probe left partial schema changes behind."
    }
    Write-Output "Failed migration transaction rollback: PASS"
    Write-Output "All isolated migration checks: PASS"
}
finally {
    $env:TEST_POSTGRES_URL = $originalTestPostgresUrl
    $env:TEST_POSTGRES_USERNAME = $originalTestPostgresUsername
    $env:TEST_POSTGRES_PASSWORD = $originalTestPostgresPassword
    $env:JAVA_TOOL_OPTIONS = $originalJavaToolOptions
    if ($injectedMigration -and (Test-Path -LiteralPath $injectedMigration)) {
        Remove-Item -LiteralPath $injectedMigration -Force
    }
    foreach ($database in $createdDatabases) {
        & $dropdb @connectionArgs --if-exists $database
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove isolated database '$database'."
        }
    }
}
