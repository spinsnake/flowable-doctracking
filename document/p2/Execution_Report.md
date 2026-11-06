# P2 Execution Report

วันที่ 2026-11-06 · ขอบเขต backend/headless บน PostgreSQL จริง

## สิ่งที่ส่งมอบ

- [BPMN document_tracking_v1](../../backend/src/main/resources/processes/document_tracking_v1.bpmn20.xml) deploy ได้จริง มี branch Model A/B และ route iterator ใช้ exact step IDs แยกต้นทางกับจุดรับคืนแม้เป็น Station เดียวกัน
- [TrackingWorkflowEngine](../../backend/src/main/java/th/ac/mfu/doctracking/workflow/TrackingWorkflowEngine.java) สั่งผ่าน Flowable Java API ภายใน ตรวจ document/business key, immutable mode/route snapshot, waiting task, step occurrence และ leg ก่อน complete; instance เดิมผูกกับ process definition ID/version เดิม
- [WorkflowOutboxWorker](../../backend/src/main/java/th/ac/mfu/doctracking/workflow/WorkflowOutboxWorker.java) ใช้ document row lock + SKIP LOCKED และไม่ให้ event หลังแซง event ที่ยังไม่ APPLIED; ตรวจ envelope เทียบ immutable ledger และ canonical payload ก่อน apply
- Engine start/complete, inbox hash, instance mapping และ ACK/sync projection commit/rollback ภายใน datasource/transaction manager เดียวกัน ไม่มี broker หรือ engine REST endpoint
- Retry ใช้ persisted backoff 2/4/8/16 วินาทีและ DEAD_LETTER เมื่อครบ 5 attempts; correlation mismatch เข้า DEAD_LETTER ทันที ส่วน document แสดง FAILED_RETRYABLE และปฏิเสธคำสั่ง workflow ถัดไป
- [Recovery API](../../backend/src/main/java/th/ac/mfu/doctracking/workflow/WorkflowRecoveryController.java) ให้ Central Admin อ่านเฉพาะ technical status และ retry original operation พร้อม delivery version/CSRF/idempotency/reason/audit โดยไม่สร้าง business event ใหม่
- [Business commands](../../backend/src/main/java/th/ac/mfu/doctracking/domain/WorkflowCommandService.java) รองรับ single-document origin/unit ready, courier pickup/drop-off พร้อมรูป, intermediate unit receipt และ final origin return; มี version/scope/owner/expiry/photo/pending guards และบันทึก business event/outbox แบบ atomic
- [V3 migration](../../backend/src/main/resources/db/migration/V3__workflow_delivery.sql) เพิ่ม delivery metadata/index, inbox hash และ durable reminder feed แยกจาก Flowable-owned schema

Model A จบที่ `COURIER_TRACKING_COMPLETE` หลัง final courier drop-off โดยไม่มี unit receipt/ready สมมติ Model B คง current step เดิมหลัง courier drop-off จนหน่วยงานยืนยันรับ และจบ `COMPLETED` หลัง origin ยืนยัน exact return step เท่านั้น Reservation/cancel/expiry ยังเป็น local events ที่ไม่ complete task

## ผลทดสอบ

| ชุดตรวจ | ผล |
|---|---|
| Maven Wrapper `verify` | ผ่าน; executable JAR สร้างสำเร็จ |
| P1 regression | 29 tests ผ่าน: identity 11, signed OAuth 4, loopback SMTP 1, registry/reservation/evidence 13 |
| TrackingWorkflowEngineIntegrationTest | 5 tests ผ่าน: A/B ครบ A→B→C→A, exact correlation, timer และ version pinning |
| WorkflowCommandsIntegrationTest | 6 tests ผ่าน: business flow A/B จริงพร้อมรูป, custody/pending/role/expiry guards, rollback/replay และ JDBC-session HTTP/CSRF/polling |
| WorkflowOutboxIntegrationTest | 7 tests ผ่าน: restart worker/replay, สอง worker แข่งกัน, rollback หลัง start/complete, mismatch, event ordering/backoff/dead-letter/admin retry |
| รวม backend | **47 tests, 0 failures, 0 errors, 0 skipped** บน PostgreSQL 17.11 |
| OpenAPI | validation ผ่าน 37 paths / 44 operations |
| Compose base/dev | config validation ผ่าน; ยังไม่ได้ build/start containers |

หลักฐานแบบอ่านด้วยโปรแกรม: [Test_Results.json](Test_Results.json) · คำสั่งชุดหลักอยู่ใน [README](../../README.md)

การรันทดสอบรวมครั้งหนึ่งถูกขัดจังหวะเพราะพื้นที่ดิสก์เต็ม จึงล้างเฉพาะ disposable npm cache และ runtime download archives ที่งานนี้สร้าง เก็บ installed runtimes/source ไว้ แล้ว restart ฐานทดสอบและรัน `verify` ใหม่จนผ่านครบ ผลที่รายงานอ้างอิงรอบที่สำเร็จ

## Transaction และ recovery evidence

1. Inject failure หลัง Flowable start: process, app mapping และ inbox rollback ทั้งหมด แต่ business registration ที่ commit ก่อนหน้ายังอยู่ จากนั้น persisted retry start ได้หนึ่ง instance
2. Inject failure หลัง complete task: task ID เดิมยังเปิดอยู่, inbox ไม่มี marker และ outbox ไม่ ACK; retry แล้วขยับเพียงครั้งเดียว และ replay หลัง ACK ไม่ complete task ถัดไป
3. ตรวจ engine datasource และ transaction manager เป็น object เดียวกับแอปจริง ไม่มี REQUIRES_NEW แยก inbox/ACK ออกจาก engine apply
4. สอง threads เรียก worker สำหรับ registration เดียวกันพร้อมกันได้ผลหนึ่ง success/หนึ่งไม่พบงาน พร้อม process/inbox อย่างละหนึ่ง; delayed predecessor ป้องกัน UNIT_READY แซง DOCUMENT_REGISTERED
5. Operation polling แสดง sync ของ operation นั้น รวม `updated_states[].process_sync_status`; command receipt สำหรับ idempotent replay ยังคง original outcome แม้ polling เปลี่ยนเป็น APPLIED แล้ว

Native JVM recovery ใช้ [WorkflowRestartProbe](../../backend/src/test/java/th/ac/mfu/doctracking/WorkflowRestartProbe.java) กับ isolated test schema เท่านั้น: เลือก registration ที่ commit จาก test JVM ที่ออกไปแล้วและยังไม่มี mapping จากนั้นเริ่ม executable JAR ให้ scheduler consume แล้วหยุด/เริ่ม JAR ใน JVM ใหม่ ตรวจว่า process ID/task ID เดิม, inbox หนึ่งรายการ และ ORIGIN_READY ยังเปิดอยู่โดยไม่ start/advance ซ้ำ Probe เลื่อน due time ของ outbox fixtures อื่นออกไป ไม่สร้าง ACK ปลอมให้ registration ที่ตรวจ

การฉีด failure ระหว่าง apply เป็น integration test ส่วน native restart ตรวจหลัง business commit และหลัง ACK; DB restart/host crash/restore/โหลดระดับ staging ยังต้องตรวจใน P6/P8

## Timer และ deployment

Noninterrupting boundary timer 24 ชั่วโมงเป็น **development baseline ที่ยังรอ SLA policy** เมื่อถึงเวลาจะบันทึก `workflow_reminders` ภายใน timer transaction โดยไม่ complete task/auto-accept/เปลี่ยน custody Tests ย้าย persisted timer ไป executable job แล้ว execute ผ่าน ManagementService จริงและยืนยันว่า UNIT_RECEIVE/task ID เดิมยังคงอยู่ ยังไม่มีการส่ง workflow reminder email หรือ operations UI ซึ่งเป็น P6

`WORKFLOW_WORKER_ENABLED=true` เปิด outbox polling และ `WORKFLOW_TIMER_ENABLED=true` เปิด Flowable async executor สำหรับ durable timer jobs; tests ปิด scheduler/async executor แล้วเรียก worker/jobs แบบ deterministic การ deploy ใช้ duplicate filtering และ instance ที่รันแล้วไม่ย้าย definition ตาม latest deployment

## Acceptance / checklist traceability

| รายการ | หลักฐาน |
|---|---|
| P2-01–05 | XML executable, engine service และ headless A/B business-flow tests |
| P2-06 / T25 | Canonical event/step/task/leg guards, immutable snapshot check, ordering และ concurrent-worker tests |
| P2-07 | Shared transaction identity assertions และ failure หลัง engine start/complete |
| P2-08 | Backoff/dead-letter/pending guards, safe technical error codes และ original-operation retry API |
| P2-09 / T20 | Durable worker reconstruction, native JVM restart, replay, failed/mismatched envelope และ delayed predecessor |
| P2-10 / T26 | A/B A→B→C→A, repeated Station occurrences, noninterrupting timer และ definition version test |

Final independent review ไม่พบ blocker ใน authorization/custody/ordering/transaction พบ operation nested-sync projection ที่ค้าง PENDING ซึ่งแก้พร้อม HTTP assertion แล้ว และเพิ่ม concurrent-worker test ตาม review

## ขอบเขตที่ยังไม่ปิด

- G0 และ policy/infra approvals เดิมยังเปิด; timer baseline/TTL/field-photo scope ไม่ถือว่าได้รับอนุมัติจากการ implement
- P3/P4 ยังต้องต่อ UI ของ READY, Check รับ/ส่ง, unit receipt และ origin return พร้อม worklists/mobile end-to-end; P2 ผ่าน backend/headless ไม่ใช่ UAT
- Bulk/direct transfer/observation/default-mode switch/exception recovery ตาม policy ยังเป็น P4/P5; P2 ไม่เปิด bulk เพียงเพราะ payload ใช้รูปแบบ items array
- Google/MFU account, SMTP MFU inbox, private S3, HTTPS และ container runtime จริงยังรอ staging verification ตาม P8; CI remote runner ยังไม่มีผลรัน
- Reminder delivery/escalation, operational dashboards, reconciliation, retention, backup/restore และ security assessment เต็มระบบเป็น P6–P8

P1 report เป็นหลักฐานตามเวลาที่ทำ P1; สถานะ workflow ปัจจุบันให้อ้างรายงาน P2 นี้และ checklist ล่าสุด
