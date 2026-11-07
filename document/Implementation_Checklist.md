# Implementation Checklist — Flowable Document Tracking

วันที่: 2026-11-07 (Asia/Bangkok)

สถานะ: P1–P4/G1–G4 ผ่านเฉพาะ development/isolated integration ตาม baseline; P5 bulk **P5-01–04 เสร็จ 4/11** ตาม D08 partial success ที่ผู้ใช้ยืนยัน ดู [P5 Execution Report](p5/Execution_Report.md) — backend 81 tests และ browser B/bulk 1 test ผ่าน มีกรณีเลือก 10 สำเร็จ 8 อีก 2 conflict จริง; ส่วน direct/exception ยังรอยืนยัน D06/X07–08 และ G5 ยังไม่ผ่าน; G0 ยังรอ business/infra และ staging จริงยังเป็น P8

## วิธีใช้และเอกสารอ้างอิง

- เปลี่ยน `- [ ]` เป็น `- [x]` เมื่อมีผลส่งมอบและหลักฐานตรวจสอบแล้ว ระบุ commit/PR, test result หรือเอกสารที่เกี่ยวข้องไว้ท้ายรายการ
- ทุก phase มี Exit Gate; งานที่ไม่ขึ้นกับ gate ที่ยังค้างทำต่อได้ แต่ห้ามถือว่า phase ผ่านก่อนหลักฐานครบ
- หากตัดงานออกจาก scope ให้ระบุเหตุผล ผู้ตัดสิน และผลกระทบ ไม่ทำเครื่องหมายเสร็จแทนการยกเลิก
- หมายเลข `Txx` อ้างถึง acceptance tests ในเอกสารหลัก; `Dxx` อ้างถึง business decisions ที่ต้องยืนยัน ไม่ใช่รายการที่ถือว่าอนุมัติแล้ว
- การแก้เอกสารหรือมี Mermaid diagram ยังไม่ถือว่า BPMN/API/ฟีเจอร์นั้นทำงานแล้ว

เอกสารอ้างอิง:

- [ข้อกำหนดระบบและ T01–T42](Document_Tracking_Flowable_Development_Requirements.md)
- [แผนพัฒนาและ Tech Stack](Development_Plan_and_Tech_Stack.md)
- [Google Login, Login Email และ Central User](Authentication_and_Central_User_Requirements.md)

ใช้ P0–P8 โดยนำรายละเอียดที่ปรับแล้วใน Development Plan มาใช้: ทำ reservation/idempotency/audit ตั้งแต่ P1, workflow sync ตั้งแต่ P2 และ default-mode switch ใน P4 ส่วน P6 ตรวจ recovery/regression ซ้ำ เพิ่ม **P7 — Security Testing** ก่อน Pilot และย้าย Staging Verification/UAT/Pilot เดิมจาก P7 เป็น **P8**; phase numbers และ task IDs ของ Pilot ในไฟล์นี้จึงเปลี่ยนตาม การตรวจ security ระหว่างพัฒนายังคงทำใน phase เดิม และ P7 เป็นการตรวจรวมก่อน Pilot

## ภาพรวม phase

| Phase | ผลลัพธ์หลัก | ต้องมีฐานจาก | งานเสร็จ / สถานะ Gate |
|---|---|---|---|
| P0 | กฎธุรกิจ, API/สิทธิ์, compatibility และ infra decisions | เอกสารข้อกำหนด | 8/14 · G0 รอยืนยัน business/infra |
| P1 | Foundation, Google login, Central User/Unit, login email, registry/evidence/reservation | P0 สำหรับส่วนที่เกี่ยวข้อง | 25/25 · G1 ผ่านเฉพาะ development |
| P2 | BPMN A/B ที่ execute ได้ และ workflow outbox/inbox ที่กู้คืนได้ | P1 data/transaction/authorization | 10/10 · G2 ผ่านเฉพาะ backend/headless development |
| P3 | Model B ครบ A → B → C → A ผ่าน UI จริง | P1–P2 | 10/10 · G3 ผ่านเฉพาะ isolated development/browser |
| P4 | Model A และ switch default A/B โดยไม่กระทบเรื่องเดิม | P2–P3 | 9/9 · G4 ผ่านเฉพาะ development ตาม conservative observation baseline |
| P5 | Bulk, direct transfer และ exception resolution | P3–P4 และ policy ที่ยืนยันแล้ว | 4/11 · Bulk ผ่าน development; G5 รอ direct/exception |
| P6 | Operations, reconciliation, deploy/rollback, backup/restore | Workflow และ integration ที่เกี่ยวข้อง | 0/12 · ยังไม่เริ่ม |
| P7 | Security testing, แก้ช่องโหว่และทดสอบซ้ำ | P0–P6 และ staging ที่ทดสอบได้ | 0/18 · ยังไม่เริ่ม |
| P8 | Staging verification, UAT และ Pilot | P0–P7 ผ่านตาม scope | 0/10 · ยังไม่เริ่ม |

จำนวนงานไม่นับ Exit Gate และไม่รวมผล compatibility spike ไว้เป็นงาน P2 ที่เสร็จแล้ว งานพัฒนาถัดไปคือ **P5 — Bulk, Direct Transfer และ Exception** โดยต้องยืนยัน policy ที่เกี่ยวข้องก่อนเปิดใช้; Model A/B ทำงานผ่าน UI ได้และ default switch ไม่เปลี่ยน mode ของเรื่องเดิม การ reconcile/resume หลังพบข้ามจุดยังรอ approved resolution ใน P5/X07

ไม่ผูก checklist นี้กับวันส่งมอบตายตัว ประเมินกำลังคนและระยะเวลาใหม่หลัง P0 โดยรวม Google/Central User/login email ที่เพิ่มเข้ามาแล้ว

## P0 — ตรึงกฎและพิสูจน์ stack

### งานที่ต้องทำ

- [x] **P0-01** ทำ decision log ของ D01–D17 ระบุคำตอบ/ผู้ตัดสิน/สถานะ โดยเริ่มจาก mode, ความหมายของการจบ, bulk policy, evidence และสิทธิ์ — [Decision Log](p0/Decision_Log.md); การทำ log เสร็จไม่เท่ากับ open decisions ได้รับอนุมัติแล้ว
- [x] **P0-02** ทำ transition matrix ของ Model A/B: register → ready/physical pickup → reserve → pickup → drop-off → receipt/processing → return พร้อม actor/guard/event/task ที่คาดไว้ — [Design Baseline §3](p0/Design_Baseline.md)
- [x] **P0-03** ตรึงความหมาย `reported_location_step_id`, `current_route_step_id`, from/to step และจังหวะ advance; แยก A ต้นทางกับ A ส่งคืนด้วย `step_id` — [Design Baseline §2](p0/Design_Baseline.md) และ [OpenAPI](../contracts/openapi.yaml); เป็น technical baseline ไม่ใช่การอนุมัติ policy ที่ค้าง
- [x] **P0-04** ทำ permission matrix แยก ADMIN, UNIT_ADMIN, STAFF, COURIER, Coordinator และ Origin/Station context รวมสิทธิ์รายละเอียดหนังสือ/timeline/รูป — [Design Baseline §4](p0/Design_Baseline.md); D11/D13 ยังรอ field/image scope sign-off ก่อน API ใช้ข้อมูลจริง
- [ ] **P0-05** ยืนยัน Unit ↔ Station mapping, จำนวน active memberships และขั้นตอน provision/ปิด Unit/ปิดผู้ใช้ที่ไม่ทิ้งงานค้าง
- [ ] **P0-06** ยืนยัน TTL reservation, bulk all-or-nothing/partial-success, direct-transfer photo และ exception recovery โดยไม่ใช้ข้อเสนอเริ่มต้นแทนการอนุมัติ — D08 ยืนยัน partial success แล้ว 2026-11-07; TTL/D06/X07–08 ยังรอยืนยันตาม Decision Log
- [ ] **P0-07** ยืนยัน business duplicate rule ของเลขหนังสือ, UUID ภายใน, retention, SLA และ backup RPO/RTO
- [ ] **P0-08** เตรียม Google Web OAuth client, MFU hosted-domain policy, HTTPS callback URI และอีเมล bootstrap Admin ที่อนุมัติ โดยเก็บ secret นอก repo — มี [config template](../deploy/.env.example); ยังรอ client/URL/บัญชีจริงตาม [Execution Report](p0/Execution_Report.md)
- [ ] **P0-09** เตรียม SMTP relay/sender/TLS, ผู้ติดตาม failed-email jobs และบัญชี MFU สำหรับทดสอบ staging — มี template/handoff และ loopback SMTP test ผ่านแล้ว; ยังไม่ยืนยัน relay จริงหรือทดสอบรับอีเมลถึง MFU inbox
- [x] **P0-10** ทำ compatibility spike ของ Java/Spring Boot/Flowable/PostgreSQL: start engine, deploy BPMN, complete task, timer และ transaction rollback แล้วตรึงเวอร์ชันที่ทดสอบจริง — [spike/result](../spikes/flowable-compatibility/README.md): Java 21.0.13, Boot 4.0.9, Flowable 8.0.0, PostgreSQL 17.11; 3 tests ผ่าน 2026-11-06, timer execute แบบ deterministic
- [x] **P0-11** กำหนด transaction boundaries สำหรับ business event/outbox, workflow worker/inbox และ login session/audit/email outbox ให้ชัดก่อน implementation — [Design Baseline §5](p0/Design_Baseline.md); shared JDBC/engine rollback ผ่าน spike และ login atomicity/rollback ผ่าน P1-15/T39 แล้วตาม [P1 Report](p1/Execution_Report.md); business workflow synchronization/shared transaction ผ่าน P2 แล้วตาม [P2 Report](p2/Execution_Report.md)
- [x] **P0-12** เขียน OpenAPI ของ auth/provisioning/register/reserve/check/receipt/ready พร้อม error, idempotency, version และ `process_sync_status` — [OpenAPI + scope](../contracts/README.md): P0 baseline 28 paths / 33 operations; P1 ผ่าน 35 paths / 42 operations; P2 ผ่าน 37 paths / 44 operations; P3 ผ่าน 39 paths / 46 operations; ล่าสุด P4 `0.5.0-p4` validation ผ่าน **40 paths / 47 operations** ตาม [ผลทดสอบ](p4/Test_Results.json) โดย observation/default-policy ใช้งานแล้ว แต่ direct/bulk/approved exception resolution ของ P5 ยังไม่เปิด
- [x] **P0-13** ทบทวน UX gaps: ยืนยันรับคืน, conflict/จองหมดอายุ, upload error, sync pending, exception และหน้า Central/Unit Admin — [Design Baseline §6](p0/Design_Baseline.md)
- [ ] **P0-14** ยืนยัน MVP exclusions: online-only, ไม่เปลี่ยน active-instance mode อัตโนมัติ, ไม่มี QR/OCR/native app และประเมินแผนเวลาด้วย scope ล่าสุด

### Exit Gate P0

- [ ] **G0** มีผล compatibility spike, decision/permission/transition matrix และ API contract ที่ใช้เริ่มงานได้; decision ที่ยังค้างระบุชัดว่าบล็อกงานใด และมีแผนจัดการก่อนเริ่มงานส่วนนั้น — technical evidence พร้อม แต่ P0-05–09/P0-14 ยังรอยืนยัน/infra ตาม [Execution Report](p0/Execution_Report.md); เริ่มต่อได้เฉพาะงานที่ไม่ขึ้นกับคำตอบ

## P1 — Foundation, Identity และข้อมูลธุรกิจ

หลักฐาน P1: [Execution Report](p1/Execution_Report.md), [ผล tests/checks](p1/Test_Results.json), [UI verification](p1/UI_Test_Results.json), [วิธีรัน](../README.md) — 29 backend tests บน PostgreSQL จริง, frontend typecheck + 2 tests + build ผ่าน; แยกขอบเขต test provider/SMTP sink จาก staging จริงตามรายงาน

### โครงสร้างและฐานข้อมูล

- [x] **P1-01** สร้าง backend Spring Boot และ frontend Nuxt/TypeScript ตามเวอร์ชันที่ผ่าน P0 พร้อม Maven Wrapper และ package lockfile
- [x] **P1-02** จัด Compose/Nginx สำหรับ local/staging, same-origin API, environment examples, health endpoint และ secret configuration — [Compose](../compose.yaml) / [dev override](../compose.dev.yaml) ตรวจ config ผ่าน; native JAR health smoke ผ่าน; ยังไม่ได้ build/start containers เพราะ Docker Engine ไม่พร้อม
- [x] **P1-03** ตั้ง CI ให้ backend compile/test และ frontend typecheck/build พร้อม PostgreSQL integration tests — [CI workflow](../.github/workflows/ci.yml) พร้อมแล้ว; local equivalent ผ่านตาม [ผลทดสอบ](p1/Test_Results.json); ยังไม่ได้รันบน remote CI runner
- [x] **P1-04** ทำ application migrations: units/users/memberships/station permissions, documents/template versions/route snapshots, reservations/legs, evidence/events, workflow outbox/inbox และ notification data
- [x] **P1-05** เพิ่ม constraints/indexes ที่ป้องกัน normalized email/sub ซ้ำ, active leg/reservation ซ้ำ และอ้างอิง step ไม่ถูกเรื่อง; แยก application migrations จาก Flowable-owned schema

### Google Login และ Central User

- [x] **P1-06** ทำ Google authorization-code login ผ่าน backend พร้อม state/nonce/PKCE, timeout/single-use callback และ request rate limit
- [x] **P1-07** ตรวจ ID-token signature/issuer/audience/expiry, `email_verified`, exact `@mfu.ac.th`, hosted domain และ allowlist; ไม่สร้าง user/role อัตโนมัติจาก callback
- [x] **P1-08** ทำ first-link Google sub ด้วย lock/unique constraint; ปฏิเสธ identity mismatch และไม่แก้อีเมลที่ผูกแล้วผ่านฟอร์มทั่วไป
- [x] **P1-09** ทำ Spring Session JDBC, regenerate session, cookie flags, CSRF, `/auth/me` และ logout; ตรวจ active user/unit/role/session version ทุก authenticated request
- [x] **P1-10** ทำ bootstrap Central Admin แบบ idempotent จาก configured MFU email โดยไม่ต้องมี Unit และไม่มี app password/shared account
- [x] **P1-11** ทำ API/UI ให้ Central Admin สร้าง Unit ที่รอ Admin และแต่งตั้ง UNIT_ADMIN พร้อมเปิด Unit แบบ atomic
- [x] **P1-12** ทำ API/UI เพิ่ม/แก้/ปิด STAFF; UNIT_ADMIN ทำได้เฉพาะ Unit ตน; ห้ามสร้าง Central Admin/เพิ่มสิทธิ์ตนเอง/ข้าม Unit ผ่าน API ตรง
- [x] **P1-13** ทำ guards รักษา Unit Admin คนสุดท้าย และตรวจงานค้างก่อนย้าย/ปิดบัญชีหรือ Unit พร้อม revoke sessions และ audit — P3 ทดสอบซ้ำกับ document/reservation/leg จริงและ role-change session revocation ผ่านแล้วตาม [รายงาน](p3/Execution_Report.md); bulk/direct-transfer/exception guards ยังต้องตรวจใน P5
- [x] **P1-14** ทำหน้า Google-only login/profile/user status: รอเข้า Google ครั้งแรก/ผูกแล้ว/ปิดใช้ และ error เมื่อบัญชีไม่มีสิทธิ์

### อีเมลแจ้งเตือน login

- [x] **P1-15** ทำ login finalization ให้ session + login audit + notification outbox commit/rollback ร่วมกันจริง และไม่รายงานสำเร็จก่อน persistence เสร็จ
- [x] **P1-16** ทำ template แจ้งเจ้าของบัญชีทุก login session ใหม่ มีเวลา Asia/Bangkok/ช่องทาง IT และไม่มี OAuth token/session ID/ข้อมูลหนังสือ
- [x] **P1-17** ทำ SMTP sender พร้อม timeout/TLS และ worker ที่มี lease/retry/backoff/attempt history; SMTP failure ไม่ยกเลิก login ที่ finalize แล้วหรือบล็อก workflow worker
- [x] **P1-18** กัน enqueue ซ้ำด้วย login event/type/recipient และใช้ Message-ID คงที่; refresh/me API ไม่ส่งเมลใหม่ และไม่รับรอง exactly-once SMTP
- [x] **P1-19** ทำสถานะ pending/processing/retry/provider-accepted/failed และ API สำหรับ Central Admin ตรวจ/retry job เดิมพร้อม audit; หน้า operations รวมตรวจอีกครั้งใน P6

### Registry, Evidence และ single reservation

- [x] **P1-20** ทำ Route Template/version และสร้างหนังสือพร้อม immutable route snapshot, tracking mode และ policy version; บังคับกรอกอย่างน้อยเลขหนังสือหรือชื่อเรื่อง
- [x] **P1-21** ทำ unit/station-scoped list/detail/timeline APIs; เมนูหนังสือของหน่วยงาน filter ตาม origin และ worklist ของ Station แยกกัน
- [x] **P1-22** ทำ private photo upload/validate/finalize/checksum/authorized access และ evidence links; รูปยังไม่ validated หรือไม่มีสิทธิ์ใช้ต้อง Check ไม่ได้ — local storage/authorization/failure tests ผ่าน; S3 adapter มีแล้ว แต่ private bucket/service จริงยังรอตรวจใน staging ตาม P7-11
- [x] **P1-23** ทำ single-document reserve/cancel/expiry ด้วย transaction/row lock; ยังไม่เปลี่ยน custody จน Check รับ และไม่ expire `IN_TRANSIT`
- [x] **P1-24** ทำ idempotency key + request hash/result, optimistic version, append-only ledger/corrections และ event/outbox transaction; เอกสารเดียวกันใช้ guard เดียวกันทุกช่องทาง
- [x] **P1-25** ผ่าน T27–T41 ด้วย isolated test provider/mail sink และทดสอบ reservation race/idempotency/upload failure กับ PostgreSQL จริง

### Exit Gate P1

- [x] **G1** Google/role/Unit/user/email flow มีหลักฐานทดสอบ, สร้างหนังสือพร้อม snapshot/รูปได้, ป้องกันจองซ้ำได้ และพิสูจน์ login transaction rollback; ไม่ใช้ mock auth ใน production — ผ่าน isolated provider/loopback SMTP/PostgreSQL + UI fixtures; ไม่ใช่ staging sign-off และไม่ปิด G0

### งานค้างที่ส่งต่อหลัง P1

| งานค้าง | รายการที่ติดตามต่อ |
|---|---|
| Business policies และบัญชี/บริการจริงที่ได้รับอนุมัติ | P0-05–09 และ P0-14; G0 ยังไม่ผ่าน |
| BPMN, workflow outbox consumer และการเปลี่ยน `PENDING` เป็น `APPLIED` | เสร็จใน P2-01–10/G2 ตาม [ผลทดสอบ](p2/Test_Results.json); Model B UI ผ่าน P3/G3 และ Model A UI ผ่าน P4/G4 แล้ว |
| Build/start container stack และ staging smoke; run CI บน remote runner | P1-02–03 ตรวจ runtime เพิ่มเติมร่วมกับ P6-06 |
| Private S3 service/bucket permissions จริง | P1-22 ตรวจ integration เพิ่มเติมร่วมกับ P7-11 |
| Google login บน HTTPS และรับ email ถึง MFU inbox จริง | P8-01–02 / T42 |

รายการที่ระบุว่ายังรอตรวจยังไม่ผ่านการตรวจรับ staging; workflow และ Model B UI มีผล development ตาม P2/P3 แล้ว การมี config/code หรือผ่าน isolated tests ไม่ถือว่าได้ผล staging verification

## P2 — Flowable BPMN และ workflow synchronization

หลักฐาน P2: [Execution Report](p2/Execution_Report.md), [ผล 47 backend tests / checks / JVM recovery](p2/Test_Results.json), [BPMN executable](../backend/src/main/resources/processes/document_tracking_v1.bpmn20.xml) — A/B เดินครบ A→B→C→A ผ่าน backend พร้อมรูป; Model B UI ผ่าน [P3](p3/Execution_Report.md) และ Model A UI/default switch ผ่าน [P4](p4/Execution_Report.md) แล้ว

### งานที่ต้องทำ

- [x] **P2-01** สร้าง deployable BPMN `document_tracking_v1.bpmn20.xml` จริง พร้อม branch Model A และ B ตั้งแต่ต้น — [BPMN XML](../backend/src/main/resources/processes/document_tracking_v1.bpmn20.xml)
- [x] **P2-02** ใช้หนึ่ง instance ต่อ document และ route iterator; เก็บ process definition/version/instance mapping โดยมีกลไกกัน start ซ้ำใน App DB — [Engine](../backend/src/main/java/th/ac/mfu/doctracking/workflow/TrackingWorkflowEngine.java) และ [concurrent/replay tests](../backend/src/test/java/th/ac/mfu/doctracking/WorkflowOutboxIntegrationTest.java)
- [x] **P2-03** ทำ task/gateway สำหรับ origin ready, courier pickup/drop-off, unit receipt/processing/ready และ origin return receipt ตาม transition matrix — [Business commands](../backend/src/main/java/th/ac/mfu/doctracking/domain/WorkflowCommandService.java) และ [A/B flow tests](../backend/src/test/java/th/ac/mfu/doctracking/WorkflowCommandsIntegrationTest.java)
- [x] **P2-04** ทำ Model A physical-pickup/unconfirmed-drop-off/final tracking-complete โดยไม่สร้าง UNIT_READY หรือ UNIT_RECEIPT สมมติ — [Model A engine/business tests](p2/Execution_Report.md)
- [x] **P2-05** ทำ backend orchestration ผ่าน Flowable Java API; frontend ไม่ complete Flowable task โดยตรง และ engine variables เก็บเฉพาะข้อมูลจำเป็น ไม่เก็บ binary รูป — [Engine service](../backend/src/main/java/th/ac/mfu/doctracking/workflow/TrackingWorkflowEngine.java)
- [x] **P2-06** ทำ outbox worker เรียง event ต่อ document และตรวจ document + route step + action + task ที่คาดไว้ก่อน apply — [Worker](../backend/src/main/java/th/ac/mfu/doctracking/workflow/WorkflowOutboxWorker.java); delayed predecessor และสอง worker แข่งกันผ่าน tests
- [x] **P2-07** ทำ consumed-event/inbox + engine start/complete + ACK/sync projection ใน shared transaction; พิสูจน์ engine rollback ทำให้ consumed marker rollback ด้วย — [Rollback tests](../backend/src/test/java/th/ac/mfu/doctracking/WorkflowOutboxIntegrationTest.java) ตรวจหลัง start และหลัง complete พร้อม datasource/transaction manager identity
- [x] **P2-08** ทำ retry/backoff/dead-letter และบล็อกคำสั่ง workflow ถัดไปของเรื่องที่ sync pending ตาม policy; API แสดง business state แยก sync status — [Recovery API](../backend/src/main/java/th/ac/mfu/doctracking/workflow/WorkflowRecoveryController.java), [OpenAPI](../contracts/openapi.yaml); retry operation เดิมพร้อม version/reason/audit
- [x] **P2-09** ทดสอบ restart หลัง business commit, rollback ระหว่าง engine apply, replay หลัง engine commit, duplicate/out-of-order/stale-step event และไม่ advance/start ซ้ำ — [Native JVM probe](../backend/src/test/java/th/ac/mfu/doctracking/WorkflowRestartProbe.java) และ [ผล recovery](p2/Test_Results.json)
- [x] **P2-10** ทำ headless A/B tests ครบ A→B→C→A พร้อม repeated-station steps, timer ที่แจ้งเตือนไม่ auto-accept และ process version behavior — [Engine integration tests](../backend/src/test/java/th/ac/mfu/doctracking/TrackingWorkflowEngineIntegrationTest.java); timer บันทึก durable reminder feed ยังไม่ส่ง workflow email

### Exit Gate P2

- [x] **G2** BPMN A/B execute ได้จริง; T20/T25/T26 และ transaction/failure tests ผ่าน ไม่มี ACK ที่สำเร็จแต่ task rollback หรือ event เดิมขยับขั้นตอนซ้ำ — ผ่าน PostgreSQL integration tests, concurrent workers และ native JVM restart ตาม [P2 Report](p2/Execution_Report.md); ไม่ใช่ UI/UAT/staging sign-off

### งานที่ส่งต่อหลัง P2

| สิ่งที่พร้อมแล้ว | งานที่ยังต้องทำ / Phase |
|---|---|
| Single-document READY/pickup/drop-off/receipt/return APIs และ A/B headless flow | Model B ผ่าน UI/end-to-end ใน [P3](p3/Execution_Report.md); Model A UI/default switch ผ่าน [P4](p4/Execution_Report.md) |
| Photo validation, reservation ownership และ custody guards | Mobile viewport/file upload และ Courier 3 คนคนละ leg ผ่าน P3; physical camera/device testing ยังเป็น P8-06 |
| Durable timer reminder feed และ technical recovery API | ส่ง reminder/escalation และทำ operations dashboard ใน P6; ยืนยัน SLA/timer policy ใน P0-07 |
| JVM recovery, event replay และ definition version pinning | ตรวจ DB restart/host failure, deploy/rollback และ backup/restore ใน P6/P8 |
| Compose config และ CI workflow | Build/start containers, remote CI และ Google/SMTP/S3/HTTPS staging จริงยังรอตรวจตามรายการส่งต่อ P1 และ P8 |

## P3 — Model B ครบเส้นทางผ่าน UI

สถานะ: ผ่านใน isolated development ด้วย Chrome, Spring HTTP, PostgreSQL และ Flowable จริง; เฉพาะ OIDC provider เป็น test-local ดู [Execution Report / acceptance matrix](p3/Execution_Report.md) และ [ผลทดสอบ](p3/Test_Results.json) ไม่ถือเป็น UAT หรือผล Google/SMTP/กล้องอุปกรณ์จริง

### งานที่ต้องทำ

- [x] **P3-01** ทำ Origin dashboard/register/template/list/detail โดยหนังสือใหม่ยังไม่เข้าคิว Courier ก่อนต้นทางกด READY — browser T01 และ P1 regression
- [x] **P3-02** ทำ Unit READY และ eligible pickup worklist เฉพาะงาน/Station ที่ผู้ใช้มีสิทธิ์ — unit-worklist API + scope/redaction integration tests
- [x] **P3-03** ทำ single-item Courier reserve → ตรวจรายการ/ถ่ายรูป → Check รับ; บันทึก leg/custody ต่อช่วงหลังรับสำเร็จ — browser 3 legs พร้อมรูป/physical presence
- [x] **P3-04** ทำ Courier งานของฉันและ Check ส่งพร้อมรูป โดยตรวจ owner/ปลายทาง; ปิด leg แล้วเปิด `PENDING_UNIT_RECEIPT` — own-legs API/UI และ wrong-courier 403
- [x] **P3-05** ทำ Station ยืนยันรับ → `UNIT_PROCESSING` → พร้อมจัดส่ง; ห้าม Courier คนถัดไปจอง/รับก่อน READY — browser T03/T04 ทั้ง B และ C
- [x] **P3-06** ทำ Origin ยืนยันรับคืนที่ return step; ยังไม่ `COMPLETED` เพียงเพราะ Courier Check ส่งคืน — final occurrence guard และ origin-only completion
- [x] **P3-07** ทำ timeline แยก courier report/unit receipt/unit ready พร้อม actor/time/evidence และป้าย business/sync status — timeline API/UI + origin-authorized private evidence
- [x] **P3-08** ทำ error/loading/empty/conflict/upload-failed/sync-pending UI และทดสอบ camera/file flow บนมือถือ — 390px browser/file flow, corrupt PNG rejection/recovery และ pending/failed UI fixtures; physical camera/permissions บนมือถือจริงรอ P8
- [x] **P3-09** ทดสอบ Courier หลายคนรับผิดชอบคนละ leg โดยไม่ต้องให้คนก่อนโอนงาน และทดสอบ guards ปิด/ย้ายผู้ใช้กับ leg/task จริง — 3 distinct courier owners; real document/reservation/leg removal guards และ session revocation
- [x] **P3-10** ผ่าน T01–T06/T21/T23–T25 และ Google/session/permission regression ที่เกี่ยวข้องบน end-to-end flow — 53 backend tests + 1 real-browser integration; Google provider เป็น test-local

### Exit Gate P3

- [x] **G3** สาธิต Model B A→B→C→A ผ่าน UI พร้อมรูปและคนรับผิดชอบจริงในแต่ละช่วง; จบได้หลังต้นทางยืนยันรับคืนเท่านั้น — ผ่านเฉพาะ isolated development ด้วย 7 fixture identities, 3 Courier, 6 รูป และ production workflow worker ตาม [P3 report](p3/Execution_Report.md)

### ผลตรวจล่าสุดและงานที่ส่งต่อหลัง P3

หลักฐานอ้างอิง: [Test Results](p3/Test_Results.json) และ [Execution Report / ภาพหน้าจอ](p3/Execution_Report.md) เป็นผลการรันที่บันทึกไว้วันที่ 2026-11-06–07; การอัปเดต checklist ครั้งนี้ไม่ได้รันชุดทดสอบใหม่

| ชุดตรวจ | ผลที่บันทึกไว้ |
|---|---|
| Backend default `verify` | 53 tests ผ่าน; browser opt-in 1 test ถูกข้ามตามเงื่อนไข |
| Browser integration แบบ opt-in | 1 test ผ่านแยกจาก default verify; รวม backend/browser **54 tests ผ่าน** |
| Frontend | typecheck, Node tests 2 tests และ static build พร้อม `index.html` ผ่าน |
| UI smoke | 20 checks ผ่านบน desktop/mobile รวม pending/failed เป็น read-only; ใช้ API fixtures แยกจาก browser integration |
| API contract | OpenAPI `0.4.0-p3` validation ผ่าน 39 paths / 46 operations |

| งานที่ยังต้องทำ | รายการที่ติดตามต่อ |
|---|---|
| Model A UI, observation/reconciliation และ switch default A/B สำหรับเรื่องใหม่ | **P4-01–09/G4 ผ่านตาม baseline**; เรื่องเดิมยังคง mode/route/process version; การพบต่างจุดเปิด exception ส่วน approved resume เป็น P5/X07 |
| Bulk, direct transfer และ exception resolution | P5-01–11/G5; policy ที่เกี่ยวข้องต้องยืนยันก่อนเปิดใช้ |
| Operations dashboard, reminder/escalation, container/CI runtime และ deploy/restore | P6 และรายการส่งต่อ P1/P2 |
| Security review/scan/retest ของ release | P7/G7; ผล scope/CSRF/session guards ใน P3 ยังไม่ใช่ security sign-off |
| Google/SMTP/HTTPS/private S3 จริง, กล้อง iOS/Android และ UAT | P8/G8; mobile viewport/file upload ใน P3 ไม่แทนการทดสอบอุปกรณ์จริง |
| Business/infra decisions ที่ยังไม่อนุมัติ | P0-05–09/P0-14 และ G0 ยังคงเปิด |

## P4 — Model A และ switch default A/B

สถานะ: ผ่าน development ตาม [Execution Report](p4/Execution_Report.md), [Test Results](p4/Test_Results.json) และภาพ desktop/mobile; 69 backend tests + 1 real-browser integration ที่รวม A/B/default switch/observation ไม่มี business API mocks OIDC provider เป็น test-local และยังไม่ใช่ staging sign-off

### ผลตรวจที่บันทึกไว้ล่าสุด

ตรวจเทียบกับ [P4 Test Results](p4/Test_Results.json) วันที่ 2026-11-07; การอัปเดต checklist ครั้งนี้ไม่ได้รันชุดทดสอบใหม่

| ชุดตรวจ | ผลที่บันทึกไว้ |
|---|---|
| Backend default `verify` | 69 tests ผ่าน, 0 failures/errors; browser opt-in 1 test ถูกข้ามตามเงื่อนไข |
| Browser integration แบบ P4 opt-in | 1 test ผ่านแยกจาก default verify ครอบคลุม 25 browser checks รวม Model A/B, default switch, observation และตัวกรอง |
| รวม backend/browser | **70 tests ผ่าน**; ไม่นับ browser checks เป็น tests เพิ่ม และไม่นับ opt-in ที่ถูกข้ามซ้ำ |
| Frontend | typecheck, Node tests 2 tests และ static build พร้อม `index.html` ผ่าน |
| UI smoke ด้วย API fixtures | 20 checks desktop/mobile ผ่าน; แยกจาก real-browser integration |
| API contract | OpenAPI `0.5.0-p4` validation ผ่าน **40 paths / 47 operations** |

### งานที่ต้องทำ

- [x] **P4-01** เปิด Model A worklist ที่ใช้ความหมาย “ตรวจพบเอกสารจริงก่อนรับ” โดยไม่บังคับ Unit READY หรือ receipt — T07 browser + shared photo/physical-presence flow
- [x] **P4-02** ทำ Check รับ/ส่งพร้อมรูป, เปลี่ยน Courier ต่อช่วง และส่งคืนเป็น `COURIER_TRACKING_COMPLETE` แยกจาก B `COMPLETED` — 3 Courier/3 legs/6 รูปและไม่มี fake unit events
- [x] **P4-03** ทำ observed-at/reconciliation เมื่อพบข้าม Station พร้อม `OBSERVED_AT` + `UNOBSERVED_TRANSFER`; ไม่เดาผู้ส่งหรือเวลาช่วงที่ไม่มีหลักฐาน — ตาม conservative baseline X07: same-step เป็น local evidence; ต่างจุดเปิด exception/คง current/reported/task; approved reconcile/resume เป็น P5-08
- [x] **P4-04** ตรวจ route eligibility/สิทธิ์/step ที่ซ้ำก่อน reconcile; กรณีระบุ step ไม่ได้หรือผิดเส้นทางเข้าสู่ exception ที่ผู้มีสิทธิ์จัดการ — exact UUID บังคับเมื่อ Station อยู่ใน route; ambiguous step ถูกปฏิเสธ; off-route/final/backward เปิด case ไม่ advance
- [x] **P4-05** ทำ Admin API/UI เปลี่ยน `default_tracking_mode` พร้อม reason/actor/time/policy version และ audit — GET/PUT `/admin/workflow-policy`, current-admin/CSRF/idempotency/version guards
- [x] **P4-06** หนังสือใหม่ copy mode/policy/snapshot ตามค่า ณ ตอนสร้างแบบสอดคล้องใน transaction; เรื่องเดิมไม่เปลี่ยนตาม default ใหม่ — policy row locks serialize registration/switch; concurrency/snapshot tests ผ่าน
- [x] **P4-07** ปฏิเสธ active-instance mode switch ใน MVP; เปลี่ยน default แล้วไม่แก้ task, route, event หรือรูปย้อนหลัง — ไม่มี active-mode endpoint; reject mass assignment และ immutable DB snapshot guards; process/task IDs เดิมคงอยู่
- [x] **P4-08** ทดสอบ A/B ทำงานพร้อมกัน, default B→A และ A→B, template เปลี่ยนเวอร์ชัน และป้าย/ตัวกรองสถานะที่ไม่สื่อหลักฐานเกินจริง — browser + policy integration/template v2; UI filter เฉพาะรายการที่โหลดและระบุขอบเขต
- [x] **P4-09** ผ่าน T07–T10/T17–T19 และ regression ของ Model B โดยยืนยันไม่มี fake unit events ใน Model A — 69 backend + 1 browser integration; T10 ใช้ exception/hold ตาม X07 ไม่ใช่ auto-reconcile

### Exit Gate P4

- [x] **G4** A/B รันจริงพร้อมกันได้, switch default มีผลเฉพาะเรื่องใหม่ และผ่าน POC gate เดิมพร้อม T20 และ identity gates ก่อนถือว่าสาธิตสองโมเดลได้ — ผ่าน isolated development ตาม baseline; Model A terminal และ Model B origin return ผ่าน browser เดียวกันหลัง B→A→B; regression engine/outbox/identity ผ่าน; ไม่ปิด G0 หรือรับรอง staging

### งานที่ส่งต่อหลัง P4

| งานค้าง | รายการที่ติดตามต่อ |
|---|---|
| Approved reconciliation/resume ของ observation exception | X07 และ P5-08; ยังห้าม Check รับจากจุดที่ข้ามไปก่อนผู้มีสิทธิ์ resolve |
| Bulk atomicity, direct-transfer evidence และ exception policy | D08 ยืนยัน partial success 2026-11-07 และเริ่มพัฒนา bulk ได้; D06/X07–08 ยังรอยืนยันก่อนเปิดส่วนส่งตรง/resolve; ไม่มีการอนุมัติแทนเจ้าของระบบจากผล development |
| Bulk/direct/exception implementation และ lifecycle guards | P5-01–11/G5 |
| Operations/recovery/deploy/restore และ production security | P6–P7; real Google/SMTP/S3/HTTPS/physical mobile/UAT เป็น P8 |

## P5 — Bulk, Direct Transfer และ Exception

สถานะ: **P5-01–04 เสร็จ 4/11 เฉพาะ bulk development** ตาม [P5 Report](p5/Execution_Report.md); ใช้ partial success เรื่องที่ผ่านสำเร็จทันที พร้อม accepted/rejected และเหตุผลรายเรื่อง เช่น เลือก 10 สำเร็จ 8 อีก 2 มีผู้รับหรือจองไปแล้ว ไม่ย้อน 8 เรื่องที่สำเร็จ ส่วนส่งตรง D06 และ exception resolution X07–08 ยังรอยืนยันแยกกัน และ G5 ยังไม่ผ่าน

### Bulk และ concurrency

- [x] **P5-01** ทำ multi-select/reserve/pickup ตาม bulk policy ที่ยืนยันแล้ว พร้อมผลต่อรายการและ explicit reselect เมื่อ conflict; ไม่เงียบตัดเรื่องจากชุด — API/UI เลือก 1–100 เรื่องและแสดง accepted/rejected; browser เลือก 10 ชนจองจริง 2 สำเร็จ 8 โดยไม่ย้อนรายการสำเร็จ; retry คืนผลเดิมตาม [P5 Report](p5/Execution_Report.md)
- [x] **P5-02** รับรวมเฉพาะ Station เดียวและส่งรวมเฉพาะปลายทางเดียว; ตรวจ Courier owner และ guard ของแต่ละ document แยกกัน — ตรวจ shared source/common destination และ owner ราย leg; mixed destination ปฏิเสธแยกรายเรื่อง และ browser รับ–ส่ง–หน่วยงานยืนยันรับ 8 เรื่องจริง
- [x] **P5-03** ทำ batch/evidence-event links ใช้ภาพร่วมได้ตาม policy แต่ยังมี event ต่อเรื่อง พร้อมตรวจรายชื่อ/จำนวนและแนบภาพเพิ่ม — batch_id/operation_documents, รูป Station ร่วมเชื่อม event แยกต่อเรื่อง; UI ทวนรายชื่อ/จำนวน เพิ่มรูปและบล็อกจนรูปครบ; DB/browser ตรวจ link จริง
- [x] **P5-04** ล็อก document IDs ตามลำดับคงที่และทดสอบ simultaneous reserve/check, TTL expiry แข่ง pickup, bulk conflict และ request retry — deterministic UUID string locks, overlapping reversed batches, simultaneous pickup/replay, expiry แข่ง pickup และ SQL constraint rollback รายเรื่อง ผ่าน 8 bulk tests; aggregate ACK/selected retry/privacy ผ่าน real-engine tests

### Direct transfer และข้อขัดข้อง

- [ ] **P5-05** ทำ Model B direct dispatch หลัง unit receipt/processing พร้อมหลักฐานตาม policy; ห้ามชน active courier reservation/leg ด้วย lock ของ document เดียวกัน
- [ ] **P5-06** Direct dispatch เปิด task ให้ปลายทางยืนยันรับก่อน advance; direct return จบได้เมื่อผู้รับเป็นต้นทางที่ return step เท่านั้น
- [ ] **P5-07** ทำ exception types: not found, partial receipt, wrong station และ receipt dispute พร้อม case owner/reason/evidence/audit
- [ ] **P5-08** ทำ coordinator resolution/resume เข้าสู่ task ที่กำหนดตามหลักฐานจริง; ห้าม auto-accept, ลบประวัติ หรือสร้าง Check แทนบุคคลที่ไม่ได้ทำรายการ
- [ ] **P5-09** สำหรับ route change/mid-leg handoff ให้รองรับเฉพาะ flow ที่อนุมัติและทดสอบแล้ว; กรณีที่อยู่นอก MVP ต้องแสดงการปฏิเสธ/ส่งต่อผู้ดูแลชัดเจน
- [ ] **P5-10** ตรวจ user/unit deactivation และ role transfer ซ้ำกับ bulk reservations, courier custody, direct transfer และ exception ค้าง
- [ ] **P5-11** ผ่าน T11–T16/T22 พร้อม direct-return/exception-resume tests และ regression T35 ที่ใช้ workflow จริง

### Exit Gate P5

- [ ] **G5** Bulk/direct transfer ไม่ทำให้เกิดผู้รับผิดชอบสองคนหรือสองช่องทางพร้อมกัน; ผู้ใช้เห็น conflict ชัด และ exception กลับสู่งานปกติได้โดยรักษาประวัติ

## P6 — Operations, Deploy และ Recovery

### งานที่ต้องทำ

- [ ] **P6-01** ทำ Admin dashboard สำหรับ failed workflow sync, login notification failures, exception และ audit ตาม scope
- [ ] **P6-02** ทำ reconciliation job เปรียบเทียบ App ledger/projection กับ Flowable tasks และเปิดรายการให้ตรวจเมื่อไม่ตรงกัน โดยไม่ auto-create หลักฐานธุรกิจ
- [ ] **P6-03** ทำ retry/replay tools ที่ตรวจ event/task/step และใช้ operation เดิม; เก็บผู้สั่งและเหตุผลใน audit
- [ ] **P6-04** เพิ่ม health/readiness, correlation ID, structured logs และ metrics/alerts สำหรับ outbox lag, dead letters, mail retries และ stuck tasks โดยกรอง secret/PII
- [ ] **P6-05** ทำ orphan-upload cleanup ที่ไม่ลบรูปหลักฐานที่ link แล้ว และกำหนด retention jobs ตาม policy ที่อนุมัติ
- [ ] **P6-06** จัด deploy/config/migration runbook, image version/digest, secret injection และ staging smoke checks; แยก app migration/engine schema upgrade
- [ ] **P6-07** ทดสอบ deploy BPMN เวอร์ชันใหม่ขณะที่ instance เดิมยังรัน โดยคง delegate compatibility และไม่ migrate instance อัตโนมัติ
- [ ] **P6-08** ทำ rollback runbook ครอบคลุม application/schema/process version และข้อมูลใหม่หลัง release; ทดสอบใน staging ก่อนใช้จริง
- [ ] **P6-09** ทำ backup/restore App DB + Flowable state + object evidence ที่สอดคล้องกัน และตรวจ RPO/RTO จากผล restore จริง
- [ ] **P6-10** ทดสอบ worker lease recovery, DB restart, workflow apply failure และ SMTP ACK ไม่แน่นอน โดยไม่อ้าง exactly-once mail delivery
- [ ] **P6-11** ตรวจ HTTPS/callback/access-log redaction, CSRF/cookie/session revocation และ object-level permissions จาก API ตรง
- [ ] **P6-12** ทำ regression T17–T20/T26/T35/T37/T40–T41 และตรวจโหลด/ขนาดรูป/ความเร็ว worklist ตามปริมาณที่ตกลง

### Exit Gate P6

- [ ] **G6** มีหลักฐาน deploy/rollback/restore/reconciliation และ failure recovery; ผู้ดูแลติดตาม/retry งานที่ผิดพลาดได้โดยไม่แก้ตาราง Flowable โดยตรง

## P7 — Security Testing และแก้ไขช่องโหว่

ใช้ [OWASP Web Security Testing Guide](https://owasp.org/www-project-web-security-testing-guide/) และ [OWASP API Security Top 10 2023](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) เป็นแหล่งออกแบบ test cases ให้ตรงระบบนี้ ไม่ใช้ผล automated scan เพียงอย่างเดียวเป็นข้อสรุปว่าระบบปลอดภัย รายการ P7 เป็น security coverage เพิ่มจาก T01–T42 โดยไม่เปลี่ยนเลข acceptance tests เดิม

### ขอบเขตและ automated checks

- [ ] **P7-01** ระบุ release/commit/image ที่ทดสอบ, URL/API/asset inventory และขอบเขต staging พร้อมข้อมูลทดสอบ/บัญชีทุก role และอย่างน้อยสอง Unit; กำหนดขอบเขตและอัตรา active scan ไม่ให้กระทบ Google/SMTP หรือบริการภายนอกที่ไม่อยู่ในขอบเขต
- [ ] **P7-02** ทำ threat review ของ Google callback, Unit/user administration, document/evidence access, workflow commands และ worker/admin retry; เชื่อมความเสี่ยงกับ endpoint/role/test case ที่ตรวจได้
- [ ] **P7-03** ตรวจ source/config ด้วย SAST และ secret scanning รวมประวัติ Git/CI artifacts/frontend bundle; หากพบ secret จริงให้ revoke/rotate และตรวจซ้ำ ไม่เพียงลบข้อความออกจากไฟล์
- [ ] **P7-04** ตรวจช่องโหว่ dependency ฝั่ง Java/JavaScript และ container/base image ของ release ที่จะใช้ บันทึก package/version/severity/ผลกระทบ และแก้หรืออธิบาย false positive ด้วยหลักฐาน
- [ ] **P7-05** ทำ DAST บน staging ทั้ง anonymous และ authenticated role ที่เกี่ยวข้อง รวม authenticated API coverage; ยืนยันว่า scanner เข้า protected routes ได้จริง และทำ manual tests สำหรับ business/authorization ที่ scan ไม่ครอบคลุม

### Identity, authorization และ API

- [ ] **P7-06** ทดสอบ Google-domain spoof, unassigned/disabled user, token issuer/audience/signature/expiry, state/nonce/PKCE, callback replay/concurrency และ Google-sub mismatch ตาม T27–T31; ทดสอบ redirect/return URL ว่าใช้พาออกนอก allowlist ไม่ได้
- [ ] **P7-07** ทดสอบ session fixation/reuse หลัง logout/หมดอายุ/revoke, cookie flags, CSRF ของคำสั่ง และ Origin/CORS policy; การปิด user/Unit หรือเปลี่ยน role ต้องตัดสิทธิ์ session เดิมตาม T35/T41
- [ ] **P7-08** ทดสอบ object-level authorization โดยเปลี่ยน document/unit/station/event/upload/batch/job IDs: list/detail/search/timeline/รูป/download ต้องไม่เห็นหรือแก้ข้อมูลข้าม Unit หรือ leg ที่ไม่มีสิทธิ์ รวมรายการปะปนใน bulk request
- [ ] **P7-09** ทดสอบ function/property-level authorization และ mass assignment: STAFF/UNIT_ADMIN/Courier สร้าง ADMIN, ย้ายคนข้าม Unit, เปลี่ยน mode/policy, ปลอม actor หรือแก้ workflow sync status ผ่าน payload/API ตรงไม่ได้
- [ ] **P7-10** ทดสอบ input validation และ SQL injection/XSS/log injection ในชื่อเรื่อง เลขหนังสือ Unit/template ชื่อไฟล์ exception reason และข้อความในอีเมล; error response ไม่เผย stack trace, SQL หรือ secret

### Evidence, Flowable และโครงสร้างพื้นฐาน

- [ ] **P7-11** ทดสอบ upload ที่ปลอม extension/MIME, ไฟล์ชนิดที่ไม่อนุญาต, ขนาดเกิน/ภาพ decode ผิดปกติ และ path/object-key traversal; ตรวจ private bucket, evidence ownership, signed URL หมดอายุ และ cache/access policy ของรูป
- [ ] **P7-12** ทดสอบ workflow bypass ทั้ง A/B: เรียก task/step ของเรื่องอื่น, replay/out-of-order event, Check แทน courier, ข้าม unit receipt/ready/return หรือแก้ mode เรื่อง active ต้องไม่สำเร็จ; ยืนยันว่า Flowable REST/designer/deployment endpoints ไม่ถูกเปิดให้ผู้ใช้ทั่วไป
- [ ] **P7-13** ทดสอบ rate/resource limits ของ login, upload, bulk size, reservation และ mail/admin retry; การ abuse ต้องไม่ทำให้ส่งอีเมลซ้ำไม่จำกัดหรือเปิด leg/reservation เกิน invariants โดยกำหนดขนาดการทดสอบที่ควบคุมได้
- [ ] **P7-14** ตรวจ HTTPS/TLS, security headers, trusted-proxy/Host handling และ network exposure; DB/object-storage admin/metrics/debug endpoints เข้าถึงได้เฉพาะสิทธิ์ที่จำเป็น และ runtime DB/storage credentials มีสิทธิ์เท่าที่ใช้
- [ ] **P7-15** ตรวจ OAuth client secret, SMTP/storage credentials, token/code/session/cookie และข้อมูลหนังสือใน logs/audit/notification/backup access; callback access logs ต้องไม่เก็บ authorization code และผู้ไม่มีสิทธิ์อ่าน security evidence ไม่ได้
- [ ] **P7-16** ทดสอบ outbound-request/SSRF controls หากมีฟังก์ชันรับ URL หรือ HTTP delegate ที่ปรับปลายทางได้; หากไม่มีพื้นผิวนี้ให้บันทึก N/A พร้อมหลักฐาน และยืนยันว่าผู้ใช้ส่ง BPMN/expression/URL เพื่อสั่ง backend ติดต่อปลายทางเองไม่ได้

### แก้ไข ทดสอบซ้ำ และสรุปผล

- [ ] **P7-17** จัดทำ finding register: severity/ผลกระทบ, endpoint/role, ขั้นตอนทำซ้ำ, evidence ที่ปกปิดข้อมูล, owner และวิธีแก้; แก้ Critical/High และทดสอบซ้ำทั้งกรณีโจมตีและ normal flow A/B/Google/login email ที่ได้รับผลกระทบ
- [ ] **P7-18** สรุป security report ของ release ที่แก้แล้วพร้อม scan/manual/retest results; Medium/Low ที่เหลือมีผู้รับผิดชอบ กำหนดแก้ และการตัดสินรับความเสี่ยงที่บันทึกไว้ รวมข้อจำกัดการทดสอบและ N/A ที่มีเหตุผล

### Exit Gate P7

- [ ] **G7** Security report ระบุ release/ขอบเขต/ผลตรวจครบ, ไม่มี Critical/High ที่ยังไม่แก้, finding ที่แก้ผ่าน retest และความเสี่ยงที่เหลือมีผู้รับผิดชอบ/แผน/การตัดสินชัดเจนก่อนเข้า Pilot; หากมีการแก้ auth/permissions/workflow/dependencies หลังรายงานต้องตรวจส่วนที่ได้รับผลกระทบซ้ำก่อนใช้ gate เดิม

## P8 — Staging Verification, UAT และ Pilot

### งานที่ต้องทำ

- [ ] **P8-01** ทดสอบ Google OAuth จริงบน HTTPS staging กับ Central Admin/UNIT_ADMIN/STAFF/Courier ที่อยู่ใน allowlist; ตรวจบัญชีนอกโดเมน/ไม่มีสิทธิ์ถูกปฏิเสธ
- [ ] **P8-02** ทดสอบ SMTP relay จริงและตรวจอีเมล login ถึงกล่อง MFU ของบัญชีทดสอบ รวมการติดตาม failed jobs; ผ่าน T42
- [ ] **P8-03** ทำ UAT Model B ครบ receipt/ready/return และ Model A ครบ physical pickup/observation/tracking-complete แยกผลรับรองแต่ละโมเดล
- [ ] **P8-04** ทำ UAT Central Admin เพิ่ม Unit/ผู้ใช้ และ Unit Admin เพิ่ม STAFF ของตน รวมการปิด/ย้ายสิทธิ์ที่มีงานค้าง
- [ ] **P8-05** ทดสอบ A/B พร้อมกันและสลับ default ทั้งสองทิศทางระหว่างมีเรื่องค้าง โดยเรื่องเดิมไม่เปลี่ยนโมเดล
- [ ] **P8-06** ทดสอบมือถือ iOS Safari/Android Chrome, camera/upload, bulk selection, touch targets, keyboard/labels และข้อความ error/status กับผู้ใช้จริง
- [ ] **P8-07** สรุปผล T01–T42 ทั้งหมดใน scope พร้อมหลักฐาน; บันทึกกรณีไม่อยู่ใน scope และผู้อนุมัติ ไม่ข้าม test แบบเงียบ
- [ ] **P8-08** จัดคู่มือ Unit/Courier/Admin, วิธีรับมือ dispute/login/mail failure และอบรมผู้รับผิดชอบระบบ
- [ ] **P8-09** ยืนยันผู้ดูแล production/Google client/SMTP/backup/alerts และขั้นตอน incident/rollback ก่อน Pilot
- [ ] **P8-10** ปิดข้อบกพร่องที่ขวาง Pilot และรับ UAT sign-off; บันทึกขอบเขตผู้ใช้/หน่วยงานและเงื่อนไขประเมินผล Pilot

### Exit Gate P8

- [ ] **G8** ผ่าน Security Gate G7 และมี staging verification/UAT sign-off, test evidence และผู้รับผิดชอบ operations พร้อมตาม scope; การขึ้น production ใช้ขั้นตอน release/อนุมัติขององค์กรแยกจากการทำ checklist

## Acceptance coverage ที่ต้องติดตาม

| กลุ่มทดสอบ | ตรวจหลักใน phase | ตรวจซ้ำก่อน Pilot |
|---|---|---|
| T01–T06 — Model B | P3 ผ่าน isolated real-browser flow ตาม [หลักฐาน](p3/Execution_Report.md) | P8 UAT |
| T07–T10 — Model A / observation | P4 ผ่านตาม baseline: A delivery ครบและ observation gap เปิด exception/hold; approved resume ยังเป็น P5/X07 | P8 |
| T11–T16 — Concurrency / bulk / direct | P1 พื้นฐาน, P5 ครบ | P8 |
| T17–T19 — Default mode / snapshot | P4 ผ่าน B→A→B/current instance isolation/template version ตาม [หลักฐาน](p4/Test_Results.json) | P6/P8 |
| T20 — Duplicate event / restart | P2 ผ่าน worker/JVM recovery และ replay ตาม [หลักฐาน](p2/Test_Results.json) | P6/P8 |
| T21 — Evidence failure | P1/P3 ผ่าน; corrupt PNG ถูกปฏิเสธ ไม่มี Check request หรือ version เปลี่ยน และเลือกไฟล์ใหม่ทำต่อได้ | P8 อุปกรณ์/storage จริง |
| T22 — Receipt dispute | P5 | P8 |
| T23–T25 — Custody / scope / route steps | P2/P3 ผ่าน; wrong courier, outsider และ wrong return occurrence ถูกปฏิเสธ | P8 |
| T26 — Process version | P2 version pinning ผ่าน; deploy/rollback จริงตรวจต่อ P6 | P8 |
| T27–T34 — Google / identity / provisioning | P1 | P7/P8 |
| T35 — Revoke / outstanding work / last admin | P1 foundation และ P3 document/reservation/leg guards ผ่าน; bulk/direct/exception ยังรอ P5 | P6/P7/P8 |
| T36–T41 — Login email / failure / session | P1 | P6/P7/P8 |
| T42 — Google/SMTP staging จริง | P8 | ก่อน Pilot |
| P7-01–P7-18 — Security coverage เพิ่มเติม | P7 | Retest เมื่อแก้ไขก่อนผ่าน G8 |

## บันทึกหลักฐานผ่าน phase

ใส่ลิงก์หลักฐานที่ตรวจแล้ว ไม่ใช้ช่องนี้แทนผลทดสอบหรือการตัดสินของผู้รับผิดชอบ

| Phase | ผู้รับผิดชอบ | Commit/PR/ผลทดสอบ | วันที่ผ่าน Gate | เรื่องค้าง/ข้อยกเว้นที่อนุมัติ |
|---|---|---|---|---|
| P0 | Codex: technical artifacts; business/infra owners รอระบุ | [Execution Report / test results](p0/Execution_Report.md) | ยังไม่ผ่าน G0 | P0-05–09/P0-14 และ field/image/exception policies ใน Decision Log |
| P1 | Codex implementation; infra/business owners รอยืนยัน | [Execution Report / 29 backend tests + frontend checks](p1/Execution_Report.md) | 2026-11-06 เฉพาะ development G1 | G0 ยังเปิด; P0 policies, real Google/SMTP/S3/HTTPS staging, container runtime; workflow/UI A/B ผ่าน P2–P4 แล้ว |
| P2 | Codex implementation; independent core review | [47 backend tests + JVM recovery](p2/Execution_Report.md) | 2026-11-06 เฉพาะ backend/headless G2 | G0 ยังเปิด; timer 24h เป็น dev baseline/durable feed ยังไม่ส่ง email; UI A/B ผ่าน P3/P4 แล้ว ส่วน operations/staging เป็น P6–P8 |
| P3 | Codex implementation; scoped API/UI review | [54 backend/browser tests + UI smoke + screenshots](p3/Execution_Report.md) | 2026-11-07 เฉพาะ development G3 | G0 ยังเปิด; real Google/SMTP/S3/HTTPS/physical camera และ UAT เป็น P8; Model A/default switch ผ่าน P4 แล้ว |
| P4 | Codex implementation; independent scoped policy/observation/UI review | [70 backend/browser tests + screenshots](p4/Execution_Report.md) | 2026-11-07 เฉพาะ development G4 ตาม baseline | G0/X07 ยังเปิด; observation ข้ามจุดพักรอ approved resolution P5; bulk/direct/exception P5, operations/security/staging P6–P8 |
| P5 | Codex implementation; independent bulk/recovery/privacy review | [81 backend + 1 real-browser test / screenshots](p5/Execution_Report.md) | ยังไม่ผ่าน G5; P5-01–04 ผ่าน development 2026-11-07 | D08 partial success ยืนยันแล้ว; D06/X07–08 และ P5-05–11 ยังเปิด |
| P6 | — | — | — | — |
| P7 | — | — | — | — |
| P8 | — | — | — | — |
