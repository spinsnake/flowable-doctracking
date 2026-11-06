# MFU Document Tracking — P1/P2

Spring Boot 4.0.9 / Java 21 / Flowable 8.0.0 / PostgreSQL 17.11 backend และ Nuxt 4 / TypeScript frontend

P1 มี Google OIDC + MFU allowlist, Central/Unit administration, login notification outbox, route templates, document snapshots, private evidence และ single reservation ดูขอบเขต/หลักฐานใน [P1 report](document/p1/Execution_Report.md) และ [checklist](document/Implementation_Checklist.md)

P2 เพิ่ม BPMN A/B ที่ execute ได้, outbox/inbox worker และ single-document workflow APIs เอกสารใหม่แสดง `PENDING` ระหว่าง sync แล้วเป็น `APPLIED` เมื่อ task พร้อม รายละเอียดอยู่ใน [P2 report](document/p2/Execution_Report.md) UI สำหรับ READY/pickup/drop-off/receipt/return ยังเป็น P3/P4; P2 ทดสอบ flow ผ่าน backend แบบ headless

Worker ตรวจ exact document/step/task/leg และ commit engine + inbox + ACK ใน transaction เดียวกัน ความล้มเหลวใช้ retry/backoff และ dead letter โดยบล็อกคำสั่งขั้นต่อไป มี Admin retry operation เดิมพร้อม audit ผ่าน `/api/v1/admin/workflow-operations/{operation_id}/retry` การจอง/ยกเลิก/หมดอายุการจองไม่ complete task

`WORKFLOW_WORKER_ENABLED` และ `WORKFLOW_TIMER_ENABLED` เปิดเป็นค่าเริ่มต้น Timer 24 ชั่วโมงเป็น development reminder baseline ที่ยังรอ SLA policy; บันทึก reminder โดยไม่ auto-accept หรือเปลี่ยน custody ส่วนการส่งแจ้งเตือน workflow ให้ผู้ใช้เป็นงาน operations ใน P6

## Local development

ต้องมี Docker Engine/Compose, Node 24 และ Java 21 (กรณีรันนอก container)

1. คัดลอก `deploy/.env.example` เป็น `deploy/.env` ตั้ง `DATABASE_PASSWORD` และ `APP_URL=http://localhost:8080`
2. หากจะ login ให้ตั้ง Google Web OAuth client/callback ที่ตรงกับ origin และ `BOOTSTRAP_ADMIN_EMAIL` เป็นบัญชี MFU ที่ได้รับอนุมัติ บัญชีนี้จะ provision แบบ idempotent โดยไม่มีรหัสผ่านแอป
3. รันจาก root:

```powershell
docker compose --env-file deploy/.env -f compose.yaml -f compose.dev.yaml up --build
```

เปิด `http://localhost:8080` และ Mailpit `http://localhost:8025` อีเมลใน dev อยู่ใน local sink ไม่มี demo login หรือ test provider ใน production artifact หากยังไม่มี Google client หน้า login จะยังเข้าใช้งานไม่ได้

Dev override ใช้ secure-cookie=false เฉพาะ localhost, engine schema update และ private local evidence volume Nginx ให้ frontend/API เป็น origin เดียวกัน ไม่เปิด DB หรือ backend สู่ host ค่า TTL 600 วินาที / MODEL_B เป็น development defaults ที่ยังต้องยืนยันใน P0

รันแบบ native: ตั้ง `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `APP_URL=http://localhost:3000`, `SPRING_PROFILES_ACTIVE=dev` และ Google/mail config ตามตัวอย่าง จาก `backend` ใช้ `./mvnw spring-boot:run`; จาก `frontend` ใช้ `npm ci` แล้ว `npm run dev` (proxy `/api` ไป backend localhost:8080)

## Verification

Backend tests ต้องใช้ฐาน PostgreSQL ทิ้งได้และสิทธิ์สร้าง schema ห้ามชี้ production แต่ละ JVM สร้าง `p1_test_<uuid>` schema โดยไม่มี H2 fallback

```powershell
$env:TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/doctracking_test'
$env:TEST_DB_USER='doctracking_test'
$env:TEST_DB_PASSWORD='<private test password>'
cd backend
.\mvnw.cmd --batch-mode verify
```

Frontend: `npm ci`, `npm run typecheck`, `npm test`, `npm run build` ภายใน `frontend` CI ทำ checks เดียวกันพร้อม PostgreSQL service

## Staging configuration

Base Compose ใช้ `prod` และปฏิเสธการเริ่มงานหาก policy/Google/MFU admin/HTTPS/secure session/SMTP TLS/private S3 ยังไม่ครบ ต้อง provision Flowable-owned schema ด้วย release procedure ก่อนรัน `FLOWABLE_SCHEMA_UPDATE=false`; application schema ใช้ Flyway แยกต่างหาก ห้ามใช้ dev schema update เป็น production migration strategy

วาง HTTPS reverse proxyหน้า frontend และตั้ง APP_URL เป็น public HTTPS origin Cookie ใช้ origin นี้โดยไม่เชื่อ forwarded headers จากผู้ใช้ ต้องทดสอบ Google, SMTP MFU inbox, private S3 permissions และ backup บน staging จริงตาม P8; Compose validation หรือ local tests ไม่ใช่ผล staging verification
