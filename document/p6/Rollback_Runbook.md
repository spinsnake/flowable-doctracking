# Rollback / recovery runbook

สถานะ: จัดทำขั้นตอนพร้อม release/backup tooling แล้ว ยังไม่ได้ rollback application/schema/process versions บน staging จริง ต้องซ้อมตาม environment/image/schema ที่จะ release ก่อนใช้จริง

## Record และ contain

ปิด ingress, หยุด backend ทุก replica/worker/timer/mail/reconciliation/cleanup และ external writers เก็บ incident timestamp, release ID, old/new image digests, config revisions, Flyway history/checksums, Flowable schema version, process definitions/mappings, latest ledger sequence, outbox/inbox state และ notification attempts ไว้ใน restricted incident record ห้ามใส่ tokens/cookies/อีเมล/รูปลง public logs

สร้าง verified **post-failure backup** ภายใต้ writer fence เพิ่มจาก pre-release backup เพื่อรักษาข้อมูลที่เกิดหลัง release แม้แอปล้มเหลว PostgreSQL/object storage อาจยังมีข้อมูลที่ commit แล้ว การคืน pre-release snapshot ทันทีทำให้ข้อมูลใหม่สูญหาย ต้องแจกแจง affected documents, process/task instances, accepted provider attempts และ evidence ก่อนตัดสินใจ

## เลือกวิธีตาม compatibility ที่พิสูจน์แล้ว

| สถานการณ์ | วิธีที่อนุญาตหลังตรวจข้อมูล |
|---|---|
| เปลี่ยน app/config เท่านั้น; schema/task payload/delegates ยัง backward compatible และตรวจแล้ว | กลับ old **image digest + config revision** โดยคง DB/objects ปัจจุบัน ตรวจ instance เก่า/ใหม่และ outbox ก่อนเปิด writers |
| App migration เป็น additive และ old app รองรับข้อมูล/constraints ใหม่จริง | กลับ image ได้หลัง staging regression บน copy ของข้อมูลหลัง release; ไม่ลด Flyway version/ลบ migration history |
| Migration/engine schema/variable/delegate เปลี่ยนแบบ incompatible หรือยังพิสูจน์ไม่ได้ | หยุด writer; เลือก forward fix หรือ restore bundle ลง **isolated target ใหม่** แล้วทำ data reconciliation/approved cutover ห้ามเดา down-migration ใน live database |
| BPMN version ใหม่ผิด แต่มี instance ใหม่เริ่มแล้ว | เก็บ definition/delegates ที่ active instances ต้องใช้ หยุดการเริ่มเรื่องใหม่/แก้ release deployment สำหรับเรื่องใหม่ตาม change procedure; ไม่ delete process deployment ที่มี instance และไม่ migrate task/custody อัตโนมัติ |
| SMTP ACK ไม่แน่นอน | รักษา attempts/provider state และให้ mail owner ตรวจ อาจส่งซ้ำ; rollback DB ไม่เรียกคืนอีเมลที่ส่งแล้วและไม่ทำให้ exactly-once |

Image rollback ไม่ลด schema หรือ process definition เอง การสลับ default A/B มีผลเฉพาะเอกสารใหม่ ไม่ใช่เครื่องมือ rollback active instance ห้าม complete task/check/receipt เพิ่มเพื่อให้ process วิ่งถึง stage ที่คาดไว้ ห้ามลบ ledger/evidence/outbox หรือเปลี่ยน immutable snapshot เพื่อทำให้ reconciliation ผ่าน

## Rehearse image rollback

ใช้ `scripts/operations.py release` สร้าง override ที่ระบุ digest เก่าที่ตรวจแล้ว และ private configuration revision เก่าที่เข้ากับ current schema ตรวจ Compose โดยไม่เผย secrets; คง maintenance และ workers ปิดจน health/schema/active process compatibility smoke ผ่าน ใช้ `docker compose ... up -d --no-build backend frontend` เฉพาะหลังตรวจแล้ว รายละเอียดใน [Deployment](Deployment_Runbook.md)

ตรวจ registered process mappings ของ old/new instances ไม่เปลี่ยน, current task IDs/route steps/leg ownership, pending/retry/dead-letter operations และ direct/exception state ที่ระบบรองรับจริง จากนั้น enable workers ตามลำดับและติดตาม exact event/task validation ก่อนเปิด ingress บันทึกเวลาที่ service กลับใช้งานได้และเก็บ images/config ที่ใช้ได้สำหรับ incident record

## Restore branch และข้อมูลใหม่หลัง release

ทำตาม [Backup / Restore](Backup_Restore_Runbook.md) เพื่อคืน pre-release หรือ post-failure consistent bundle ลง database/store เปล่าใหม่ เปรียบเทียบ current/post-failure ledger กับ snapshot โดยผู้รับผิดชอบข้อมูลระบุ committed operations ที่ขาดไป แยกธุรกรรมที่ committed แล้ว, operation pending/retry, physical custody ที่เปลี่ยนจริง และ external side effects เช่น email accepted

การนำข้อมูลใหม่กลับเข้าระบบต้องเป็น recovery/change procedure ที่รักษา immutable event IDs/sequence, snapshot, engine correlation และ evidence hashes ให้ตรวจสอบย้อนหลังได้ ไม่มี generic SQL copy/replay ที่ได้รับอนุมัติใน runbook นี้ ถ้ากู้สถานะจริงไม่ได้ให้เปิดรายงาน mismatch/exception เพื่อคนรับผิดชอบตรวจ ไม่สร้างหลักฐานรับ/ส่งย้อนหลังเอง ยืนยัน observed data loss/RPO และเวลาหยุดบริการ/RTO กับ incident owner ก่อน cutover ไป restored target

คง source/post-failure backup และ manifest ตาม retention/legal hold ที่อนุมัติ ห้ามลบทันทีเมื่อ smoke ผ่าน จบ incident หลังตรวจ service, monitoring, scoped API/auth/evidence และผล reconciliation รวมถึงรายการ unresolved/ยอมรับความเสี่ยงที่มีผู้รับผิดชอบระบุชัดเจน
