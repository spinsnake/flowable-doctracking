# P6 — Operations, deployment และ recovery

วันที่: 2026-11-07 (Asia/Bangkok)

สถานะ: **พัฒนาและตรวจ isolated development แล้ว; ยังไม่ผ่าน G6** ต้องแยกผลในเครื่องออกจากการ deploy/rollback/full restore/HTTPS และบริการ staging จริง งาน P5 direct transfer/exception resolution ยังเปิดตาม D06/X07–08/D10 ไม่ได้ถูกอนุมัติหรือ implement แทนด้วย P6

## สิ่งที่ส่งมอบ

- หน้า Central Admin `/admin/operations` รวม workflow/mail failures, business exceptions ที่เปิดอยู่, audit references, reminders, tasks และ exact engine bindings แสดงข้อมูลระบบตามสิทธิ์โดยไม่เผยชื่อเรื่อง/เลขหนังสือ/อีเมลผู้รับ/รูป/ข้อความ provider หรือ audit reason ใน feed ใหม่นี้ การเป็น Admin สำหรับข้อมูลเทคนิคไม่เปิด full document detail/evidence โดยปริยาย
- Retry ใช้ delivery ID/version และ operation เดิม พร้อมเหตุผล/actor audit และ canonical event/task/step validation ใน production worker ไม่ register เรื่องใหม่หรือสร้าง Check/receipt แทนบุคคล Mail retry ใช้ job/Message-ID เดิมแต่ยังอาจเกิด SMTP delivery ซ้ำเมื่อ ACK ขาดหาย
- Migration V5 เพิ่ม `workflow_reconciliation_findings`; scheduled bounded scan และ manual CSRF/idempotent/audited scan เทียบ ledger/projection/custody/pinned definition/task/bindings บันทึก diagnostic finding แยกจาก business exception ไม่แก้สถานะ/ประวัติ/หลักฐานอัตโนมัติ Pending delivery พัก engine comparison แต่ยังตรวจ canonical envelope/projection; เก็บ engine finding เดิมจนตรวจได้ และ malformed payload ไม่ขวางเรื่องถัดไป
- Readiness ตรวจ DB + workflow definition, liveness ตรวจ application availability โดยไม่เผย component details Metrics เป็น Central Admin เท่านั้น มี queue lag/dead letters/mail retries/waiting tasks และ alert feed สำหรับ failure/finding ที่มีอยู่จริง ไม่กำหนด SLA deadline หรืออ้างว่าส่ง external alert ถึงผู้รับแล้ว
- Validated UUID `X-Correlation-ID` เชื่อม response/error กับ structured logs; request logs ใช้ route template ไม่บันทึก URL/query/body Nginx API access log ปิด ส่วน access logs อื่นไม่เก็บ URL/query; `/healthz` proxy readiness และ production ปฏิเสธ automatic engine schema update ก่อนสร้าง engine bean
- Orphan cleanup รองรับ dry-run และ explicit approved grace; ค่าเริ่มต้นปิด การลบเลือกเฉพาะ INITIATED/STORED เก่าและไม่ link ใช้ upload lock เดียวกับ upload/finalize ไม่ลบ FINALIZED หรือ linked evidence Batch DB metadata/receipt/audit commit ร่วมกันและ savepoint ต่อ upload; object deletion ภายนอกเป็น exact-key/idempotent หาก DB rollback metadata จะกลับมาเพื่อ retry รูป staged นั้นได้
- [Deployment runbook](Deployment_Runbook.md), [Backup/restore runbook](Backup_Restore_Runbook.md), [Rollback runbook](Rollback_Runbook.md) และ `scripts/operations.py` สำหรับ image digest override, anonymous smoke, single full Postgres dump + private evidence/checksum manifest, isolated restore/dry-run ไม่ overwrite DB เดิม ผล restore script แยก data restore duration จาก service RTO และไม่เติม RPO/RTO ขึ้นเอง

## หลักฐานตรวจ

ดู [Test_Results.json](Test_Results.json) สำหรับผลรวมและขอบเขตแต่ละชุด รวม backend/browser **104 tests ผ่าน** จาก default verify และ opt-in แยกกัน ไม่นับ browser checks เป็น tests เพิ่ม

| ชุดตรวจ | ผล |
|---|---|
| Backend default `verify` | 104 discovered: ผ่าน 103, 0 failure/error, opt-in browser ถูกข้าม 1; executable JAR สร้างได้ |
| OperationsIntegrationTest | 5 tests ผ่าน: admin/CSRF/probes/metrics scope, bounded redacted lists, exact engine bindings/read-only gauges, correlation/log redaction, cleanup policy guard |
| WorkflowReconciliationIntegrationTest | 7 tests ผ่าน: A/B full lifecycle, pending/held exception/terminal, canonical envelope and projection tampering, finding dedup/resolve/no auto-correction, malformed payload starvation, admin/CSRF/idempotency/revocation และ deploy BPMN ใหม่ขณะ A/B เดิมยังรัน |
| OrphanUploadCleanupIntegrationTest | 5 tests ผ่าน: fail-closed/defaultdeny, age/finalized/linked preservation, exact-key retry after uncertain storage ACK, finalize lock และ outer transaction rollback/retry |
| ProductionSchemaGuardTest | 2 tests ผ่าน: prod automatic/destructive update ถูกปฏิเสธก่อนสร้าง engine; controlled prod และ isolated test profile ใช้ได้ |
| Mail failure tests | เพิ่ม 3 tests: expired lease/stale ACK, retry จน terminal cap พร้อม safe errors และ loopback SMTP รับ body แล้วขาด 250 ACK ทำให้ retry job เดิม/Message-ID เดิมได้ โดยไม่อ้าง exactly-once |
| Real-browser P6 opt-in | 1 test / 28 checks ผ่าน รวม Admin retry/rescan/inspect, anonymous readiness/protected CSRF smoke และ B/bulk regression; desktop/mobile ไม่มี viewport overflow |
| Native DB restart | ผ่าน: executable JAR/JVM เดิมยังรันระหว่างหยุด/เริ่ม PostgreSQL 17.11; direct probe ตรวจ process ID/task ID เดิม, inbox 1, sync APPLIED และ ORIGIN_READY คงอยู่ Readiness UP ก่อน/หลัง; ระหว่าง DB down request ไม่สำเร็จภายใน timeout ที่ตรวจ ไม่ได้วัด service RTO |
| Frontend | typecheck, Node tests 2 และ static build ผ่าน; UI smoke fixtures 20 checks desktop/mobile ผ่าน |
| Operations scripts | 9 stdlib fixture/loopback HTTP tests ผ่าน ทั้ง checksum/corruption, digest pin, refusal nonempty/source restore และ dry-run; ไม่ใช่ full pg_dump/pg_restore/S3 จริง |
| Config/API | Compose base/dev config ผ่าน; OpenAPI `0.7.0-p6` valid 51 paths / 58 operations |

Browser fixture P6 สร้าง registration จริง ฉีด outbox corruption ให้ production worker dead-letter แล้วคืน envelope จาก immutable ledger ก่อนให้ Admin retry ผ่าน UI; ไม่ fake APPLIED, process mapping หรือ business evidence อีก fixture เปลี่ยน task-local binding ใน test เท่านั้นเพื่อพิสูจน์ว่า scan พบความต่างและไม่แก้ document version/task เอง Test provider และ fault injection อยู่ใน test sources ไม่มี bypass ใน production JAR

Native probe เลือกเฉพาะ registration ที่ commit แล้วมี canonical object envelope; fixture ที่จงใจ corrupt จาก integration suite ไม่ถูกเลือก เลื่อน due time เฉพาะ outbox fixtures อื่นใน disposable schemaเพื่อไม่ให้ scheduler ไปทำงาน unrelated ไม่มีการสร้าง ACK ปลอม การทดสอบนี้ไม่ใช่ crash recovery/full backup restore และ host clock มีการกระโดดระหว่าง session จึงไม่ใช้ elapsed wall-clock เป็น RPO/RTO

## Checklist และขอบเขตที่ยังค้าง

| รายการ | สถานะ/ข้อจำกัด |
|---|---|
| P6-01–04 | ผ่าน development: dashboard/API, read-only reconciliation, original retry, observability/failure feed; external alert routing/approved SLA ยังเป็น infra policy |
| P6-05 | Cleanup code/5 tests พร้อมและปิดโดย default; ยังไม่มี approved orphan grace/retention/legal hold policy สำหรับเปิดใช้งานจริง |
| P6-06–07 | Runbooks/tools/config พร้อม และ new BPMN coexistence ผ่านจริงใน isolated engine; image digests จริง/container staging release ยังไม่ตรวจ |
| P6-08–09 | Runbooks/scripts/fixture tests พร้อม; ยังไม่ได้ staging rollback หรือ full app+Flowable DB/evidence restore จริง และไม่มี approved RPO/RTO targets |
| P6-10 | ผ่าน isolated failure/restart/lease/uncertain SMTP tests และ native DB restart; ไม่ใช่การรับรอง host crash หรือ exactly-once mail |
| P6-11–12 | API security/regression ส่วนที่มีผ่าน; HTTPS/callback/proxy access-log runtime, private S3/provider staging และ capacity ตามปริมาณจริงยังเปิด |
| G6 | ยังไม่ผ่าน: ต้องมี deployment/rollback/full restore/recovery evidence บน environment ที่กำหนดและ policy ที่อนุมัติครบ |

Docker daemon ไม่พร้อม (`docker_engine` pipe ไม่มี) จึงตรวจได้เฉพาะ Compose config ไม่มีการอ้าง container build/start ผ่าน Native PostgreSQL package มี postgres/pg_ctl/initdb แต่ไม่มี pg_dump/pg_restore; ไม่มี S3/staging credentials หรือ workload/RPO/RTO/retention ที่อนุมัติ จึงไม่สร้างตัวเลขหรือผลทดสอบแทนบริการจริง รายการที่ไม่ต้องขึ้นกับสิ่งเหล่านี้ทำเสร็จแล้ว

## รันซ้ำ

กำหนด Java 21 และ disposable PostgreSQL `TEST_DB_URL`/`TEST_DB_USER`/`TEST_DB_PASSWORD` ใน environment ส่วนตัว จาก backend ใช้ `./mvnw.cmd verify`; frontend ใช้ `npm.cmd run typecheck`, `npm.cmd test`, `npm.cmd run build`; tools ใช้ `python -B -m unittest discover -s scripts -p test_operations.py -v`

Browser หลัง build frontend: ตั้ง `P3_BROWSER_ENABLED=true`, `P5_BROWSER_ENABLED=true`, `P6_BROWSER_ENABLED=true` และ `PYTHON_BIN` ถ้า Python ไม่อยู่ใน PATH แล้วรัน `./mvnw.cmd '-Dtest=P3BrowserIntegrationTest' test`; ใช้ signed test-local OIDC, Chrome/Playwright และ loopback gateway โดยไม่ติดต่อ Google/SMTP จริง ล้าง opt-in env ก่อนกลับไปรัน default verify


## ภาพและคู่มือใช้งาน

- [Operations desktop](images/operations-desktop.png) / [mobile 390px](images/operations-mobile.png): finding จาก task-local mismatch จริงใน test fixture และ technical bindings; ตารางเลื่อนภายในได้
- [Operations runbook](Operations_Runbook.md): ขั้นตอน inspect/retry/rescan, mail ACK uncertainty, health/metrics/correlation และ policy guard ของ cleanup

Smoke script ตรวจ readiness 200 และ anonymous CSRF/session 401 ตาม authorization contract จริง ไม่ login หรือส่งอีเมล การรอบสุดท้ายแก้ตัวทดสอบให้เรียก subprocess แบบ asynchronous เพื่อไม่บล็อก gateway ของตัวเอง แล้วผ่านครบ ไม่มีการเปิด CSRF token ให้ anonymous เพื่อให้ test ผ่าน
