# P6 operations — ติดตามและ retry งานเดิม

Central Admin ใช้หน้า `/admin/operations` หรือ technical API ที่ระบุใน [OpenAPI](../../contracts/openapi.yaml) ผู้ใช้ทั่วไปไม่มีสิทธิ์ หน้านี้แสดง references/status เท่านั้น การเข้าถึงข้อมูลเทคนิคไม่ให้สิทธิ์อ่านหนังสือ/รูปข้าม origin และไม่มีปุ่ม complete task หรือรับหนังสือแทนผู้ใช้งาน

## Workflow ที่ล้มเหลว

1. เลือก delivery ที่ RETRY_WAIT/DEAD_LETTER ตรวจ operation/document/event references, last_error_code และ version; batch หนึ่งอาจมีหลาย delivery ให้เลือกเฉพาะที่ต้องแก้
2. ใช้ “ตรวจขั้นตอน” ตรวจ pinned process definition/version, active task key และ exact route/leg binding; เทียบ diagnostic findings หากข้อมูลไม่ตรงกัน ห้ามแก้ตาราง Flowable โดยตรงหรือ replay เพื่อข้าม step
3. ตรวจและแก้สาเหตุเชิงระบบ เช่น DB/worker availability หรือ release/delegate compatibility ด้วย runbook ถ้า canonical ledger/event หรือ business correction ไม่ถูกต้อง ให้พักงานและส่งต่อ approved correction flow ไม่แก้ immutable ledger/สร้าง fake Check เพื่อให้ผ่าน
4. กด “ลองงานเดิมอีกครั้ง” ใส่เหตุผล คำสั่งใช้ original operation + delivery_id + expected_version, CSRF และ Idempotency-Key โดย backend เก็บ actor/reason audit หาก stale ให้โหลดล่าสุด; ถ้าคำตอบเครือข่ายไม่แน่นอน ใช้ request/key เดิม
5. ตรวจ operation จน APPLIED ครบทุก delivery; อย่าถือว่าการเข้าคิว 202 คือขั้นตอนถัดไปพร้อมแล้ว Worker ยังตรวจ event/task/step และอาจพักอีกเมื่อ payload ไม่ถูกต้อง เรื่องที่สำเร็จแล้วไม่ต้องส่งซ้ำ

## Reconciliation

การตรวจอัตโนมัติใช้ `RECONCILIATION_ENABLED` และ bounded scan ส่วน manual scan ต้องระบุเหตุผลและอาจเลือก document UUID ถ้าเว้นว่างตรวจ 100 เรื่องแรก; scheduler เลื่อน cursor ผ่านทั้งชุด แถวที่ถูก worker/command ล็อกจะถูก skip เพื่อไม่ตรวจสถานะระหว่าง transaction

Finding มี code/state/first_seen/last_seen/sightings เท่านั้น ไม่ใช่ business exception resolution Pending outbox พัก engine comparison และคง engine finding ที่เปิดอยู่ แต่ยังตรวจ ledger/projection/envelope การซ่อมต้องทำจากสาเหตุที่ตรวจแล้ว จากนั้น rescan จึงปิด finding ที่หายไปจริง ไม่กด resolve โดยไม่ตรวจ และไม่ใช้ scan สร้าง receipt/custody/route correction

## อีเมลเข้าสู่ระบบ

หน้า operations ไม่แสดง recipient; หน้าอีเมลเดิมสำหรับ Admin มีข้อมูลรายผู้รับและ retry เฉพาะ job เดิมใส่เหตุผล Worker ใช้ lease token กัน stale ACK และ retry/backoff จน terminal failure SMTP อาจรับ body แล้วตอบกลับไม่สำเร็จ แม้ Message-ID เดิมก็อาจส่งซ้ำ ห้ามอ้าง exactly-once หรือเปลี่ยน ACCEPTED_BY_PROVIDER เป็น delivered-to-inbox โดยไม่มี provider/inbox evidence

## Health, correlation และ metrics

| Endpoint | การเข้าถึง / ความหมาย |
|---|---|
| `/actuator/health/liveness` | Public, ไม่มี details; application availability |
| `/actuator/health/readiness` | Public, ไม่มี details; readiness + DB + deployed workflow |
| `/healthz` ที่ Nginx | Proxy ไป backend readiness; ต้องตรวจ routing/TLS บน staging จริง |
| `/actuator/metrics` และ metric detail | Current Central Admin session เท่านั้น; frontend gateway ไม่ expose actuator ทุก path |

Metrics ชื่อ `doctracking.workflow.outbox.pending`, `.retry`, `.dead.letter`, `.lag.seconds`, `doctracking.mail.retry`, `.failed`, `doctracking.workflow.tasks.waiting`, `.oldest.seconds` อ่านจำนวน/อายุจริง ไม่มี document/user IDs เป็น metric labels ค่า NaN หมายถึงอ่านฐานข้อมูลไม่ได้ ไม่ตีความเป็นศูนย์

In-app alerts WORKFLOW_DEAD_LETTER, MAIL_DELIVERY_FAILED และ RECONCILIATION_MISMATCH แสดงเมื่อมีรายการจริง เหตุการณ์รอปกติและอายุงานยังไม่ใช่ SLA breach ต้องกำหนด approved threshold/recipient/channel กับ Operations ก่อนต่อ external alerts; feed นี้ยังไม่ได้ส่ง notification ให้บุคคลภายนอก

`X-Correlation-ID` รับเฉพาะ UUID ที่ถูกต้องหรือสร้างใหม่ และตรงกับ API error correlation_id Structured request logs มี method/route template/status/duration โดยไม่บันทึก query/body/recipient/credentials Nginx API access log ปิดและ access log อื่นไม่มี URL/query; ต้องตรวจ proxy/error-log runtime และเก็บ logs ตาม approved retention ก่อน staging sign-off

## รูป orphan และ retention

ใช้ dry-run ในหน้า Admin หรือ cleanup API เมื่อมี approved positive ORPHAN_GRACE_SECONDS เท่านั้น การลบจริงต้องเปิด ORPHAN_CLEANUP_ENABLED และส่ง dry_run=false อย่างชัดเจน ระบบไม่เลือกรูป FINALIZED/linked ไม่ลบ ledger/audit/history/mail attempts และไม่กำหนด retention ของหลักฐานจาก grace ของ staged uploads ถ้า storage timeout/DB rollback ให้ retry exact key เดิมผ่าน command เดิม; external object delete rollback ไม่ได้ แต่ metadata ที่ยังไม่ commit จะกลับมาให้ retry

Deploy/rollback/full backup restore ใช้ [Deployment](Deployment_Runbook.md), [Rollback](Rollback_Runbook.md), [Backup/Restore](Backup_Restore_Runbook.md) พร้อม release record และ environment ที่กำหนด ผล local development ไม่ใช่ staging approval
