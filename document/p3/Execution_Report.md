# P3 Execution Report

วันที่ 2026-11-07 · Model B ผ่าน UI ใน isolated development environment

## สิ่งที่ส่งมอบ

- Origin ลงทะเบียน/ดูทะเบียน/รายละเอียด/เส้นทาง พร้อม READY และยืนยันรับคืนที่ final return step โดยแยกจากจุด A แรก
- Worklist หน่วยงานแยกจากคิวรับ Courier; ผู้ใช้ต้องมี active membership และ Station grant จึงยืนยันรับ/READY ได้ รายการ pending/failed เป็นแบบอ่านอย่างเดียว
- Courier จองรายการเดียว ตรวจหนังสือจริง อัปโหลดรูปและยืนยัน physical presence ก่อน Check รับ; หน้า “งานนำส่งของฉัน” แสดงเฉพาะ leg ของตนเพื่อ Check ส่งที่ปลายทางพร้อมรูป
- รูปใช้ SHA-256 → raw upload → ตรวจ decode/MIME/ขนาด → finalize ก่อนใช้ในคำสั่ง; upload ล้มเหลวไม่ทำ Check หรือเปลี่ยน custody
- Timeline แยกรายงานรับ/ส่งของ Courier, unit receipt, READY และ origin return พร้อม actor/time และลิงก์รูปที่ backend ตรวจสิทธิ์
- UI แสดง business status และ synchronization แยกกัน ใช้ operation polling แบบจำกัดจำนวน ไม่ส่ง mutation ซ้ำเพื่อรอ workflow; conflict ให้โหลดข้อมูลล่าสุดก่อนยืนยันใหม่
- เพิ่ม `GET /stations/{id}/unit-worklist`, `GET /courier/legs`, current document version ใน own reservations และ actor display name ใน origin timeline พร้อม [OpenAPI](../../contracts/openapi.yaml)
- แก้ static build config ที่ปิด prerender ทุก route จนไม่มี `index.html`; ชุด browser test ตรวจว่า SPA entrypoint มีจริงก่อนเริ่ม

Full detail/timeline/รูปที่แนบยังจำกัดต้นทาง; non-origin worklist ปิด title/number ตาม conservative field policy เดิม ไม่มี implicit admin access หรือคำสั่ง Model A ใน UI ของ P3

## การตรวจสอบ

| ชุดตรวจ | ผล |
|---|---|
| Maven Wrapper `verify` บน PostgreSQL จริง | 53 tests ผ่าน, 0 failures/errors; opt-in browser 1 test ถูกข้ามตามปกติ และ executable JAR สร้างสำเร็จ |
| P1/P2 regression | 47 tests ผ่าน รวม identity, signed OAuth, loopback SMTP, registry/evidence/reservation, engine A/B, commands และ outbox/recovery |
| WorkflowQueriesIntegrationTest | 6 tests ผ่าน: exact receipt/return occurrence, pending action guards, membership/Station scope, inactive-origin redaction, current reservation version, own-leg access, administrative removal guards และ session revocation |
| P3BrowserIntegrationTest แบบ opt-in | 1 test ผ่าน, 0 failures/errors/skips; Spring HTTP + PostgreSQL + Flowable + Chrome จริง; 20 browser checks |
| Frontend | typecheck ผ่าน, Node tests 2 ผ่าน, static build ผ่านพร้อม `index.html` |
| UI smoke ด้วย API fixtures แยกจาก integration | 20 checks ผ่าน desktop/mobile รวม PENDING/FAILED_RETRYABLE เป็น read-only; 0 page errors |
| OpenAPI 0.4.0-p3 | validation ผ่าน 39 paths / 46 operations |
| Visual verification | ตรวจภาพ desktop/mobile; รายละเอียด/timeline, การอัปโหลดและยืนยันรับจริง, worklist/empty state ไม่มี horizontal viewport overflow; ตารางเลื่อนภายในได้ |

รวม backend/browser **54 tests ผ่าน** จาก default verify และ opt-in run แยกกัน ผลแบบอ่านด้วยโปรแกรมอยู่ [Test_Results.json](Test_Results.json) Browser run สุดท้ายวันที่ 2026-11-07 ใช้ document `f12ba94e-9ad1-4e92-b825-7a7074f418a3` ซึ่งเป็นข้อมูลทดสอบเท่านั้น

DB assertions ยืนยันหนึ่ง workflow instance, 3 legs ที่มี Courier 3 คนต่างกัน, รูป linked กับ pickup/drop-off 6 รูป, READY 3 ครั้ง, intermediate unit receipt 2 ครั้ง, final return โดย origin และ outbox ทั้งหมด APPLIED ผู้ใช้ 7 คนผ่าน signed code/PKCE login จริงภายใน harness และมี login email outbox คนละหนึ่งรายการ; logout ยกเลิก JDBC session สำเร็จ

| Acceptance / checklist | หลักฐาน |
|---|---|
| P3-01–02 / T01 | ลงทะเบียนจาก browser; document ไม่อยู่ courier queue ก่อน origin READY; read-model scope tests |
| P3-03–05 / T02–T05 | reserve → รูป → pickup → รูป → drop-off; queue B/C ว่างก่อน receipt และหลัง receipt จนกด READY |
| P3-06 / T06 / T25 | หลัง final courier drop-off ยัง PENDING_ORIGIN_RETURN_RECEIPT; wrong initial occurrence และ intermediate staff ถูกปฏิเสธ; origin ยืนยัน exact final step จึง COMPLETED |
| P3-07 | Timeline 16 events แยก actor/time; origin ดาวน์โหลด PNG ที่ linked ทั้ง 6 รูปได้ ผู้ใช้นอกหน่วยงานอ่านรูปไม่ได้ |
| P3-08 / T21 | อัปโหลด PNG เสียบน viewport 390px ถูก server ปฏิเสธ; pickup disabled ไม่มี Check request และ version ไม่เปลี่ยน จากนั้นเลือกไฟล์ถูกต้องและทำต่อได้; pending/failed UI fixtures ผ่าน |
| P3-09 / T23 | 3 distinct courier leg owners; wrong courier drop-off 403 ทุกช่วงโดย version ไม่เปลี่ยน; integration ทดสอบปิดผู้ใช้/ถอด grants/ปิดหน่วยงานกับ document/reservation/leg จริงและ role-change session revocation |
| P3-10 / T24 | signed-in outsider อ่าน document/รูปได้ 404; missing CSRF 403 ไม่เปลี่ยนข้อมูล; Google/session/permission regression ผ่าน |
| G3 | Model B A→B→C→A ผ่าน UI จริงพร้อมรูปและผู้รับผิดชอบแต่ละช่วง จบหลัง origin return เท่านั้น ใน isolated development |

ภาพทั้งหมดเป็น fixture identities/หนังสือทดสอบและรูป PNG จาก canvas:

- [Mobile pickup / photo / physical presence](images/pickup-mobile.png)
- [Mobile unit receipt worklist](images/unit-receipt-mobile.png)
- [Completed desktop / complete timeline](images/completed-desktop.png)
- [Completed mobile](images/completed-mobile.png)
- [Mobile station empty state](images/station-mobile.png)

ระหว่างตรวจพบ static build ขาด SPA entrypoint จึงแก้ Nuxt route rule และเพิ่ม test precondition จากนั้นรันใหม่จนผ่าน การ capture ภาพใช้ explicit application state และ fonts/layout readiness แทนการรอ network idle ไม่รวม run ที่ล้มเหลวเหล่านั้นเป็นผลผ่าน

Scoped API/UI review พบ inactive origin membership อาจเปิด title/number ใน worklist และ stale version conflict อาจให้ยืนยันซ้ำ แก้ active-unit redaction พร้อม regression และให้ form ปิดการยืนยันหลัง 409 จนโหลดข้อมูลใหม่แล้ว

## วิธีรันซ้ำ

ใช้ PostgreSQL ทิ้งได้ที่สร้าง schema ได้ ตั้ง `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD` ตาม [README](../../README.md) แล้วรัน `backend/mvnw.cmd verify` ส่วน frontend ใช้ `npm.cmd run typecheck`, `npm.cmd test`, `npm.cmd run build`

Browser integration เป็น opt-in และต้องมี Chrome กับ Playwright ที่ติดตั้งแล้ว:

```powershell
# หลัง build frontend; ตั้ง TEST_DB_* เป็นฐานทดสอบส่วนตัวก่อน
$env:P3_BROWSER_ENABLED='true'
$env:PLAYWRIGHT_PACKAGE_JSON='<absolute path to package.json with playwright installed>'
$env:BROWSER_CHANNEL='chrome'
cd backend
.\mvnw.cmd --batch-mode '-Dtest=P3BrowserIntegrationTest' test
```

Test เปิด Spring HTTP บน random port, local signed OIDC provider และ loopback same-origin gateway port 62916; ใช้ session/CSRF/command/evidence APIs และ production outbox worker/Flowable จริง ไม่มีการ seed `APPLIED`, ข้าม READY หรือ mock business APIs ภาพทดสอบสร้างจาก canvas เป็นไฟล์ PNG ไม่ใช่ภาพหนังสือจริง ส่วน UI smoke ใช้ API fixtures แยกต่างหากสำหรับสถานะ pending/failed

## ขอบเขตที่ยังไม่ปิด

- G0 business/infra approvals ยังเปิดตาม Decision Log; การทดสอบไม่ใช่การอนุมัติ TTL/retention/SLA/field policy
- Mobile browser viewport/file upload และ `capture="environment"` ไม่ใช่ผลทดสอบกล้องบนโทรศัพท์จริง; ต้องตรวจ physical devices และ permissions ใน P8
- Google provider ใน browser test อยู่ใน test source เท่านั้น ยังไม่ใช่ Google/MFU Workspace จริง; email ยืนยันเฉพาะ login outbox ไม่ได้ส่งถึง MFU inbox; staging Google/SMTP/HTTPS/private S3 ยังคงเป็น P8
- Model A UI/default switch เป็น P4; bulk/direct transfer/exception เป็น P5 และ operations/security/staging เป็น P6–P8
