# P0 — Design Baseline

วันที่: 2026-11-06 (Asia/Bangkok)  
สถานะ: แบบสำหรับเริ่ม implementation และ review; ตารางนี้ยังไม่ใช่ BPMN/API ที่ execute แล้ว

อ่านร่วมกับ [Decision Log](Decision_Log.md), [ข้อกำหนดหลัก](../Document_Tracking_Flowable_Development_Requirements.md), [Authentication](../Authentication_and_Central_User_Requirements.md) และ [Checklist](../Implementation_Checklist.md) ข้อที่ยังเป็นข้อเสนออ้าง Dxx/Xxx และต้องยืนยันก่อนพัฒนาส่วนที่ขึ้นกับคำตอบ การมีตารางครบไม่เท่ากับผ่าน gate ที่ต้องใช้ผล runtime

## 1. เจ้าของสถานะและขอบเขต

Backend ตรวจสิทธิ์/transition และบันทึกข้อมูลธุรกิจลง App DB พร้อม event/outbox; Worker ส่ง event ผ่าน Embedded Flowable Java API ให้ engine เปิด task ถัดไป ไม่ใช้ Flowable เฝ้าตารางทะเบียนและไม่ให้ browser complete engine task โดยตรง

| ส่วน | ข้อมูลที่เป็นเจ้าของ |
|---|---|
| App DB | users/units/scopes, document/route snapshot/mode/policy, reservation/leg/direct transfer, evidence metadata, append-only events, sync projection/outbox/inbox |
| Flowable | process definition/instance/task/timer state; อ่านและสั่งผ่าน engine services เท่านั้น |
| Private object storage | ไฟล์ภาพจริง; checksum/reference และ authorization อยู่ที่แอป |
| Notification worker | login email jobs/attempt/lease; แยกการประมวลผลและ retry จาก workflow |

Modular monolith เดียวและ PostgreSQL datasource/transaction manager ร่วมสำหรับ application + engine เป็นเป้าหมาย P0 spike; หากใช้ร่วมจริงไม่ได้ต้องแก้ boundary ก่อนถือว่า architecture ผ่าน ไม่เพิ่ม broker/microservice เพื่อเลี่ยงการพิสูจน์นี้

## 2. Route/ตำแหน่ง/การ advance

ใช้ snapshot ที่แก้ตาม template ภายหลังไม่ได้: `S0=A(origin) → S1=B → S2=C → S3=A(return)` ทุก event/command/task ใช้ `document_id + step_id`; `station_id=A` อย่างเดียวระบุรอบไม่ได้

| Field | ความหมาย |
|---|---|
| `current_route_step_id` | B: step ล่าสุดที่หน่วยงานยืนยันครอบครอง; A: route progress ตาม report/observation ไม่ใช่ unit acceptance |
| `reported_location_step_id` | step ล่าสุดที่มีรายงานส่งถึง/พบจริง; ไม่หมายถึงตำแหน่งเรียลไทม์เมื่อ `IN_TRANSIT` และไม่ใช่ unit receipt |
| `from_step_id`, `to_step_id` | ต้นทาง/ปลายทางของ leg หรือ direct dispatch; ตรึงตอนเริ่ม ไม่เดาจาก current step หลัง advance |
| `pending_receipt_step_id` | B: ปลายทางที่ต้องยืนยันรับหลัง courier/direct dispatch; ใช้เลือก task โดยไม่เลื่อน current ก่อนหลักฐาน |
| `active_leg_id`, `active_reservation_id` | ความรับผิดชอบ/สิทธิ์จองชั่วคราว; reservation ไม่ใช่ custody |

ตัวอย่าง B: pickup S0→S1 ไม่เลื่อน current; drop-off ตั้ง reported=S1/pending=S1 และปิด leg แต่ current=S0; B receipt ตั้ง current=S1/ล้าง pending เพียงครั้งเดียว แล้วจึง processing/ready ต่อ ส่วน direct dispatch ไม่อ้างว่าถึงปลายทางจน receipt; reported คงหลักฐานล่าสุดและสถานะแสดง direct-in-transit

ตัวอย่าง A: drop-off S0→S1 ตั้ง reported=current=S1 แต่ UI แสดง “ผู้เดินหนังสือรายงานส่งถึง ยังไม่มีการยืนยันของหน่วยงาน”; pickup ต่อได้เมื่อพบจริงและ Check+รูป ห้ามสร้าง `UNIT_RECEIPT_CONFIRMED`/`UNIT_READY` แทนหน่วยงาน

ที่ S3: A drop-off จบ `COURIER_TRACKING_COMPLETE`; B courier/direct delivery ต้องให้ origin member ยืนยันรับที่ S3 ก่อน `COMPLETED` ห้ามเทียบแค่ Station A กับต้นทางแล้วจบทันที

Model A พบที่ step อื่น: บันทึก `OBSERVED_AT` และช่องว่าง `UNOBSERVED_TRANSFER` โดยไม่เดา courier/ผู้ส่ง/เวลา/รูปของช่วงที่ไม่มีหลักฐาน ต้องระบุ exact step และปฏิเสธคำขอกำกวมใน repeated-station route; X07 ยังรอผู้อนุมัติวิธี reconcile ข้ามหลายจุด การพบที่ return step อย่างเดียวไม่สร้าง final courier drop-off หรือ unit receipt ย้อนหลัง ให้ Coordinator ตรวจและใช้ resolution ที่ได้รับอนุมัติก่อนเปลี่ยนเป็น terminal

## 3. Transition matrix

ชื่อ event/task เป็น contract baseline สำหรับ P1/P2; BPMN XML จริงต้องใช้ชื่อตรงกับ API และทดสอบ task correlation ตารางแสดงผลธุรกิจหลัง commit กับ **next task หลัง workflow sync สำเร็จ** เป็นคนละจังหวะ

ใช้ domain-event enums ตาม Requirements §11.2 ได้แก่ `DOCUMENT_REGISTERED`, `UNIT_READY`, `COURIER_PICKED_UP`, `COURIER_DROPPED_OFF`, `UNIT_RECEIPT_CONFIRMED`, `DIRECT_UNIT_DISPATCHED`, `ORIGIN_RETURN_CONFIRMED`, `OBSERVED_AT`, `EXCEPTION_OPENED`, `EXCEPTION_RESOLVED` และบันทึก `UNOBSERVED_TRANSFER` เพิ่มตาม T10 โดย task key เช่น `COURIER_PICKUP` เป็นคนละ namespace กับ event ส่วน `PICKUP_RESERVED`/reservation cancelled/expired เป็น local reservation events ที่ไม่ทำให้ engine advance

Guards สำหรับ authenticated mutations: user/unit/scopes active, CSRF, version/idempotency key+request hash และ resource/document lock ตาม command contract; งานต่อเรื่องใช้ exact route step คำสั่ง workflow ปกติห้ามทำกับ terminal/exception และต้อง `process_sync_status=APPLIED` ก่อนเดินต่อ; register/exception resolution/recovery ใช้ guards เฉพาะแถวของตน ไม่บังคับว่าต้องมี process ก่อน register หลักฐานรูปต้อง validated และเป็นของ operation/scope ที่มีสิทธิ์ Actor/time มาจาก session/server

### 3.1 Register / จอง / Courier

| Mode / action | จาก state → state หลัง commit | Actor + guard เพิ่มเติม | Event / route/custody effect | Next task หลัง sync |
|---|---|---|---|---|
| A register | — → `AWAIT_PHYSICAL_PICKUP` | Origin permission; เลขหนังสือหรือชื่อเรื่อง; snapshot/mode/policy ตรึง | `DOCUMENT_REGISTERED`; current/reported=S0; start หนึ่ง instance | `COURIER_PICKUP(S0,S1)` |
| B register | — → `WAIT_ORIGIN_READY` | Origin permission; เหมือน A | `DOCUMENT_REGISTERED`; current/reported=S0 | `ORIGIN_READY(S0)` |
| B origin ready | `WAIT_ORIGIN_READY` → `READY_FOR_PICKUP` | Origin ที่ S0; มีเอกสารพร้อมส่งจริง | `UNIT_READY(S0)`; ไม่เลื่อน step | `COURIER_PICKUP(S0,S1)` |
| A reserve | `AWAIT_PHYSICAL_PICKUP` / `DROPPED_OFF_UNCONFIRMED` → `PICKUP_RESERVED` | Courier station scope ของ from step; ไม่มี leg/direct/reservation; ต้องพบจริงเมื่อ pickup | `PICKUP_RESERVED`; เก็บ previous state/owner/expiry ไม่เปลี่ยน custody | คง `COURIER_PICKUP` |
| B reserve | `READY_FOR_PICKUP` → `PICKUP_RESERVED` | Courier scope; มี READY ของ step; ไม่มี leg/direct/reservation | `PICKUP_RESERVED`; ไม่เลื่อน step | คง `COURIER_PICKUP` |
| A/B cancel reserve | `PICKUP_RESERVED` → state ก่อน reserve | owner ของ reservation; non-owner เช่น ADMIN/Coordinator ต้องมี explicit reservation-resolution grant + resource scope และ reason ตาม API ไม่ได้สิทธิ์จากชื่อ role เพียงอย่างเดียว | `PICKUP_RESERVATION_CANCELLED`; ไม่สร้าง pickup | คง `COURIER_PICKUP` |
| A/B expire reserve | `PICKUP_RESERVED` → state ก่อน reserve | System worker; server time ≥ expires_at; row lock เดียวกับ pickup | `PICKUP_RESERVATION_EXPIRED`; TTL รอ D07; ไม่ expire `IN_TRANSIT` | คง `COURIER_PICKUP` |
| A/B pickup | `PICKUP_RESERVED` → `IN_TRANSIT` | reservation owner/ยังไม่หมดอายุ; รูป; A ยืนยันพบจริง; B ยัง eligible ตาม READY | `COURIER_PICKED_UP`; consume reservation/เปิด leg from→to; current ไม่เลื่อน | `COURIER_DROPOFF(leg,to)` |
| A drop-off กลางทาง | `IN_TRANSIT` → `DROPPED_OFF_UNCONFIRMED` | leg owner; to step ตรง; รูป | `COURIER_DROPPED_OFF`; ปิด leg; current/reported=to | `COURIER_PICKUP(to,next)` |
| B drop-off กลางทาง | `IN_TRANSIT` → `PENDING_UNIT_RECEIPT` | leg owner; to step ตรง; รูป | `COURIER_DROPPED_OFF`; ปิด leg; reported/pending=to; current คง from | `UNIT_RECEIVE(to)` |
| A drop-off return | `IN_TRANSIT` → `COURIER_TRACKING_COMPLETE` | leg owner; to=final return step; รูป | `COURIER_DROPPED_OFF`; ปิด leg; current/reported=return; ไม่มี receipt สมมติ | End A |
| B drop-off return | `IN_TRANSIT` → `PENDING_ORIGIN_RETURN_RECEIPT` | leg owner; to=final return step; รูป | `COURIER_DROPPED_OFF`; ปิด leg; reported/pending=return; current คง from | `ORIGIN_RETURN_RECEIVE(return)` |

Reservation/cancel/expiry เป็น local business state และ audit; **ไม่ complete Flowable task** และไม่ต้องเพิ่ม engine task ต่อการจอง Outbox ที่ส่ง engine ต้องมี destination/action ชัด มิฉะนั้น reserve event อาจถูกเข้าใจว่า pickup จน workflow เดินผิด

### 3.2 หน่วยงาน / Direct / Observation / Exception

| Mode / action | จาก state → state หลัง commit | Actor + guard เพิ่มเติม | Event / route effect | Next task หลัง sync |
|---|---|---|---|---|
| B receipt | `PENDING_UNIT_RECEIPT` → `UNIT_PROCESSING` | Staff/Unit Admin มี station RECEIVE ที่ pending step; ยืนยันได้รับจริง | `UNIT_RECEIPT_CONFIRMED`; current=to; ล้าง pending; รูปปกติ optional | `UNIT_PROCESS_AND_READY(to)` |
| B ready | `UNIT_PROCESSING` → `READY_FOR_PICKUP` | staff มี READY ที่ current step; มี receipt ของ step แล้ว | `UNIT_READY`; route ไม่เลื่อน; ปุ่มนี้คือจบงานหน่วยงาน/พร้อมส่ง ไม่เพิ่ม complete-process อีกปุ่ม | `COURIER_PICKUP(current,next)` |
| B direct dispatch | `UNIT_PROCESSING` → `DIRECT_UNIT_IN_TRANSIT` | staff มี DIRECT_DISPATCH; ไม่มี active reservation/leg/direct; รูปตาม D06 | `DIRECT_UNIT_DISPATCHED`; สร้าง direct transfer from→to; current/reported คงหลักฐานเดิม; pending=to | `UNIT_RECEIVE(to)` หรือ `ORIGIN_RETURN_RECEIVE(to)` |
| B direct receipt กลางทาง | `DIRECT_UNIT_IN_TRANSIT` → `UNIT_PROCESSING` | staff ที่ปลายทางมี RECEIVE; direct ID/to step ตรง; ยืนยันได้รับจริง | `UNIT_RECEIPT_CONFIRMED`; ปิด direct; current/reported=to; ล้าง pending | `UNIT_PROCESS_AND_READY(to)` |
| B origin return receipt | `PENDING_ORIGIN_RETURN_RECEIPT` / direct ที่ final return → `COMPLETED` | origin member มี RECEIVE ที่ final return step; received จริง; ไม่ใช่ S0 | `ORIGIN_RETURN_CONFIRMED`; current/reported=return; ปิด pending/direct | End B |
| A observe ที่ step เดิม | ไม่เปลี่ยน state ที่รอ pickup | Courier scope และหลักฐานพบจริงตาม X07; ไม่ใช่ active custody ของคนอื่น | `OBSERVED_AT`; ไม่สร้างหน่วยงานรับ/ready | คง `COURIER_PICKUP` |
| A observe ข้าม step | state ที่รอ pickup → `EXCEPTION` รอ reconcile | exact observed step + reason/evidence; ไม่มี active leg/reservation; policy X07 | `OBSERVED_AT` + `UNOBSERVED_TRANSFER` + case; ยังไม่ complete task ปกติ | hold task; Coordinator review |
| A/B report issue | nonterminal → `EXCEPTION` | actor มีสิทธิ์กับ document/leg/step; type=not-found/partial-receipt/wrong-station/dispute | `EXCEPTION_OPENED`; เก็บ previous state/task/custody; ไม่ advance/ไม่สร้าง receipt | hold task; `EXCEPTION_REVIEW` เป็น operations case |
| A/B resolve issue | `EXCEPTION` → target ที่อนุมัติเฉพาะ case | Coordinator มี resolution scope; reason/evidence/resume action/expected task/version; X08 | `EXCEPTION_RESOLVED` + correction ที่ระบุจริง; ไม่ลบ ledger/ไม่ปลอม Check | resume task ตาม resolution map ที่ทดสอบแล้ว |

Direct dispatch จาก `READY_FOR_PICKUP` ยังไม่เปิดโดยปริยาย: ต้องมี flow ถอน READY/ยกเลิกจองภายใต้ lock เดียวก่อน หรือยืนยัน extension ใน D10/X08 คำว่า partial receipt หมายถึงรายงานปัญหาจริง ไม่ใช่สิทธิ์ silently complete บางรายการจาก bulk request; D08 ต้องกำหนด atomicity แยกกัน

Exception ไม่ล้าง active leg โดยอัตโนมัติ ผู้ที่ถือเอกสารจริงยังมี custody จน resolution/handoff ที่ได้รับอนุมัติครบ; กรณี not-found ก่อน pickup อาจจบ reservation ด้วยเหตุผลจริง แต่ไม่สร้าง courier leg ค้างขึ้นมา

## 4. Permission matrix

Management role แยกจาก business grants; การเป็น ADMIN/UNIT_ADMIN ไม่สร้าง actor รับส่งแทนผู้อื่น Origin/Station เป็น **context ของเรื่องและ step** ไม่ใช่ Google role และ Coordinator เป็น business grant ที่ต้อง provision อย่างชัดเจน

`G` = ต้องมี business grant + resource scope, `ตน` = บัญชี/Unit/leg ของตน, `—` = role นั้นไม่ได้สิทธิ์นี้โดยลำพัง

| Action | ADMIN | UNIT_ADMIN | STAFF | COURIER | Coordinator |
|---|---|---|---|---|---|
| เพิ่ม/แก้/ปิด Unit, แต่งตั้ง UNIT_ADMIN | ได้ตาม guards | — | — | — | — |
| เพิ่ม/แก้/ปิด STAFF | ได้ตาม guards | เฉพาะ Unit ตน | — | — | — |
| แต่งตั้ง ADMIN | ไม่ผ่าน ordinary users API; ใช้ bootstrap/IT flow ที่มี audit | — | — | — | — |
| กำหนด Courier/Coordinator/station scopes | ได้เมื่อมีสิทธิ์จัดการ grants; audit | — | — | — | — |
| Origin register/template/ready/return | G + origin Unit membership | G + Unit ตน | G + Unit ตน | — | G + origin Unit membership |
| หนังสือของหน่วยงาน | G + origin scope | origin Unit ตน | origin Unit ตน | — | G ตามขอบเขต |
| Station worklist/receipt/ready/direct B | G + exact station/action | G + stations ของ Unit | G + stations ของ Unit | — | G; ห้าม complete แทนผู้รับจริง |
| Eligible pickup worklist | — | — | — | assigned pickup stations; B READY เท่านั้น | G แบบ read-only |
| Reserve/pickup/drop-off | — | — | — | reserve/pickup ตาม station scope; drop-off leg owner | —; ต้องมี Courier grant แยก |
| ดู detail/timeline | G | เฉพาะ origin/งานที่เกี่ยวข้อง | เฉพาะ origin/งานที่เกี่ยวข้อง | เอกสาร eligible ตาม fields ขั้นต่ำ/leg ที่รับผิดชอบ | case/scope ที่ได้รับ |
| ดูรูป/ดาวน์โหลดหลักฐาน | G + sensitivity policy | G + document/event scope | G + document/event scope | evidence ของงานที่มีสิทธิ์; ไม่ใช่ bucket ทั้งหมด | G + case/evidence scope |
| เปลี่ยน default mode | ได้ตาม policy-management grant/reason | — | — | — | เฉพาะหากได้ grant นี้เพิ่ม |
| Resolve exception/route correction | เฉพาะ resolution grant | — | — | report issue ในงานตน | resolution grant + case scope |
| Workflow sync retry/replay | operational grant + audit | — | — | — | เฉพาะหากได้ sync-ops grant |
| ตรวจ/retry login-email jobs | Central Admin + audit | — | — | — | — |
| `/auth/me`, logout | ตน | ตน | ตน | ตน | ตน |

ทุก list/detail/timeline/evidence/search/bulk ตรวจสิทธิ์เดียวกันใน backend, ไม่พึ่งเมนูและไม่ให้ guessed ID ข้าม Unit; URL รูปต้อง private/อายุสั้นและตรวจ linked event/owner ก่อนออก URL D11/D13 ยังต้องยืนยัน field/image scope รวมสิทธิ์ Admin ในข้อมูลอ่อนไหว ก่อนทำ read APIs จริง

Origin actions ต้องทำในฐานะสมาชิกจริงของ Unit ต้นทางที่มี action grant; ADMIN/Coordinator ที่ไม่มี membership นี้ลงทะเบียนหรือยืนยันรับแทนต้นทางไม่ได้ Actor ใน ledger เป็นผู้ทำรายการจาก session เสมอ ไม่รับ user ID ของบุคคลอื่นใน payload เพื่อ impersonate

### Unit/User lifecycle

1. ADMIN สร้าง Unit เป็น `WAITING_UNIT_ADMIN`; เพิ่ม UNIT_ADMIN และเปิด Unit ใน transaction เดียวกัน
2. user provision เริ่ม “รอเข้า Google ครั้งแรก”; first verified login ผูก unique `google_sub` ไม่ถือว่า linked=active เสมอ
3. Unit ที่ active ต้องมี active UNIT_ADMIN ≥ 1; ห้ามลบประวัติ user/unit ที่ถูกอ้างอิง
4. ปิด/ย้าย/เปลี่ยน role ต้องตรวจ reservation/active leg/direct transfer/task/exception ของผู้ใช้และ Unit; ปฏิเสธ `409 OUTSTANDING_WORK` หากยังไม่มีแผนรับช่วง ไม่แจก custody ให้บุคคลอื่นเงียบ ๆ
5. การเปลี่ยนสิทธิ์บันทึก audit + bump session version/revoke session ใน transaction; ทุก authenticated request ตรวจ active unit/user/membership/scopes ปัจจุบัน

X01/X02 ยังรอยืนยัน: schema รองรับ Unit→หลาย Station และหนึ่ง active membership เป็นข้อเสนอ ห้ามเพิ่ม unique constraint ที่บังคับ policy นี้โดยอ้างว่าผู้ใช้อนุมัติแล้ว

## 5. Transaction boundaries และ failure contract

### B1 — Business command

ใน transaction: lock document → ตรวจ version/transition/permission/reservation/evidence → เขียน state + leg/direct/reservation + append-only event/evidence links + workflow outbox + idempotency result → commit ใช้ document lock เดียวกันทุกช่องทาง รวม direct/reservation/bulk ไม่พึ่ง unique index คนละตารางเพียงอย่างเดียว

Object upload อยู่ก่อน B1 และนอก SQL transaction: backend ตรวจ file type/MIME/size/decode/checksum และ finalize metadata ก่อนใช้ รูป orphan ที่ยังไม่ linked cleanup ได้ตาม policy; รูป linked ไม่ถูกลบแบบ temporary upload

ตอบ business state + `operation_id` + `process_sync_status=PENDING` หลัง commit; รูป/guard ล้มเหลวไม่เขียน Check และไม่ complete task หากเครือข่ายหายหลัง commit ใช้ key/payload เดิมเพื่ออ่านผลเดิม ไม่สร้าง event ใหม่ key เดิม payload ต่างคืน conflict

### W1 — Workflow worker/inbox

เลือก event ต่อ document ตาม sequence; ตรวจ mapping/expected task/action/step ใน shared transaction: unique consumed marker → engine start/complete ผ่าน Java API → ACK + sync projection → commit ทั้งหมด ถ้า engine rollback consumed marker/ACK ต้อง rollback ด้วย App DB มี unique document→process mapping กัน start ซ้ำ; Flowable business key อย่างเดียวไม่ใช่ application dedupe

Claim/lease ใช้ป้องกัน worker ทำงานชนและกู้หลัง restart; ACK สำเร็จหลัง engine commit เท่านั้น event ผิดลำดับ/ผิด task/step ต้อง hold/retry/เปิด mismatch case ไม่ complete task ใหม่ให้ผ่าน ๆ ขณะ sync pending/failed อ่านข้อมูลได้แต่บล็อก workflow mutation ถัดไปของเรื่องนั้น; retry อ้าง event/operation เดิม

P0 compatibility spike ต้องแสดง rollback business+engine/inbox ที่เกี่ยวข้องได้; P2 ต้องพิสูจน์ crash/replay/out-of-order กับ workflow จริง ไม่มีหลักฐานเหล่านี้จากแผนภาพ

### L1 — Login session/audit/email finalization

OIDC callback ตรวจ state/nonce/PKCE และ token/domain/allowlist ก่อน L1; first-sub binding ใช้ lock/unique constraint แล้วออก **session ใหม่**. L1 ต้อง persist session + `LOGIN_SUCCEEDED` audit + unique notification outbox ใน transaction เดียวกัน ก่อนส่ง redirect/cookie ที่รายงานว่าสำเร็จ

ต้องออกแบบ Spring Session JDBC save timing/transaction manager ให้ร่วมจริง ไม่ถือว่าใส่ `@Transactional` ใน success handler แล้ว session filter save จะ atomic เอง ถ้า session/audit/outbox บันทึกล้มเหลว ต้อง rollback และไม่ตอบ login สำเร็จ/ไม่ส่ง success email **นี่เป็นข้อพิสูจน์บังคับ P1-15/T39 ซึ่ง P0 design ไม่รับรองว่าผ่านแล้ว**

### M1 — Notification delivery

Worker อ่านเฉพาะ committed login jobs; unique `(login_event_id,type,recipient)` กัน enqueue ซ้ำ Refresh/me ไม่สร้าง login event ใหม่ แยก mail lease/retry/backoff จาก workflow retry และส่ง SMTP นอก login SQL transaction

สถานะ `PENDING → PROCESSING → ACCEPTED_BY_PROVIDER` หรือ `RETRY_WAIT/FAILED`; เก็บ attempts/error ที่กรองแล้ว ใช้ Message-ID คงเดิม แต่ provider รับแล้ว worker ยังไม่ได้ ACK อาจส่งซ้ำได้ ไม่รับรอง exactly-once SMTP และไม่ใช้ provider-accepted แทนยืนยันถึง inbox

| Failure ที่ต้องทดสอบ | ผลที่ต้องเกิด | ตรวจหลัก |
|---|---|---|
| upload/validation fail | ไม่มี Check/event/task advance | P1-22, T21 |
| B1 commit แล้ว response หาย | retry key เดิมได้ผลเดิม; event เดียว | P1-24, T12 |
| worker ล่มหลัง business commit | outbox ยังอยู่และ replay ได้ | P2-09, T20 |
| engine ล้มหลัง consumed marker | marker/ACK/task changes rollback ร่วม | P0-10 spike, P2-07–09 |
| duplicate/out-of-order/wrong step | ไม่ start/advance ซ้ำหรือผิด task | P2-09, T20/T25 |
| session/audit/mail-outbox fail | ไม่มี authenticated success/notification จาก rollback | P1-15, T39 |
| SMTP ล่ม/ACK ไม่แน่นอน | login ใช้ได้หลัง L1 commit; retry/attempt ติดตามได้ | P1-17–18, T37/T40 |
| user/unit disabled | session เดิมหมดสิทธิ์; outstanding custody ไม่หาย | P1-13, P3/P5, T35/T41 |

## 6. UX gaps และ action/error states

| หน้าหรือสถานการณ์ | สิ่งที่ต้องมีใน implementation | Error/การกระทำต่อ |
|---|---|---|
| Google login | ข้อความ MFU/เพิ่มสิทธิ์ก่อน; ปุ่ม Google; ไม่มี password fallback | domain/unassigned/disabled/identity mismatch/callback expired แบบไม่เผย token; กลับ login หรือ IT |
| Central Unit/user | Unit waiting admin, แต่งตั้ง/เปิดแบบ atomic; linked vs active แยก; audit | duplicate MFU email/unit, last-admin, outstanding-work; แสดงงานค้างที่ผู้ดูแลมีสิทธิ์ |
| Origin B คืน | หน้า return receipt แสดง S3/ปลายทางจริง/รายการ/หลักฐานและปุ่มยืนยัน | ยังไม่ received ให้ dispute; ห้าม complete ด้วย Check ส่งแทน |
| Courier reserve/pickup | owner/countdown จาก server; ทวนเลข/เรื่อง/จำนวน; ภาพและ submit ที่รอ backend | 409 conflict/version, expired reservation; reload/reselect/re-reserve ไม่บันทึก Check ซ้ำ |
| Upload/camera | preview/เลือกใหม่/retry/finalize status; mobile camera/file | invalid type/size/decode/network; คงรายการและไม่แสดง success ก่อน commit |
| Model A detail/worklist | ป้าย A และ “ต้องพบเอกสารจริง”; แยก reported จาก receipt | wrong/repeated step ต้องเลือก exact step/เปิด case; ไม่แสดง READY สมมติ |
| Model B station worklist | แยกรับ/processing/ready; eligible Courier list แสดงเฉพาะพร้อมรับ | ห้ามปุ่มรับส่งถ้าค้าง receipt/processing; รายการติดตามอื่น read-only |
| Sync pending/failed | business status และ workflow sync แยก; operation ID/refresh | read-only ระหว่าง pending; admin retry operation เดิม; ไม่มีปุ่ม Check ซ้ำ |
| Bulk/direct | แยก pickup station/drop-off destination; ทวนรูป/รายชื่อ/จำนวน | conflict ตาม D08; direct ชน reservation ให้ cancel/resolve ก่อน |
| Exception | case owner/reason/evidence/previous state/เสนอ resume target | ไม่มีปุ่มล้าง case แล้วเดินต่อโดยไร้หลักฐาน; resolution ต้อง audit |
| Email operations | pending/attempt/retry/failed/provider-accepted; retry job เดิม | ปิดบัง secret/session/token; ไม่บอกส่งถึง inbox จาก SMTP ACK |
| ทุกหน้า | loading/empty/403/404/409/upload-failed/session-expired; keyboard/labels/touch target ตามแบบ | ไม่ล้างข้อมูลที่ผู้ใช้กรอกโดยไม่จำเป็น; กลับอ่านข้อมูลล่าสุดเมื่อ conflict |

## 7. P0 traceability และข้อจำกัด

| Checklist | หลักฐานในเอกสารนี้/Decision Log | สิ่งที่ยังต้องทำก่อนปิดงาน |
|---|---|---|
| P0-01 | Decision Log D01–D17/สถานะ/ผู้ตัดสิน/blocked tasks | เพิ่มคำตอบจริงเมื่อผู้มีอำนาจตอบ ไม่ติ๊ก decisions ว่าอนุมัติแทน |
| P0-02 | §3 transition A/B รวม return/direct/observation/exception | BPMN executable/tests ใน P2; policy D06–D10/X07–08 |
| P0-03 | §2 แยก reported/current/from/to/repeated step | นำ semantics เดียวกันเข้า OpenAPI/DB/BPMN/tests |
| P0-04 | §4 permission/action/context/evidence/sync ops | D11/D13 scope sign-off ก่อนอ่านข้อมูลอ่อนไหวจริง |
| P0-05–07 | §4 lifecycle; Decision Log X01–X05/D06–D13 | ยังเปิด mapping/membership/TTL/bulk/evidence/duplicate/retention/SLA/RPO/RTO |
| P0-08–09 | Decision Log D16/D17/X06 และ infra handoff | Google client/HTTPS/hosted-domain/bootstrap email/SMTP/test inbox จริงยังไม่มีหลักฐานเตรียมแล้ว |
| P0-11 | §5 B1/W1/L1/M1 และ failure test owners | runtime proof ใน spike/P1/P2; โดยเฉพาะ L1 ยังไม่พิสูจน์ |
| P0-13 | §6 UI gaps/action/error states | UI จริงและ mobile/UAT ใน phases ถัดไป |
| P0-14 | Decision Log D03/D14/X09 | ยืนยัน exclusions; estimate ใหม่เมื่อ scope/infra ทราบ |

P0-10 compatibility และ P0-12 OpenAPI มี artifacts แยกจากเอกสารนี้ ต้องอ่านผลจริงประกอบ G0; ไม่ถือว่าการเขียน design ทำให้สองงานนั้นเสร็จ องค์กรยังต้องเตรียมบริการจริงและยืนยัน open decisions ที่บล็อกแต่ละ feature ก่อน implementation ส่วนนั้น งานทดสอบ isolated provider/mail sink เริ่มได้โดยไม่ต้องรอ production secrets
