# P5 — Bulk partial success

วันที่: 2026-11-07 (Asia/Bangkok)

สถานะ: **P5-01–04 ผ่านเฉพาะ bulk development (4/11)**; P5 ทั้ง phase และ G5 ยังไม่ผ่าน ส่วน direct transfer และ exception resolution รอยืนยัน D06/X07–08 แยกจาก D08

ผู้ใช้ยืนยัน D08 ว่า “ให้ 8 เรื่องสำเร็จทันที แล้วแจ้งว่าอีกสองมีผู้รับไปแล้ว” ดู [Decision Log](../p0/Decision_Log.md) และ [API contract](../../contracts/README.md)

## พฤติกรรมที่พัฒนา

- เลือก 1–100 เรื่องที่ Station ต้นทางเดียวเพื่อจอง/Check รับ และเลือก courier legs ของตนที่ปลายทางเดียวเพื่อ Check ส่ง รวมถึงยืนยันรับหลายเรื่องของหน่วยงานที่จุดเดียว
- เรื่องที่ผ่านสำเร็จทันที ส่วนเรื่องที่ conflict มี `document_id`, code และเหตุผลรายเรื่อง ไม่มีข้อมูลชื่อผู้จอง/เจ้าของเรื่องนอกสิทธิ์ เมื่อมีผู้จองหรือถือครองไปแล้วจะแสดง conflict นั้นก่อน stale-version ทั่วไป; การส่งรายการเดียวคง HTTP errors เดิม
- ใช้ transaction ของ CommandService พร้อม JDBC savepoint รายเรื่อง การปฏิเสธทางธุรกิจ/constraint ย้อนเฉพาะเรื่องนั้น; unexpected system/transaction failure ยัง rollback ทั้งคำสั่งได้ Idempotency key เดิมคืนผลเดิมโดยไม่สร้างจอง/leg/event ซ้ำ
- ล็อก document UUID ตามลำดับข้อความเดียวกันทุกเส้นทางก่อนล็อก reservation/leg การรับ subset เก็บสมาชิกที่เหลือใน reservation เดิม; cancel/expiry จัดการทุกสมาชิกที่ยัง active โดยไม่ปล่อย custody ของ courier
- แสดงรายชื่ออ้างอิงและจำนวนก่อนรับ/ส่ง ใช้รูป finalized แบบ Station ร่วมชุดได้ แต่ทุกเรื่องที่สำเร็จมี event/evidence link ของตน รูปที่ผูกเอกสารเฉพาะไม่อนุญาตกับเรื่องอื่น รูปทุกช่องที่เพิ่มต้องพร้อมหรือเอาออกอย่างชัดเจนก่อนยืนยัน
- `operation_documents` ใน migration V4 ผูกทุกเรื่องที่สำเร็จกับ operation เดียว รวมถึง backfill และเชื่อม singleton commands เดิม ผลชุดที่ไม่ผ่านทั้งหมดเป็น actor-only operation โดยไม่สร้าง reservation ว่าง
- Polling แยกสถานะแต่ละเรื่องและรวม workflow deliveries ทั้งชุด ไม่แจ้ง APPLIED หลังเรื่องแรก Any failure => FAILED_RETRYABLE; otherwise pending => PENDING; APPLIED เมื่อครบทุก delivery Operation ที่มีรายการปฏิเสธ/UUID ไม่พบเปิดให้ผู้ส่งคำสั่งเท่านั้น; ชุดสำเร็จทั้งหมดต้องเป็นผู้ส่งหรือเห็นครบทุก origin
- Admin เลือก retry ด้วย delivery_id และ version ของ delivery นั้นใน operation เดิม พร้อม audit ไม่สร้างคำสั่งธุรกิจใหม่ และไม่ทำเรื่องที่สำเร็จซ้ำ

## ขอบเขตที่ยังค้าง

P5-05–06 direct dispatch/receipt/return ยังไม่เปิด รอยืนยัน photo policy D06; P5-07–09 exception resolution/resume/approved correction รอ X07–08/D10 ไม่ได้เปลี่ยน workflow ไปยังขั้นใหม่โดยเดาเอง P5-10 ยังต้องตรวจ outstanding guards กับ direct/exception จริง และ P5-11/G5 ยังต้องทดสอบรวมครบทุกส่วน P6 ไม่ได้เริ่มในงานนี้

นโยบาย TTL production, go-live, field/evidence visibility และ infra decisions ที่เปิดใน G0 ไม่ได้ถูกอนุมัติเพิ่มจากผลทดสอบ isolated

## ผลตรวจ

| ชุดตรวจ | ผล |
|---|---|
| Maven Wrapper `verify` | 82 discovered: 81 ผ่าน, 0 failure/error, browser opt-in 1 ถูกข้าม; executable JAR สร้างได้ |
| BulkTransportIntegrationTest | 8 tests ผ่าน: 10/2 conflict/replay, shared photo/partial pickup/dropoff/receipt/aggregate sync, subset then cancel/expiry, reversed concurrent batches, duplicates/all rejected, simultaneous pickup replay, expiry race และ constraint failure หลังเขียน custody ที่ rollback เฉพาะเรื่อง |
| WorkflowOutboxIntegrationTest | เพิ่ม 4 tests จาก baseline: aggregate failure + selected audited retry/idempotency, concurrent ACK, multi-origin visibility และ rejected/unknown selection privacy; ใช้ engine จริง |
| Real-browser opt-in P5 | 1 test / 23 checks ผ่านด้วย signed test-local OIDC → Spring HTTP → PostgreSQL → production worker/Flowable จริง ไม่มี business API mocks; รวม Model B A→B→C→A regression และ bulk 8 เรื่อง |
| Frontend | typecheck/build ผ่าน; Node tests 2 ผ่าน; UI smoke fixtures 20 checks desktop/mobile ผ่าน |
| OpenAPI | `0.6.0-p5-bulk` valid, 40 paths / 47 operations; JavaScript syntax และ `git diff --check` ผ่าน |

รวม backend/browser **82 tests ผ่าน** จาก default verify และ opt-in run แยกกัน ดู [Test_Results.json](Test_Results.json) การตรวจรอบแรกพบเพียง assertion เปรียบเทียบชนิดตัวเลขใน Java กับผล replay หลัง deserialize จึงปรับ test ให้เปรียบเทียบ serialized JSON จริง จากนั้นรอบสุดท้ายผ่านครบ ไม่ได้ลดการตรวจผล retry

Browser DB assertions ตรวจ 8 เรื่องเป็น UNIT_PROCESSING/APPLIED, มี 1 courier leg/เรื่อง, 2 event/evidence links/เรื่อง และ 1 explicit unit receipt/เรื่อง อีก 2 เรื่องยังเป็นการจองของผู้ที่จองก่อน ทุกคน login ผ่าน PKCE/JDBC session และมี login notification outbox ใน test harness; ไม่ได้ทดสอบ Google/SMTP ของ MFU จริงในรอบนี้

## ภาพหลักฐาน

ตรวจ desktop/mobile 390px ในรอบเดียว ไม่มี horizontal viewport overflow; ตารางเลื่อนภายในได้ ภาพเป็น test fixtures ไม่มีข้อมูลหนังสือจริง ภาพยืนยันรับอาจยังแสดง PENDING ระหว่าง bounded polling ขณะที่ assertion รอ DB/Flowable เป็น APPLIED ครบแล้ว

- [สำเร็จ 8 / conflict 2 desktop](images/bulk-conflict-desktop.png) / [mobile](images/bulk-conflict-mobile.png)
- [หน่วยงานยืนยันรับชุด desktop](images/bulk-receipt-desktop.png) / [mobile](images/bulk-receipt-mobile.png)

## รันซ้ำ

กำหนด Java 21, test PostgreSQL และ `TEST_DB_URL`/`TEST_DB_USER`/`TEST_DB_PASSWORD` ใน environment ส่วนตัวก่อน ไม่ใช้ credentials จริงกับ test fixture จาก `backend` รัน `./mvnw.cmd verify`; จาก `frontend` รัน `npm.cmd test`, `npm.cmd run typecheck` และ `npm.cmd run build`

Opt-in browser ใช้ Chrome และ Playwright ที่ติดตั้งอยู่แล้ว Build frontend ก่อน แล้วใน PowerShell จาก backend:

```powershell
$env:P3_BROWSER_ENABLED='true'
$env:P5_BROWSER_ENABLED='true'
.\mvnw.cmd '-Dtest=P3BrowserIntegrationTest' test
Remove-Item Env:P3_BROWSER_ENABLED,Env:P5_BROWSER_ENABLED
```

Browser gateway ใช้ loopback port 62916 ตาม test harness เท่านั้น Test OIDC provider อยู่ใน test sources และไม่มี authentication bypass ใน production JAR รุ่นนี้ พอร์ตและภายนอกจริงยังต้องตรวจใน P8

