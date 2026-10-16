# Migrations

Apply the SQL migrations once, in numeric order, after initializing a new database
with `database/smart_assessment_ai.sql`. Existing installations must first be at V002;
V003 through V009 run inside transactions and fail when target objects already exist rather than silently
being reapplied.

For isolated PostgreSQL verification on Windows, run:

```powershell
.\database\tests\run_migration_tests.ps1 `
  -PostgreSqlBin "C:\Program Files\PostgreSQL\16\bin" `
  -HostName 127.0.0.1 -Port 5432 -Username postgres
```

The script creates uniquely named `saa_phase05_test_*` and
`saa_phase05_rollback_*` databases, runs schema, migrations and assertions, then
drops only those databases. It never accepts an application database name. Supply
connection credentials through the standard libpq password file/environment; the
script uses non-interactive connections.

The harness is intended for a disposable PostgreSQL instance. Its default
PostgreSQL 16 client path does not select a server or database automatically;
use a separate test cluster/port for isolated verification.

If a migration fails before commit, PostgreSQL rolls back its transaction. After a
successful commit, do not remove these tables or attempt an automatic destructive
down migration: restore a verified backup or prepare a separately reviewed,
data-preserving corrective migration. V003/V004 have no production rollout
approval; test evidence does not resolve the outstanding release, legacy-backfill,
or test-version governance decisions.
