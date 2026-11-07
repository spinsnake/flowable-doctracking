# P6 deployment / configuration / migration

สถานะ: เตรียมขั้นตอนและเครื่องมือสำหรับ staging แล้ว; ยังไม่ได้ deploy Docker stack หรือบริการ Google/SMTP/S3/HTTPS จริงในรอบ P6 นี้ Docker daemon ในเครื่องทดสอบไม่พร้อมใช้งาน การตรวจ Compose และ tests ในเครื่องไม่ใช่ staging sign-off

## Release inventory และ secrets

บันทึก release ID, Git commit, backend/frontend `image@sha256`, Java/Spring Boot/Flowable/PostgreSQL versions, Flyway version/checksum, BPMN deployment/process-definition IDs, configuration revision และ operator ใน release record ที่ไม่เก็บ secrets ใช้ registry digest ที่ build/scan/ตรวจจริง ห้ามคัดลอก digest ตัวอย่างเป็นหลักฐาน release

`compose.yaml` มี build context สำหรับ development; production ใช้ immutable images ผ่าน override และ `--no-build`:

```powershell
python scripts/operations.py release --backend-image '<registry/backend@sha256:actual-digest>' --frontend-image '<registry/frontend@sha256:actual-digest>' --output .local/release-images.yaml
docker compose --env-file deploy/.env -f compose.yaml -f .local/release-images.yaml config --quiet
docker compose --env-file deploy/.env -f compose.yaml -f .local/release-images.yaml pull backend frontend
```

คำสั่ง `release` ตรวจรูปแบบ digest และสร้าง override เท่านั้น ไม่ pull/start service เอง ต้องตรวจ registry และบันทึก `docker image inspect` digest ให้ตรง release record ก่อนใช้จริง Pin PostgreSQL image digest ใน environment override ของ staging/production ด้วย; Compose base ระบุ version tag เท่านั้น

ใช้ secret manager หรือ private deployment environment ที่จำกัด OS permissions เพื่อ inject `DATABASE_PASSWORD`, Google secret, SMTP credentials และ AWS credentials ห้าม commit populated `.env`, พิมพ์ `docker compose config` แบบเต็ม, `docker inspect` environment หรือแนบ raw connection string ลงรายงาน Prod S3 ใช้ workload identity/credential provider ที่รองรับก่อนเลือก static keys และใช้ dedicated private bucket ที่ service มีสิทธิ์เฉพาะงาน

ตรวจ `deploy/.env.example`: APP_URL เป็น HTTPS origin, callback คือ `APP_URL/api/v1/auth/google/callback`, Google hosted domain `mfu.ac.th`, secure cookie, MFU allowlist/bootstrap admin ที่อนุมัติ, SMTP TLS/sender/IT contact, private bucket/region/endpoint และ DB network แยกจาก public internet `APP_POLICY_CONFIRMED=true` ใช้หลังผู้รับผิดชอบยืนยัน policy จริงเท่านั้น G0 ที่ยังเปิดห้ามตีความว่าอนุมัติแล้ว

## Maintenance และ schema

1. ปิด ingress ของผู้ใช้ด้วย maintenance response; หยุด backend **ทุก replica** รวม workflow worker, Flowable timers, mail worker, reconciliation และ cleanup หยุด external integration/DB writer/object writer ทุกตัวด้วย ปิดการเขียน bucket หรือ revoke writer credentials จาก process ที่หยุดแล้วตามขั้นตอนของผู้ดูแล การปิด ingress อย่างเดียวไม่หยุด background writes
2. ตรวจว่าไม่มี active application DB clients และ object writes แล้วสร้างและตรวจ backup bundle ตาม [Backup / Restore](Backup_Restore_Runbook.md) ตลอดช่วง dump+copy ห้ามเปิด writer ใหม่
3. แยก **application schema** กับ **Flowable schema** เป็นคนละรายการใน release record App tables ใช้ Flyway `V1...Vn` และตรวจ `flyway_schema_history`/checksums ก่อนรัน ห้ามแก้ migration ที่ apply แล้ว ห้าม `repair`, `baseline` หรือ DROP tables เพื่อข้าม checksum error
4. Engine schema ของ Flowable ใช้ SQL/tooling ที่มาจาก **release artifact เดียวกับ Flowable ที่ pin อยู่** ให้ DBA ตรวจลำดับ upgrade ของ engine/common/history และ schema version จริงจาก `ACT_GE_PROPERTY` ก่อน execute ภายใต้ maintenance ทดสอบบน restored staging copy ก่อนเสมอ ไม่มี arbitrary version-to-version SQL script ที่ได้รับอนุมัติรวมไว้ใน repo นี้ ห้ามเปิด `FLOWABLE_SCHEMA_UPDATE=true` ใน production เพื่อให้แอปเดาลำดับ upgrade
5. App Flyway ทำงานเมื่อ backend startup ดังนั้นใช้ deployment account และเริ่ม backend เพียงหนึ่ง instance ใน private maintenance network โดย **ยังไม่เปิด ingress** และ `FLOWABLE_SCHEMA_UPDATE=false`, `WORKFLOW_WORKER_ENABLED=false`, `WORKFLOW_TIMER_ENABLED=false`, `MAIL_WORKER_ENABLED=false`, `RECONCILIATION_ENABLED=false`, `ORPHAN_CLEANUP_ENABLED=false` ให้ root configuration ชื่อจริงใน `.env.example` เป็นหลัก ทดสอบ startup/health และตรวจ Flyway schema version จาก DB หาก engine upgrade ยังไม่พร้อมให้หยุดก่อนขั้นนี้
6. ตรวจ migration/health/definition compatibility ใน staging; เมื่อผ่านแล้ว recreate backend ด้วย worker/timer/mail settings ที่ได้รับอนุมัติ ห้ามเปิด cleanup จนกว่าจะมี orphan grace policy หรือเปิด timer/alert โดยตีความเวลา development เป็น SLA ที่อนุมัติแล้ว

```powershell
docker compose --env-file deploy/.env -f compose.yaml -f .local/release-images.yaml up -d --no-build backend frontend
python scripts/operations.py smoke --origin https://your-staging-origin
```

ปรับ configuration maintenance ให้เป็น private revision แยกจากค่าปกติ การแก้ environment ของ container ที่กำลังรันไม่มีผลจนกว่าจะ recreate และ `docker compose stop backend` หยุดได้เฉพาะ project/host ปัจจุบัน ต้องจัดการ replica/host อื่นด้วย

## BPMN และ active instances

Release ต้องเก็บ delegate/service contracts และ task-local/variable formats ที่ process version เก่าใช้อยู่ ห้ามลบ implementation ของ delegate ที่ instance เก่ายังอ้างถึง Deploy definition ใหม่เพื่อเอกสารใหม่โดยตรวจ mapping `workflow_instances.process_definition_id` ของ instance เก่าก่อน/หลัง ไม่เปลี่ยน process definition ของ active instance โดยอัตโนมัติ ไม่เปลี่ยน immutable tracking mode/route/policy snapshot และไม่ใช้ model switch เพื่อแก้ engine mismatch

ถ้า runtime มีสอง definition versions ให้ smoke instance เก่าต่อจาก task เดิมและเริ่มเอกสารใหม่กับ definition ใหม่ ตรวจ exact task/step/leg correlation ของแต่ละ version แล้วจึงเปิด ingress ผลทดสอบ compatibility ในเครื่องไม่แทน staging test ของ image/schema จริง

## Staging smoke sign-off

`operations.py smoke` เรียกเฉพาะ anonymous `/healthz`, CSRF และ `/auth/me` คาด 200/401/401 (CSRF token เปิดเฉพาะ session ที่ login แล้ว) ไม่ follow OAuth redirect และไม่ login จึงไม่ส่งอีเมล Login/OIDC/SMTP/evidence integration ต้องทดสอบแยกด้วยบัญชีและ inbox ที่ได้รับอนุญาต:

- TLS/callback/certificate, request logs ไม่มี query/token/cookie/email, Secure/HttpOnly/SameSite cookie, CSRF deny, revoked session และ non-admin deny จาก API ตรง
- Login จริงด้วย allowlisted `mfu.ac.th`, ตรวจว่า notification มีหนึ่ง job ต่อ login event พร้อมผล provider; SMTP accepted ไม่ใช่การยืนยัน inbox delivery และ uncertain ACK อาจส่งซ้ำ
- Private S3 finalized upload/download ตาม scope และ deny unauthorized/direct public access; ไม่ใช้รูปหรือข้อมูลจริงที่ไม่ได้รับอนุญาต
- Model A/B และ bulk conflict, operation polling/retry, reconciliation findings และการคง custody/exception เดิม โดยไม่เปลี่ยน ledger เพื่อให้ dashboard ผ่าน
- ตรวจ monitoring/alert destination ที่เจ้าของบริการอนุมัติ และ restore drill/RPO/RTO ที่บันทึกจริง

บันทึก operator เวลา image digests configuration revision schema versions และผล smoke รายข้อใน release record หากยังไม่มี staging target ให้รายงานว่า **ยังไม่ได้ทดสอบ staging** และคง P6/P8 gate ที่เกี่ยวข้องไว้
