# P4 Execution Report

วันที่ 2026-11-07 · ขอบเขต isolated development: Model A UI, default A/B และ observation ตาม conservative baseline

## สิ่งที่ส่งมอบ

- เปิด Model A ใน courier worklist, reservation/pickup และ own-leg/drop-off ที่ใช้รูป validated และยืนยันถือหนังสือจริง ไม่บังคับ Unit READY/receipt; final courier drop-off จบเป็น `COURIER_TRACKING_COMPLETE` พร้อมข้อความที่ไม่อ้างว่าหน่วยงานรับคืนแล้ว
- เพิ่มตัวกรอง Model/สถานะสำหรับรายการที่โหลดแล้วและป้าย A/B; ใช้สถานะของแต่ละเรื่อง ไม่ใช้ default ปัจจุบันตัดสิน workflow ของเรื่องเก่า
- เพิ่ม Admin `GET/PUT /api/v1/admin/workflow-policy` และหน้า `/admin/workflow-policy` พร้อม reason, expected version, idempotency, current-admin authorization และ audit old/new mode/version/actor/time การเปลี่ยนไม่มีผลต่อ snapshot, task หรือรูปของเรื่องเดิม
- Registration ใช้ policy row `FOR SHARE` เดิม; policy update ใช้ `FOR UPDATE` จึง serialize การเปลี่ยน default กับการสร้างเรื่องใน transaction เรื่องใหม่เก็บ mode/policy version และ template version ณ ตอนสร้าง ไม่รับ per-document mode override
- เพิ่ม `GET /courier/observation-context` ที่รับ document UUID และ assigned observed Station คืนเฉพาะข้อมูลสถานะ/version และ exact occurrences ของ Station นั้น ปิด title/number/origin/full route และไม่เปิด operation ของ actor อื่นให้ poll
- เพิ่ม `POST /documents/{id}/observations` และหน้า `/observations` พร้อม exact step, reason, physical confirmation, station-context photo, version/idempotency และ guards ของ pending/custody/terminal/exception
- Timeline แสดง observed Station/step/reason และแยกเวลา audit ของการบันทึกช่องว่างจากเวลานำส่งที่ไม่ทราบ ผู้ส่งของ `UNOBSERVED_TRANSFER` เป็น null ไม่ใช้ชื่อผู้พบแทนคนที่นำส่งช่วงนั้น

## ขอบเขต observation/reconciliation

ยึด [Design Baseline §3](../p0/Design_Baseline.md) และ [Decision Log X07](../p0/Decision_Log.md) ซึ่งยังไม่อนุมัติการ reconcile ข้ามจุดอัตโนมัติ:

| การพบจริง | ผล |
|---|---|
| exact current step | บันทึก local `OBSERVED_AT` พร้อม actor/time/photo; ไม่เปลี่ยน custody, current/reported step หรือ Flowable task |
| ต่าง occurrence, ข้ามจุด, ย้อนจุด, final return occurrence หรือ off-route | บันทึก `OBSERVED_AT`, `UNOBSERVED_TRANSFER`, `EXCEPTION_OPENED` และ case; สถานะเป็น `EXCEPTION`; คง current/reported และ engine task เดิม |
| Station ซ้ำใน route | ต้องเลือก exact step UUID; ไม่เลือก occurrence จากชื่อ Station โดยอัตโนมัติ |
| พบที่ return Station | ไม่สร้าง courier final drop-off/unit receipt สมมติ และไม่จบเรื่องจาก observation อย่างเดียว |

การ resolve/resume หลังพบข้ามจุดยังเป็น P5-08 ภายใต้ policy ที่ได้รับอนุมัติ ก่อนหน้านั้น Check รับที่จุดใหม่ถูกบล็อกไว้ UI แจ้งว่ารอผู้มีสิทธิ์ตรวจ ไม่ได้ให้ผู้ใช้แก้ตำแหน่งหรือข้าม task เอง

## ผลทดสอบ

| ชุดตรวจ | ผล |
|---|---|
| Maven Wrapper `verify` | 69 tests ผ่าน, 0 failures/errors; opt-in browser 1 test ถูกข้ามตามเงื่อนไข; executable JAR สร้างได้ |
| P1–P3 regression | 53 backend tests ผ่าน ครอบคลุม identity, signed OAuth, loopback SMTP, registry/evidence/reservation, A/B engine, outbox/recovery และ scoped read models |
| WorkflowPolicyIntegrationTest | 4 tests: B→A→B ระหว่าง A/B active, process/task/definition/route เดิมไม่เปลี่ยน, template version, concurrency, audit rollback/replay/stale version และ HTTP permission/CSRF/mass assignment/ไม่มี active-mode endpoint |
| ObservationIntegrationTest | 10 tests: same-step, B→C gap, repeated/final/backward/off-route, actual terminal A, evidence/version/reason/physical guards, reservation/leg/pending/model mismatch, rollback/replay, current scope/session และ HTTP CSRF/polling |
| ObservationContextIntegrationTest | 2 tests: scoped repeated-station choices/off-route, ไม่มี registry fields, pending/reservation read-only และ operation owner, revoked capability/Station/Unit/session และ Model B hidden |
| Real-browser integration แบบ P4 opt-in | 1 test ผ่านพร้อม P3 regression; signed test-local OIDC → Spring HTTP → PostgreSQL → production worker/Flowable จริง ไม่มี business API mocks |
| Frontend | typecheck ผ่าน, Node tests 2 ผ่าน, static build ผ่านพร้อม `index.html`; UI smoke fixtures 20 checks desktop/mobile ผ่าน |
| OpenAPI | `0.5.0-p4` validation ผ่าน 40 paths / 47 operations |

รวม backend/browser **70 tests ผ่าน** จาก default verify และ opt-in run แยกกัน ดู [Test_Results.json](Test_Results.json) การตรวจครั้งสุดท้ายแก้ observation context ไม่ให้ poll operation ของต้นทาง พร้อม regression ทั้ง null ของงานคนอื่นและ operation ของตนเอง

## Acceptance / checklist

| รายการ | หลักฐาน |
|---|---|
| P4-01–02 / T07–T09 | Browser สร้าง A หลัง switch แล้วรับ/ส่งครบ A→B→C→A ด้วย Courier 3 คนและรูป 6 รูป ไม่มี READY/unit receipt/origin return; terminal แยกจาก B |
| P4-03–04 / T10 | พบจริงที่ current occurrence แล้วส่ง A→B; Courier คนอื่นพบที่ C เปิดช่องว่างพร้อมรูปและ exception; ไม่เดาผู้ส่ง/เวลาหรือ advance task; repeated/off-route/final/backward ผ่าน integration ตาม baseline X07 |
| P4-05–07 / T17–T19 | Browser Admin สลับ B→A และ A→B ขณะเรื่องเดิม active; เรื่องใหม่ได้ policy ใหม่ เรื่องเดิมคง mode/route/version; HTTP และ DB snapshot guard ปฏิเสธ active override |
| P4-08 | A/B อยู่ร่วมกัน; Model/state filters, template v2, definition/task pinning และป้ายความหมาย terminal ผ่าน tests/review |
| P4-09 / G4 | Model A terminal และ Model B origin return ผ่าน browser เดียวกันหลังสลับ default; P2 T20 recovery/identity regression ผ่านชุดรวม; เป็น development gate เท่านั้น |

Browser DB assertions ตรวจ Model A 3 distinct courier owners/ไม่มี fake unit events, observation case ยังเปิดและ engine ค้างที่ `COURIER_PICKUP`, policy audit 2 ครั้ง และ Model B จบหลัง origin return เท่านั้น ผู้ใช้ 8 คนมี signed PKCE login/JDBC session และ login email outbox จริงภายใน test harness

## ภาพหลักฐาน

ภาพเป็น fixture users/หนังสือทดสอบและ PNG สร้างจาก canvas ไม่มีข้อมูลหนังสือจริง ตรวจทั้ง desktop และ mobile 390px ในรอบเดียว ไม่มี horizontal viewport overflow; ตารางเลื่อนภายในได้

- [Model A desktop](images/model-a-desktop.png) / [mobile](images/model-a-mobile.png)
- [Admin policy desktop](images/policy-desktop.png) / [mobile](images/policy-mobile.png)
- [Observation mobile](images/observation-mobile.png) / [gap timeline desktop](images/gap-timeline-desktop.png)
- [Model B regression completed](images/completed-desktop.png)

## วิธีรันซ้ำ

ตั้ง `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD` ให้เป็น PostgreSQL ทิ้งได้ที่สร้าง schema ได้ตาม [README](../../README.md) แล้วรัน `backend/mvnw.cmd verify` Frontend ใช้ `npm.cmd run typecheck`, `npm.cmd test`, `npm.cmd run build`

```powershell
# หลัง build frontend และตั้ง TEST_DB_*; ต้องมี Chrome และ Playwright ที่ติดตั้งแล้ว
$env:P3_BROWSER_ENABLED='true'
$env:P4_BROWSER_ENABLED='true'
$env:PLAYWRIGHT_PACKAGE_JSON='<absolute path to package.json with playwright installed>'
$env:BROWSER_CHANNEL='chrome'
cd backend
.\mvnw.cmd --batch-mode '-Dtest=P3BrowserIntegrationTest' test
```

P4 ขยาย harness P3 เดิม จึงใช้ test class เดิม; flag P4 เพิ่ม Admin/policy/Model A/observation ก่อนทดสอบ Model B และเขียน artifacts ไป `frontend/test-results/p4` ปิด flag P4 เพื่อรันเฉพาะ flow P3 ได้ Gateway อยู่ loopback port 62916, Spring ใช้ random port, provider อยู่ใน test source เท่านั้น ไม่มี login bypass ใน production

## งานที่ส่งต่อ

- G0 และ X07/TTL/retention/SLA/field-policy approvals ยังเปิด; development gate ไม่แทน business sign-off
- Approved reconciliation/resume, bulk/direct transfer/exception operations เป็น P5; ไม่มี automatic skipped-step pickup หรือ active-instance mode migration ใน P4
- Operations/reconciliation jobs/deploy/restore เป็น P6, security sign-off เป็น P7, real Google/SMTP/private S3/HTTPS/physical camera และ UAT เป็น P8
- Filter เป็นการกรองรายการที่โหลดในหน้า ไม่ใช่ server-side search ทั่วฐาน; UI ระบุขอบเขตนี้ไว้
