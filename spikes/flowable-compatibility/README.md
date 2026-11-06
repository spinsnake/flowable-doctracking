# P0 Flowable compatibility spike

Isolated executable proof for P0-10/P0-11; this is not the P1 backend or production BPMN. Candidate stack: Java 21, Spring Boot 4.0.9, Flowable OSS 8.0.0, PostgreSQL 17. Maven resolves exact transitive dependencies through the pinned Boot parent. Tested versions/results must be recorded only after a successful run.

Flowable 8 supports Boot 4 and drops Boot 3: [official release notes](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0), [Spring integration](https://www.flowable.com/open-source/docs/bpmn/ch05-Spring).

## Run in PowerShell

Prerequisites: Java 21 on `JAVA_HOME`/`PATH`, Maven 3.9+, Docker Compose (or an existing **disposable** PostgreSQL 17 database). Run from this directory:

```powershell
$env:SPIKE_DB_USER = 'flowable_spike'
$env:SPIKE_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:SPIKE_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/flowable_spike'
docker compose up -d --wait
mvn --batch-mode --no-transfer-progress clean test
docker compose stop
```

Keep the password in the same shell; starting a new shell with a different password does not change an already running database user. Environment values are never printed by the tests. To use an existing disposable database, supply its three `SPIKE_DB_*` variables and omit Compose. Do not target production or a shared business database. The database account needs `CREATE SCHEMA` permission. Each test run creates an isolated `p0_spike_<uuid>` schema and retains it for inspection; no existing schema is dropped or edited. Compose storage is ephemeral and stopping its container loses its database contents.

## Checks

`CompatibilityTest` boots the actual Spring context and PostgreSQL-backed engine, deploys executable XML, then checks:

1. Start process → complete a real user task → persist a BPMN timer → execute it → complete the next task → process ends.
2. Shared `DataSource` and `PlatformTransactionManager` identity; an injected failure rolls back both application inbox insert and engine task completion. Retrying commits inbox, completion, and ACK together.
3. Retry of an already committed event is rejected by PostgreSQL's inbox primary key and does not complete the next task.

The async executor is disabled. Tests use `moveTimerToExecutableJob` + `executeJob` to fire the persisted timer deterministically, without sleeping. This proves timer persistence/execution and transaction compatibility, not wall-clock acquisition, clustered executor behavior, throughput, full worker ordering, restart recovery, or complete Model A/B behavior; those belong to P2/P6.

Maven results: `target/surefire-reports/`. A failed/missing database is a test failure, never a skipped/green fallback to H2.

## Verification status

Passed on **2026-11-06 at 14:08:29 (Asia/Bangkok)** with:

| Component | Executed version |
|---|---|
| Java | Eclipse Temurin 21.0.13+9 |
| Maven | 3.9.11 |
| Spring Boot | 4.0.9 |
| Flowable OSS | 8.0.0 |
| PostgreSQL | 17.11, native Windows process |

Command, executed from this directory with `JAVA_HOME`/`PATH` and `SPIKE_DB_*` set:

```powershell
mvn --batch-mode --no-transfer-progress '-Dmaven.repo.local=D:\doc_flowable\.local\m2' test
```

Result: **BUILD SUCCESS — 3 tests, 0 failures, 0 errors, 0 skipped**. The Surefire text report at `target/surefire-reports/th.ac.mfu.doctracking.CompatibilityTest.txt` records a test duration of 15.12 seconds. Generated reports and the local Maven repository are not committed.

The run used a dedicated disposable PostgreSQL 17.11 cluster inside the workspace because the Docker daemon was unavailable. The Compose path/image was not executed. This verifies the pinned Java/Boot/Flowable versions against PostgreSQL 17.11 for the three checks above. It does not certify production schema migration, Google OAuth/email, worker restart behavior, automatic timer acquisition, complete Model A/B workflows, or frontend dependencies.
