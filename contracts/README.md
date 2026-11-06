# P0 API contract

[`openapi.yaml`](openapi.yaml) เป็น OpenAPI 3.0.3 ของ application backend ใช้ตกลง payload, security boundary และ error ก่อนเริ่ม P1/P2 ไม่ใช่หลักฐานว่า API หรือ Google login ถูก implement แล้ว

- Prefix `/api/v1`, same-origin HTTPS, session cookie + CSRF; OAuth callback ใช้ state/nonce/PKCE แทน CSRF header
- Business/provisioning commands ใช้ idempotency; คำสั่งที่แก้เรื่องเดิมส่ง `expected_version` ต่อ document
- `202` หมายถึง business commit แล้วแต่ workflow ยัง sync ไม่เสร็จ; poll `/operations/{id}` และตรวจ `process_sync_status`
- Model/route snapshot ของเรื่องเดิมแก้ผ่าน API นี้ไม่ได้ การเปลี่ยน default มีผลเฉพาะเรื่องใหม่
- ลงทะเบียนด้วย `title` หรือ `document_number` อย่างน้อยหนึ่งค่าที่ไม่เป็นช่องว่าง; trim ก่อนบันทึกและเก็บค่าที่ไม่ได้ระบุเป็น null
- Route snapshot ใช้ `sequence` เริ่ม 0; `pending_receipt_step_id` แยกเป้าหมายรอยืนยันรับออกจาก `current_route_step_id`
- Batch conflict มี error รายเรื่องและไม่เงียบตัด subset; policy D08 ต้องได้รับการยืนยันก่อนเปิด bulk จริง
- TTL, direct-transfer photo, Unit/Station mapping, retention และ SLA ยังอิง decision log; ไม่มีค่าตั้งต้นใดใน contract ที่แทนการอนุมัติของเจ้าของระบบ

ดู [ข้อกำหนดหลัก](../document/Document_Tracking_Flowable_Development_Requirements.md), [Identity](../document/Authentication_and_Central_User_Requirements.md) และ [Checklist](../document/Implementation_Checklist.md) ประกอบ ใช้ route `step_id` แยกต้นทางและจุดรับคืนแม้อยู่ Station เดียวกัน

ขอบเขต P0-12 ครอบคลุม auth/provisioning/registry/reservation/check/receipt/ready/return/direct-transfer/observation/default-policy/login-email retry และ operation polling ส่วน upload/finalize/download evidence, station administration, courier-grant administration และ exception resolution ต้องออก contract เพิ่มพร้อม phase ที่พัฒนาฟีเจอร์นั้น โดย reference ของ evidence ที่รับในคำสั่งนี้ต้องผ่านการตรวจไฟล์และสิทธิ์แล้วเสมอ
