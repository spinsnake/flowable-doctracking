# Application API contract — P0/P1/P2/P3

[`openapi.yaml`](openapi.yaml) เป็น OpenAPI 3.0.3 ของ application backend ใช้ตกลง payload, security boundary และ error หลักฐาน implementation อยู่ใน [P1 report](../document/p1/Execution_Report.md) การมี endpoint ใน contract ไม่ถือว่า endpoint นั้นพัฒนาแล้ว

- Prefix `/api/v1`, same-origin HTTPS, session cookie + CSRF; OAuth callback ใช้ state/nonce/PKCE แทน CSRF header
- Business/provisioning commands ใช้ idempotency; คำสั่งที่แก้เรื่องเดิมส่ง `expected_version` ต่อ document
- `202` หมายถึง business commit แล้วแต่ workflow ยัง sync ไม่เสร็จ; poll `/operations/{id}` และตรวจ `process_sync_status`
- Model/route snapshot ของเรื่องเดิมแก้ผ่าน API นี้ไม่ได้ การเปลี่ยน default มีผลเฉพาะเรื่องใหม่
- ลงทะเบียนด้วย `title` หรือ `document_number` อย่างน้อยหนึ่งค่าที่ไม่เป็นช่องว่าง; trim ก่อนบันทึกและเก็บค่าที่ไม่ได้ระบุเป็น null
- Route snapshot ใช้ `sequence` เริ่ม 0; `pending_receipt_step_id` แยกเป้าหมายรอยืนยันรับออกจาก `current_route_step_id`
- Batch conflict มี error รายเรื่องและไม่เงียบตัด subset; policy D08 ต้องได้รับการยืนยันก่อนเปิด bulk จริง
- TTL, direct-transfer photo, Unit/Station mapping, retention และ SLA ยังอิง decision log; ไม่มีค่าตั้งต้นใดใน contract ที่แทนการอนุมัติของเจ้าของระบบ

ดู [ข้อกำหนดหลัก](../document/Document_Tracking_Flowable_Development_Requirements.md), [Identity](../document/Authentication_and_Central_User_Requirements.md) และ [Checklist](../document/Implementation_Checklist.md) ประกอบ ใช้ route `step_id` แยกต้นทางและจุดรับคืนแม้อยู่ Station เดียวกัน

P1 เพิ่ม contract ของ station directory/create, explicit user grants, template revision, own-reservation list และ evidence create/raw PUT/finalize/authorized download/attach-to-document พร้อม implementation ของ auth/provisioning/registry/single reservation/notification retry/operation polling

P2 เพิ่ม single-document ready/check-receive/check-dropoff/confirm-receipt/confirm-return พร้อม BPMN A/B และ outbox/inbox worker จริง คำสั่งตอบ 202 แล้ว poll operation จน APPLIED; ระหว่าง pending/failed ห้ามเดินขั้นถัดไป การจอง/ยกเลิก/หมดอายุการจองยังไม่ complete engine task ดู [P2 report](../document/p2/Execution_Report.md)

`GET /admin/workflow-operations/{operation_id}` ให้ Central Admin ดูเฉพาะ technical status/version และ `POST .../retry` requeue delivery เดิมพร้อม CSRF/idempotency/version/reason/audit ไม่สร้าง business event ใหม่ Operation polling รายงาน sync ของ operation นั้น ไม่ใช้สถานะของคำสั่งถัดไปทับคำสั่งที่ apply แล้ว

P3 เชื่อม UI ของ single-document business workflow ส่วน direct transfer, observation, default-policy API และ bulk ยังเป็น P4/P5 ยังไม่เปิดใช้งาน P2 รับหนึ่ง item ต่อคำสั่ง batch-shaped payload; P1 tests เดิมที่ seed ready/APPLIED เป็น fixtures แยกจาก P2 tests ที่ execute Flowable จริง

UI ไม่แก้ tracking mode ของเรื่องเดิม การใช้รูปในคำสั่งต้องผ่าน finalize และสิทธิ์ owner/context ก่อน; เก็บรูปแบบ private โดยดาวน์โหลดผ่าน backend ที่ตรวจสิทธิ์ทุกครั้ง


P3 adds authenticated read models for the single-document UI:

- `GET /stations/{id}/unit-worklist` requires active membership of that station's unit and an explicit station grant. MODEL_B rows identify the exact route occurrence in `action_route_step_id`; `action` is `CONFIRM_RECEIPT`, `READY`, `CONFIRM_RETURN` or null. Pending/failed synchronization stays visible with no mutation action. MODEL_A has no unit receipt/ready queue.
- `GET /courier/legs` returns only the current courier's active legs with an active assigned destination station/unit. It exposes exact from/to step and station IDs, current document version and synchronization, without title/number or full document details.
- `GET /courier/reservations` remains owner-only and now checks current capability/station/unit access. Each item carries current `document_version`/`expected_version` for pickup and historical `reserved_version`; the top-level reservation `version` still guards cancellation.
- The origin-authorized timeline includes `actor_display_name` for display and retains immutable `actor_id` for audit. Display names may change; system events have no invented actor.

The pending G0 field-visibility policy remains fail closed: non-origin unit worklists redact title/number, and full detail, timeline and attached evidence remain origin-scoped. Membership in an inactive origin unit does not reveal those fields. Query actions are UI hints; mutation endpoints independently enforce authorization, state, exact occurrence, version and evidence.
