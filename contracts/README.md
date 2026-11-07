# Application API contract — P0/P1/P2/P3/P4

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

P3 เชื่อม UI ของ single-document business workflow; P4 เพิ่ม Model A UI, observation และ default-policy API ส่วน direct transfer, bulk และ exception resolution ยังเป็น P5 P2 รับหนึ่ง item ต่อคำสั่ง batch-shaped payload; P1 tests เดิมที่ seed ready/APPLIED เป็น fixtures แยกจาก P2 tests ที่ execute Flowable จริง

UI ไม่แก้ tracking mode ของเรื่องเดิม การใช้รูปในคำสั่งต้องผ่าน finalize และสิทธิ์ owner/context ก่อน; เก็บรูปแบบ private โดยดาวน์โหลดผ่าน backend ที่ตรวจสิทธิ์ทุกครั้ง


P3 adds authenticated read models for the single-document UI:

- `GET /stations/{id}/unit-worklist` requires active membership of that station's unit and an explicit station grant. MODEL_B rows identify the exact route occurrence in `action_route_step_id`; `action` is `CONFIRM_RECEIPT`, `READY`, `CONFIRM_RETURN` or null. Pending/failed synchronization stays visible with no mutation action. MODEL_A has no unit receipt/ready queue.
- `GET /courier/legs` returns only the current courier's active legs with an active assigned destination station/unit. It exposes exact from/to step and station IDs, current document version and synchronization, without title/number or full document details.
- `GET /courier/reservations` remains owner-only and now checks current capability/station/unit access. Each item carries current `document_version`/`expected_version` for pickup and historical `reserved_version`; the top-level reservation `version` still guards cancellation.
- The origin-authorized timeline includes `actor_display_name` for display and retains immutable `actor_id` for audit. Display names may change; system events have no invented actor.

The pending G0 field-visibility policy remains fail closed: non-origin unit worklists redact title/number, and full detail, timeline and attached evidence remain origin-scoped. Membership in an inactive origin unit does not reveal those fields. Query actions are UI hints; mutation endpoints independently enforce authorization, state, exact occurrence, version and evidence.


P4 adds Model A observation and default-mode management:

- `GET/PUT /admin/workflow-policy` requires current Central Admin access. PUT requires CSRF, idempotency, `expected_version`, a different `default_tracking_mode` and a nonblank reason. It returns `{default_tracking_mode, version, changed_at, changed_by, change_reason}`; actor/reason are null for the seeded initial policy. Audit action `WORKFLOW_POLICY_CHANGED` stores a JSON envelope in the existing audit `reason` column containing the human reason and old/new modes/versions. Audit row actor/time remain server controlled.
- Registration already takes `FOR SHARE` on the singleton policy inside the same transaction as the document, immutable route snapshot, event and outbox. Policy change takes `FOR UPDATE`. A concurrent registration therefore copies one consistent committed mode/version; existing document mode, policy/route snapshots and process instance/definition/task IDs remain unchanged. No active-instance mutation or registration mode override is exposed.
- `GET /courier/observation-context?document_id=&station_id=` returns only matching occurrences, document version/state/sync and `can_observe` for a known Model A document at an active assigned station. It accepts off-route stations but reveals no title/number, origin identity, full route or other users. Model B returns 404. Courier capability alone does not bypass explicit station scope.
- `POST /documents/{id}/observations` requires `expected_version`, `observed_station_id`, finalized station-context photos, `reason` and `physical_presence_confirmed: true`. Select `proposed_route_step_id` explicitly whenever the station occurs on the route; omit it only for an off-route station. An observation at the current occurrence adds `OBSERVED_AT` without advancing a task. Another occurrence/off-route adds `OBSERVED_AT`, `UNOBSERVED_TRANSFER` and `EXCEPTION_OPENED`, sets `EXCEPTION`, and holds current/reported route positions and the waiting workflow task. Unknown carrier/time remain null. Photos prove the observation, not the missing transfer.
- X07 reconciliation approval remains open. P4 never automatically skips/reconciles a route or completes a document based on observation; authorized exception resolution is P5. Pending sync, active custody/reservation/direct transfer, terminal states and existing exceptions block observation. No synthetic unit receipt/ready/return event is created in Model A.


P5 bulk follows user-confirmed D08 (2026-11-07):

- Reservation, pickup, drop-off and intermediate unit receipt accept 1–100 unique documents. A multi-item command commits valid items and reports rejected items by document ID/code/message. Global station authorization and malformed payloads reject the request before work. Singleton errors preserve existing status codes.
- Each item has a JDBC savepoint inside the command transaction. Business conflicts roll back only that item; an unexpected system/transaction failure still rolls back the command. Document locks follow UUID string order. Stable idempotency replay returns the original outcome, including all-rejected results.
- `accepted`, `document_ids`, `event_ids`, and `updated_states` describe successful items only; `rejected` contains no title, owner identity or private evidence. `batch_id` aliases `operation_id`. Zero accepted multi-item results return 200 and no empty reservation. Partial reserve returns 201; accepted workflow commands return 202 until every accepted document is applied.
- Pickup may consume an explicitly selected subset from one owned reservation. Remaining members retain their reservation and TTL. Cancellation/expiry handles every remaining active member and never releases actual courier custody. Reservation `items` lists active members; cancelled/expired responses use `accepted` to identify released documents.
- Pickup shares one source station; drop-off shares one destination and verifies each leg owner. Finalized station-context evidence can be shared through a separate immutable event/evidence link per accepted document. A document-context photo cannot authorize another document. The UI requires every added photo to be ready or explicitly removed before submitting.
- `GET /operations/{id}` derives aggregate status from all deliveries belonging to that operation: any failed delivery means FAILED_RETRYABLE; otherwise any pending means PENDING; APPLIED requires all applied. Per-document states reflect their own delivery. Ownership or origin visibility for every accepted document is required; outcomes with rejected/unknown selections are submitter-only.
- Technical recovery lists individual deliveries. Multi-delivery retry requires `delivery_id` and that delivery's `expected_version`; it requeues that existing delivery with audit, retaining the original business operation/events. A retry never re-executes successful business items.
- Direct-transfer D06 and exception resolution X07/X08 remain pending separate confirmation; bulk approval does not approve those policies.
