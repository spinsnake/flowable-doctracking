# Backup / restore: application + Flowable + private evidence

สถานะ: มี executable tooling และ local fixture tests; ยังไม่ได้ทำ full PostgreSQL/S3 backup/restore บน staging จริง เครื่องพัฒนามี native PostgreSQL server แต่ไม่มี `pg_dump`/`pg_restore` และ Docker daemon ใช้งานไม่ได้ จึงไม่มีค่า RPO/RTO ที่วัดจาก service restore จริง

## Consistency boundary

Application ledger/projection, Flyway, sessions, login notification jobs/attempts, workflow outbox/inbox, process mappings และ Flowable runtime/history อยู่ใน PostgreSQL database เดียวกัน ใช้ **full database `pg_dump --format=custom` หนึ่งครั้ง** ไม่ dump เฉพาะ app tables หรือ engine tables แยกเวลา แล้ว copy **private evidence store ทั้งหมด** ในช่วง writer fence เดียวกัน รวม finalized และ staged uploads เพราะ metadata อาจอ้าง staged content อยู่

ขั้นตอนนี้เป็น maintenance backup: หยุดทุก backend replica/worker/timer/cleanup และ external DB/object writers ก่อน dump จนหลัง manifest ตรวจผ่าน มี downtime ตามช่วง maintenance ห้ามอ้างว่า pg_dump และ S3 live copy ให้ distributed snapshot ขณะที่ยังมี writer `pg_stat_activity` check เป็นการตรวจประกอบ ไม่แทนสิทธิ์ fence writers และไม่กัน object writes จาก external service

เครื่อง operator ต้องมี Python 3.10+, `psql`, `pg_dump`, `pg_restore` รุ่นที่รองรับ server และ AWS CLI สำหรับ S3 กำหนด `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSFILE` ผ่าน private environment ไม่ใส่ password ใน command/connection URL ฐานใช้ schema ผ่าน default search_path ของ account: สำหรับระบบที่เปลี่ยน schema ให้กำหนด `PGOPTIONS=-c search_path=<app-schema>` ด้วย name ที่ตรวจแล้ว ใช้ account อ่าน DB ได้ครบ และ restore role ที่สร้าง objects/extensions ของ backup ได้

กำหนด backup destination นอก evidence root/source volume ที่มี capacity เพียงพอ owner-only ACL/encryption, transport encryption และ off-host copy/restore ownership ตาม policy ของ MFU Tool ไม่เข้ารหัสไฟล์ให้โดยอัตโนมัติ ไม่ควรเก็บ bundle ใน Git, artifact สาธารณะ หรือ directory เว็บ manifest มี evidence object identifiers และ database name ต้องถือเป็นข้อมูลส่วนตัวด้วย

## Create และ verify

```powershell
python scripts/operations.py backup --database doctracking --output '<private-new-backup-directory>' --release-id '<actual-release-id>' --evidence-root '<private-evidence-root>' --maintenance-confirmed
```

สำหรับ production S3 ใช้ dedicated bucket แทน `--evidence-root`:

```powershell
python scripts/operations.py backup --database doctracking --output '<private-new-backup-directory>' --release-id '<actual-release-id>' --s3-bucket '<private-bucket>' --maintenance-confirmed
python scripts/operations.py verify --bundle '<private-backup-directory>'
```

S3-compatible service เพิ่ม `--s3-endpoint https://approved-endpoint` เมื่อจำเป็น Tool ใช้ AWS credential provider chain ไม่พิมพ์ keys และไม่สร้าง public ACL/signed URLs Copy current objects ภายใต้ writer fence; ถ้าต้องสำรอง historical S3 versions, KMS key availability หรือ WORM/legal hold ให้จัดการเป็น policy/infra task แยก ห้ามอ้างว่า bundle เก็บ bucket configuration/IAM/version history

Tool ตรวจว่าไม่มี client session อื่นใน target database, output ต้องเป็น directory ใหม่, object keys เป็น generated UUID เท่านั้น, ไม่รับ symlink/recursive unknown files ตรวจ SHA-256/size ของ finalized evidence จาก DB เทียบ content และสร้าง manifest inventory SHA-256 ของ dump+objects หากมี temp/unknown files จะหยุดให้ operator ตรวจแทนการลบ file เอง หากคำสั่งล้มเหลว bundle ที่ไม่มี manifest ตรวจผ่านเป็น **incomplete** ห้ามใช้ restore และห้ามเปิด writes ก่อนตรวจผล/จัดการ maintenance ให้เรียบร้อย

manifest/checksum ตรวจ accidental corruption และ completeness; ไม่ใช่ digital signature ของผู้สำรอง ให้เก็บ manifest และ release record ใน controlled/audited storage ตรวจ dump ด้วย `pg_restore --list` และทำ restore drill จริง ไม่ถือ checksum ผ่านอย่างเดียวว่า recover service ได้

## Isolated restore

เตรียม database เปล่าชื่อใหม่ **ต่างจาก source** และ evidence directory เปล่าหรือ S3 bucket เปล่าแยกต่างหาก ใช้ private network/credentials ที่ไม่ให้แอป production เขียนได้ Tool ไม่มี DROP/`--clean`/overwrite path และไม่สร้าง database แทน operator

Preview ตรวจ bundle โดยไม่ติดต่อ DB/S3 หรือเขียน target:

```powershell
python scripts/operations.py restore --bundle '<private-backup-directory>' --target-database '<new_restore_database>' --evidence-root '<new-private-evidence-directory>'
```

Execute ต้องยืนยัน exact target เพิ่ม:

```powershell
python scripts/operations.py restore --bundle '<private-backup-directory>' --target-database '<new_restore_database>' --evidence-root '<new-private-evidence-directory>' --execute --maintenance-confirmed --confirm-target '<new_restore_database>'
```

S3 target ใช้ `--s3-bucket '<new-empty-private-bucket>'` แทน `--evidence-root` และ service endpoint ถ้าต้องใช้ Tool ต้องมี list/get/put ของ target และจะ read-back ตรวจ hash ของ uploaded objects ไม่มี ACL/policy configuration และไม่แก้ public-access settings ให้ ตรวจ private configuration ก่อนเปิดแอป

การ restore ล้มเหลวอาจเหลือ partial DB/objects ใน isolated target ให้รักษา logs และ target ไว้ตรวจ อย่าเปิด application/worker บน target นั้น Tool ไม่ลบ state ที่เหลือและไม่เขียนทับ retry ให้เตรียม target เปล่าใหม่และแก้สาเหตุก่อนรันใหม่ ห้าม retry ด้วย `--clean` กับ database จริง

## Validate service และวัด RPO/RTO

1. เก็บ restore JSON report ที่ระบุเวลาเริ่ม/จบ `data_restore_seconds` และ evidence count ไว้เป็นหลักฐาน ค่า `service_rto_seconds`/`rpo_seconds` เป็น null จนกว่าจะมีข้อมูลวัดจริง การคืนไฟล์/DB เสร็จไม่ใช่ service recovered
2. เริ่ม image/configuration revision ที่เข้ากับ schema/BPMN ใน private restored environment โดย workers/timers/mail/cleanup ปิด ตรวจ Flyway version, Flowable schema version, process definitions, document/process/task mappings, finalized evidence checksums/access scope และเปิด reconciliation แบบ report-only ไม่แก้ ledger เพื่อกลบ mismatch
3. เก็บผล signed OIDC/session/CSRF, document A/B + pending operation recovery, retained custody/exception, batch operation/delivery mapping และ object read ของ sample ที่ได้รับอนุญาต Restore jobs อาจมี pending mail เก่า **ห้ามเปิด SMTP real recipients** ใน drill ใช้ local/test sink; กระบวนการ DR จริงต้องให้ mail owner ตัดสินใจรายการที่อาจ accepted แล้วแต่ ACK ไม่แน่นอน อ้าง exactly-once ไม่ได้
4. บันทึก `incident_at`, latest recoverable committed business event time/sequence, snapshot boundary, `restore_started_at`, เวลา smoke และเวลาที่ service กลับใช้งานได้จริง คำนวณ observed data loss/RPO จากจุดล่าสุดที่กู้ได้เทียบ incident และ service RTO จาก incident เทียบ recovery ที่ตรวจแล้ว ระบุ timezone และ evidence อ้างอิง อย่าเอา backup age หรือ data restore duration มาแทนเป้าหมาย/ผล RPO/RTO โดยอัตโนมัติ
5. ค่าเป้าหมาย, backup schedule, retention, off-site location, responsible owner และ acceptable loss/outage ยังรอ X04/X05/D12/D17 อนุมัติ ไม่มี default ตัวเลขใน repo นี้ ก่อน promote restored target ต้องผ่านเจ้าของ incident/release ตามขั้นตอนขององค์กรและตรวจข้อมูลใหม่หลัง snapshot ตาม [Rollback](Rollback_Runbook.md)

## Orphan uploads และ retention

`POST /api/v1/admin/operations/orphan-uploads/cleanup` เป็น Central Admin + CSRF + idempotency command; ใช้ request fields ของ API contract (`dry_run`, `limit`, `reason`) เริ่ม dry-run เสมอ `ORPHAN_CLEANUP_ENABLED=false` และ `ORPHAN_GRACE_SECONDS=0` เป็น fail-closed defaults ไม่มี grace policy จะ preview/delete ไม่ได้ ตั้ง grace ตามการอนุมัติของเจ้าของข้อมูลเท่านั้น

cleanup ถือ upload row lock เดียวกับ content/finalize เลือกเฉพาะ INITIATED/STORED ที่เก่ากว่า grace และไม่มี evidence link; concurrent upload/finalize ถูก skip/recheck ลบ object exact key แบบ idempotent ก่อนลบ metadata โดย batch ใช้ transaction ร่วมกับ command receipt/audit และ savepoint ต่อ upload หาก storage/DB fail จะเก็บ metadata ของรายการนั้นไว้ retry; ถ้า outer transaction rollback metadata ทั้งชุดจะกลับมาให้ retry exact key เดิมได้ ไม่ลบ FINALIZED แม้ยังไม่ได้ attach และไม่ลบ event/ledger/audit/history/mail attempts/backup เลย No finalized-evidence retention deletion job จนกว่าจะมี retention/legal hold policy และ design ที่อนุมัติ
