# P0 — Decision Log

วันที่: 2026-11-06 (Asia/Bangkok)  
สถานะ: ร่างสำหรับเริ่มพัฒนาและติดตามข้อยืนยัน; ไม่ใช่ production sign-off

อ้างอิง [ข้อกำหนดหลัก §17](../Document_Tracking_Flowable_Development_Requirements.md#17-decisions-ที่ต้องให้เจ้าของระบบยืนยัน), [แผนพัฒนา §8](../Development_Plan_and_Tech_Stack.md#8-ประเด็นต้องให้เจ้าของระบบตัดสิน) และ [Authentication](../Authentication_and_Central_User_Requirements.md) ตารางนี้บันทึกสิ่งที่ทราบจริง ณ P0 ไม่เปลี่ยนข้อเสนอเดิมให้เป็นการอนุมัติ เมื่อมีคำตอบให้เติมผู้ตัดสินจริง วันที่ และข้อความอ้างอิงก่อนเปลี่ยนสถานะ

## สิ่งที่ผู้ใช้สั่งไว้แล้ว

| ข้อกำหนด | หลักฐาน/สถานะ | ผลต่อ implementation |
|---|---|---|
| Google login ด้วยอีเมล `@mfu.ac.th` | ยืนยันตามคำขอผู้ใช้ใน session และ Authentication §1 | ใช้ OIDC ฝั่ง backend; app allowlist/role ยังเป็นข้อมูลแอป |
| ส่งอีเมลแจ้งเตือนเมื่อ login | ยืนยันตามคำขอผู้ใช้; รายละเอียดส่งทุก session ใหม่เป็น baseline ใน Authentication §4 | มี notification outbox/attempt/retry; ไม่ขอ Gmail scope |
| ผู้ดูแลกลางเพิ่ม Unit และผู้ใช้หน่วยงาน | ยืนยันตามคำขอผู้ใช้และอ้างอิง DOCTRACKING | ทำ ADMIN provisioning; บัญชีกลางเป็นบทบาทของบุคคล |
| รองรับ Model A/B | เป้าหมายในเอกสารและการสนทนาเดิม | ต้องมีทั้งสอง branch และ tests; ค่า go-live และข้อยกเว้นยังต้องให้เจ้าของระบบตัดสิน |

คำว่า **BASELINE / รอยืนยัน** หมายถึงเอกสารเดิมระบุพฤติกรรมแล้ว แต่ยังไม่มีหลักฐานผู้มีอำนาจลงนาม decision นั้น ส่วน **ข้อเสนอ / รอยืนยัน** ยังเป็นทางเลือก ทีมเริ่มออกแบบส่วนที่ไม่ขึ้นกับคำตอบได้ แต่ห้ามตรึง policy จริงจากความเงียบของผู้ใช้

## D01–D17

ผู้ตัดสินในตารางเป็นบทบาทที่ต้องจัดหาคนรับผิดชอบ ยังไม่ได้แต่งตั้งบุคคลหรือกำหนด deadline แทนเจ้าของงาน

| ID | คำตอบที่มี/ข้อเสนอสำหรับพัฒนา | สถานะ | ผู้ตัดสินที่ต้องระบุชื่อ | งานที่ขึ้นกับคำตอบ / สิ่งที่ต้องได้ก่อนผ่าน |
|---|---|---|---|---|
| D01 | เริ่ม `MODEL_B`; Admin เปลี่ยน default ได้ | BASELINE / รอยืนยัน go-live default | เจ้าของระบบ | P1-20, P4-05: ค่า initial policy จริง |
| D02 | ไม่ให้ผู้ลงทะเบียน override mode รายเรื่องใน MVP | ข้อเสนอ / รอยืนยัน | เจ้าของระบบ | P1-20, P4: register contract และ guards |
| D03 | switch default มีผลเฉพาะเรื่องใหม่; ไม่ migrate active instance | BASELINE / รอยืนยันขอบเขต MVP | เจ้าของระบบ + ผู้ดูแล workflow | P4, P6-07: ยืนยัน exclusions และ UI ปฏิเสธ active switch |
| D04 | A ใช้ `COURIER_TRACKING_COMPLETE` และข้อความ “จบการติดตามการนำส่ง”; B ใช้ `COMPLETED` | BASELINE / รอยืนยันคำ UX | เจ้าของงานธุรการ | P2-04, P4, P8-03: ข้อความจบไม่อ้างว่า A มี unit receipt |
| D05 | B จบหลังต้นทางยืนยันรับที่ return step เท่านั้น | BASELINE / รอยืนยัน business sign-off | เจ้าของงานธุรการ | P2-03, P3-06, P5-06: ต้องได้ต้นแบบรับคืนที่ใช้ตรวจ UAT |
| D06 | เสนอให้ direct dispatch ต้องแนบรูป; unit receipt ปกติไม่บังคับรูป | ข้อเสนอ / รอยืนยัน | เจ้าของงานธุรการ + ผู้ดูแลข้อมูล | P1-22 ส่วน policy, P5-05: ตรึง required/optional และกรณียกเว้น |
| D07 | เสนอทดลอง TTL จอง 10 นาทีใน staging; ใช้ server time และหมดอายุก่อน pickup เท่านั้น | ข้อเสนอ / รอยืนยัน | หัวหน้าผู้เดินหนังสือ | P1-23, P5-04: ค่า TTL จริงและผลทดลอง; ห้าม expire custody |
| D08 | เสนอ bulk all-or-nothing ต่อชุด submit; conflict แล้วเลือกใหม่อย่างชัดเจน | ข้อเสนอ / รอยืนยัน; มีข้อขัดกันในเอกสารเดิม | เจ้าของงานธุรการ + หัวหน้าผู้เดินหนังสือ | P5-01–04: Requirements §17 เสนอ per-item แต่ Plan §4.3 เสนอ atomic; ต้องเลือกนโยบายเดียวก่อน bulk implementation |
| D09 | Drop-off ไม่ auto-receipt; คง pending และเปิด dispute/exception ได้ | BASELINE / รอยืนยัน owner/escalation | เจ้าของงานธุรการ | P3-05, P5-07–08: ผู้รับผิดชอบและวิธี resolve; ห้าม timer รับแทน |
| D10 | เปลี่ยน route/ส่งต่องานกลาง leg ต้องมีผู้อนุมัติและ correction audit; ไม่เปิด arbitrary edit | ข้อเสนอ / รอยืนยัน flow ที่อยู่ใน MVP | เจ้าของระบบ + Coordinator | P5-08–09: ถ้าไม่อนุมัติ flow ให้ปฏิเสธชัด; ไม่สร้าง handoff อัตโนมัติ |
| D11 | Origin-only list; Station worklist ตาม step/scope; Courier ตาม scope/leg; Admin role ไม่ได้เห็นรูปทั้งหมดโดยปริยาย | BASELINE / รอยืนยัน field/image scope | เจ้าของข้อมูล + เจ้าของระบบ | P1-21–22, P7-08: อนุมัติ permission matrix และ field redaction |
| D12 | ยังไม่มี SLA/ระยะ timer/recipient escalation | OPEN | เจ้าของบริการ + Operations | P2 timers, P6-04, P8: ได้เวลา/ผู้ติดตาม/เวลาบริการ; ไม่สร้างตัวเลขสมมติ |
| D13 | ยังไม่ทราบการจัดชั้นเอกสารอ่อนไหวและผู้มีสิทธิ์ดูรูป | OPEN | เจ้าของข้อมูล + IT security | P1-21–22, P7: ข้อมูล sensitivity และ retention ที่ได้รับอนุมัติ |
| D14 | online-only ใน MVP; retry request เดิมได้เมื่อเครือข่ายกลับมา | ข้อเสนอ / รอยืนยัน | เจ้าของระบบ + หัวหน้าผู้เดินหนังสือ | P1 API, P3 mobile, P8: ไม่รับ offline commit หรือเชื่อ device time |
| D15 | เทียบเลขหนังสือ/ชื่อเรื่อง/รายการและภาพ; ไม่ใช้ QR/OCR | BASELINE / รอยืนยันวิธีตรวจของผู้ใช้ | เจ้าของงานธุรการ | P3 check UI, P5 bulk: UAT วิธีจับคู่และกรณีเลขซ้ำ/ไม่มีเลข |
| D16 | Bootstrap Central Admin จาก MFU email ที่อนุมัติ; ไม่ใช้บัญชีแชร์ | ยืนยันว่าต้องมี Central Admin; OPEN อีเมลจริง/ผู้ดูแล | เจ้าของระบบ + MFU Google Workspace admin | P0-08, P1-10, P8-01: config จริงนอก repo; ยังไม่ provision Google client/บัญชีจริงใน P0 นี้ |
| D17 | ใช้ SMTP องค์กร; Central Admin ติดตาม job; retention/retry budget/sender ยังไม่กำหนด | ยืนยันว่าต้องมี login email; OPEN infra/policy | MFU mail admin + Operations + เจ้าของข้อมูล | P0-09, P1-16–19, P6-05, P8-02: relay/sender/TLS, failed-job owner, test inbox และ retention |

## ประเด็นเพิ่มที่ต้องตรึงร่วมกับ Dxx

| ID | ข้อเสนอ/ข้อมูลที่ขาด | สถานะและผู้ตัดสิน | บล็อกงานใด |
|---|---|---|---|
| X01 | หนึ่ง Unit เป็นเจ้าของหลาย Station ได้; Station มี owner Unit เดียว | ข้อเสนอ / รอยืนยันเจ้าของระบบ | P0-05, P1-04 identity/registry mapping |
| X02 | ผู้ใช้หน่วยงานหนึ่ง active membership; Courier station scopes หลายจุดแยกจาก membership | ข้อเสนอ / รอยืนยันเจ้าของระบบ | P0-05, P1-04/09/11–13; schema/unique constraint ที่เกี่ยวข้อง |
| X03 | เลขภายในใช้ UUID; เลขหนังสือธุรกิจซ้ำได้แค่ไหน เช่น แยก origin/year/type ยังไม่ทราบ | UUID เป็น technical design; duplicate rule OPEN เจ้าของงานธุรการ | P0-07, P1-05/20: ห้าม unique document number ทั้งระบบโดยเดา |
| X04 | อายุรูป/เอกสาร/login audit/notification attempts/backup และสิทธิ์ลบ | OPEN เจ้าของข้อมูล + Operations | P0-07, P6-05/09, P7-15; ห้ามใส่ค่า retention โดยไม่มี policy |
| X05 | SLA, ปริมาณงาน, RPO/RTO, backup target และ restore owner | OPEN เจ้าของบริการ + Operations | P0-07, P6-04/09/12; ประเมิน capacity/เวลาหลังได้ข้อมูล |
| X06 | Google hosted-domain claim ที่ MFU ใช้จริง, HTTPS staging hostname/callback, OAuth client และทดสอบทุก role | OPEN MFU Google Workspace admin + Operations | P0-08, P1-07 configuration, P8-01/09 |
| X07 | Model A observation ข้าม step/repeated-station: เสนอให้ Coordinator ระบุ step พร้อมเหตุผลก่อน reconcile | ข้อเสนอ / รอยืนยันเจ้าของงานธุรการ | P4 observation, P5-08: ห้ามเลือก step ด้วยชื่อ Station เพียงอย่างเดียว |
| X08 | ทางกลับจาก exception แต่ละชนิดและผู้อนุมัติ; ไม่รองรับ correction custody โดยไม่มี flow | OPEN เจ้าของงานธุรการ + Coordinator | P0-06, P5-07–09: resolution map และหลักฐานจริงที่ต้องมี |
| X09 | ไม่เพิ่ม native app/QR/OCR/digital signature/public BPMN designer/active-mode migration และไม่เพิ่ม distributed services ใน MVP | BASELINE / รอยืนยัน exclusions เจ้าของระบบ | P0-14, estimate และ P8 scope sign-off |

## Infra handoff และการปิด decision

ส่งให้ผู้ดูแลผ่านช่องทางทีมที่ตกลงกัน (เอกสารนี้ไม่ได้ส่งข้อความหรือสร้าง credential แทนผู้ใช้):

| ผู้รับผิดชอบ | สิ่งที่ต้องส่งมอบ | เก็บไว้ที่ใด/หลักฐานที่ปลอดภัย |
|---|---|---|
| Google Workspace admin | Web OAuth client แยก environment, allowed MFU domain, exact HTTPS callback, test accounts ทุก role | secret store/environment; บันทึกชื่อ environment/ผู้ดูแล/ผลทดสอบ ไม่ใส่ secret ใน MD |
| Mail admin | relay host/port/TLS/auth method, sender ที่อนุญาต, quotas และ test recipient | secret store; บันทึกผล provider-accepted กับรับเข้า inbox แยกกัน |
| Operations | HTTPS hostname/cert, PostgreSQL/storage target, backup/restore owner, monitoring recipient | deployment inventory/runbook; RPO/RTO มาจากข้อยืนยันและผล restore จริง |
| Business/data owner | D01–D15, mapping/membership, duplicate rule, classification/retention | เปลี่ยน status ในตารางพร้อมชื่อ/วันที่/หลักฐานคำตอบ |

ประเมินเวลาใหม่หลังข้อที่กระทบ scope ตอบแล้วและ compatibility spike เสร็จ กรอบเดิม 10–12 สัปดาห์ไม่ใช่ commitment ใหม่ ไม่มีหลักฐานว่า infrastructure หรือ business decisions ข้างต้นได้รับอนุมัติแล้ว ณ วันที่เอกสารนี้

## บันทึกการเปลี่ยนแปลง

| วันที่ | การเปลี่ยนแปลง | ผู้ทำ/หลักฐาน |
|---|---|---|
| 2026-11-06 | สร้าง log ครบ D01–D17, X01–X09 และ dependency blockers จากเอกสารเดิม | P0 documentation; รอผู้มีอำนาจเติมการตัดสินจริง |
