# P1 Execution Report

วันที่ 2026-11-06 · ขอบเขต development และ isolated integration tests

P1-01–25 พร้อมใช้งานเป็นฐานพัฒนาต่อ และผ่าน G1 ในขอบเขตการทดสอบภายในเครื่อง ไม่มี test provider หรือ demo authentication ใน production artifact การ implement ไม่ถือเป็นการอนุมัติ business decisions ที่ยังเปิดใน [P0 Decision Log](../p0/Decision_Log.md) และยังไม่ใช่ staging/production sign-off

## สิ่งที่ส่งมอบ

- Backend Java 21 / Spring Boot 4.0.9 / Flowable 8.0.0 / PostgreSQL 17.11 พร้อม Maven Wrapper 3.9.11, SHA-256 distribution verification และ Flyway application migrations V1/V2
- Frontend Nuxt 4.6.4 / Vue 3.5.45 / Nuxt UI 4.12.0 / TypeScript 5.9.3 พร้อม package lockfile, Google-only login/profile, Central/Unit administration, templates/registry/detail/timeline/evidence, station worklist/reservations และ notification operations
- Google OIDC authorization-code + PKCE/state/nonce, single-use callback มีอายุ 5 นาที, rate limit 20 requests/minute ต่อ observed address/endpoint; ตรวจ signed ID-token claims และ exact MFU domain/allowlist ก่อนสร้าง session
- First-link Google sub ใช้ row lock/unique constraint และไม่ overwrite sub เดิม; session JDBC + login audit + notification outbox อยู่ใน REQUIRED transaction เดียวกัน สร้าง cookie หลัง persist สำเร็จ และเปลี่ยน session เมื่อ login ใหม่
- Central Admin bootstrap จาก configured MFU email แบบ idempotent; สร้าง Unit ที่รอ Admin, แต่งตั้ง Unit Admin/เปิด Unit แบบ atomic, เพิ่ม/แก้/ปิด STAFF ในขอบเขต, explicit station/courier grants, last-admin/outstanding-work guards และ session revocation
- SMTP worker แยกจาก login request มี lease/retry/backoff/attempt history/fixed Message-ID/admin retry + audit; `ACCEPTED_BY_PROVIDER` หมายถึง SMTP รับ ไม่รับรอง inbox delivery หรือ exactly-once
- Immutable template versions/document route+mode+policy snapshots, origin-scoped registry/detail/timeline, station-scoped redacted worklist, private JPEG/PNG upload/checksum/decode/finalize/authorized download/attach และ single reserve/cancel/expiry
- Idempotency receipt + request hash/result, optimistic versions, row locks, transport uniqueness guards, immutable event/evidence ledger และ workflow outbox/inbox schema ตรวจ active/session version ใน command transaction รวมถึง replay
- Compose dev/base, same-origin Nginx, private environment template, health endpoints และ CI jobs สำหรับ backend/PostgreSQL/frontend

## ผลตรวจสอบ

| Check | ผล |
|---|---|
| Maven Wrapper `verify` | ผ่าน; executable Spring Boot JAR สร้างสำเร็จ |
| IdentityIntegrationTest | 11 tests ผ่าน |
| OAuthHttpIntegrationTest | 4 tests ผ่าน ผ่าน HTTP token/JWKS provider ที่เซ็น JWT จริงเฉพาะ test source |
| SmtpIntegrationTest | 1 test ผ่าน ส่งด้วย JavaMailSenderImpl ไป loopback SMTP sink จริง ไม่มี external relay |
| TrackingIntegrationTest | 13 tests ผ่านบน PostgreSQL จริง |
| Frontend typecheck / native tests / static build | ผ่าน / 2 tests ผ่าน / ผ่าน |
| Browser UI | Chrome desktop 1440×1000 / mobile 390×844: login + 5 authenticated screens ต่อ viewport, 4 form submissions, Unit Admin scope และ registration ที่ไม่มี courier grants; ไม่มี horizontal overflow/page errors |
| OpenAPI validator | ผ่าน 35 paths / 42 operations; contract รวม future P2–P5 endpoints ที่ยังไม่ implement |
| Compose base/dev | `config --quiet` ผ่านทั้งสอง configuration |
| Native JAR smoke | เริ่ม server ที่ loopback: health 200, anonymous `/auth/me` 401, Google start ที่ยังไม่ตั้ง client 503 |
| Git whitespace | `git diff --check` ผ่าน |

ผลแบบอ่านด้วยโปรแกรม: [Test_Results.json](Test_Results.json), [UI_Test_Results.json](UI_Test_Results.json) · ภาพ [Central Admin desktop](images/units-desktop.png), [Document detail mobile](images/detail-mobile.png)

Maven Wrapper บนเครื่องนี้ดาวน์โหลดผ่าน .NET ไม่สำเร็จ จึงใช้ Maven ZIP ที่ตรวจ SHA-512 กับ Maven Central และเตรียม wrapper cache ภายใน workspace จากนั้นรัน `mvnw.cmd verify` ผ่านจริง ไม่เปลี่ยนหรือปิด certificate validation

Browser tests ใช้ API request fixtures เฉพาะ test harness สำหรับ authenticated screens จึงตรวจ UI/form headers และขอบเขตการเรียก API ไม่อ้างว่าเป็น browser end-to-end กับ Google จริง การตรวจ backend ทำแยกผ่าน integration tests ที่ใช้ JDBC session, signed OIDC provider และ PostgreSQL

Final review พบและแก้การกรองเส้นทางในหน้าลงทะเบียนด้วย courier station grants ซึ่งขัดกับสิทธิ์ origin membership พร้อมเพิ่ม regression ใน browser harness; แก้ Unit Admin list ให้ระบุหน่วยงานที่ดูแลใน API query แล้ว

## Acceptance coverage P1

| กลุ่ม | หลักฐาน/ขอบเขต |
|---|---|
| T27–T29 | Signed OAuth callback → usable JDBC cookie; ตรวจ allowlist/exact domain/unverified/hd; ไม่ auto-create account |
| T30 | Signature/issuer/audience/token expiry/nonce/PKCE mismatch/state tamper/state expiry/replay ถูกปฏิเสธ; valid PKCE ถูกตรวจโดย token endpoint |
| T31 | Concurrent first-link เขียนได้ครั้งเดียว, เปลี่ยน Google sub ถูกปฏิเสธ |
| T32–T34 | Bootstrap ไม่ promote บัญชีเดิม, Unit activation + provisioning atomic/idempotent, Unit Admin สร้าง/แก้ STAFF ของตน และปฏิเสธ cross-unit/escalation/mass assignment |
| T35 | Last-admin/outstanding-work guards, ปิด Unit/เปลี่ยน role/revoke แล้ว session เดิมใช้ไม่ได้; captured actor เขียน/replay ไม่ได้หลัง revoke; ทดสอบกับ leg/task ที่ execute จริงซ้ำใน P3–P5 |
| T36–T38 | Session+audit+outbox, SMTP failure ไม่ตัด session, retry original job, refresh/me/replayed callback ไม่ enqueue ใหม่; ส่งจริงเฉพาะ loopback sink |
| T39 | Inject failure ใน session save/audit/outbox แล้ว Google sub/session/audit/email job rollback ทั้งชุด |
| T40–T41 | Lease recovery, fixed Message-ID/attempts/admin retry audit, CSRF/logout/role guards และ cookie replacement |
| Reservation / evidence | สอง courier แข่งจองได้คนเดียว, retry คืนผลเดิม/payload ต่าง conflict, cancel/expiry ไม่เปลี่ยน custody ที่รับไปแล้ว, upload/checksum/decode/storage failures ไม่สร้าง business event สำเร็จ, cross-document constraints และ private photo authorization |

ดู test methods ใน [backend tests](../../backend/src/test/java/th/ac/mfu/doctracking) และ [UI smoke harness](../../frontend/tests/ui-smoke.mjs) คำสั่งรันซ้ำอยู่ใน [README](../../README.md)

## ขอบเขตที่ยังเปิด

1. **P0/G0:** Unit/Station/membership policy, reservation TTL, field/photo scope, duplicate/retention/SLA/RPO/RTO และ infra approvals ยังรอยืนยัน Development ใช้ MODEL_B / TTL 600 วินาที และ conservative origin-detail/photo access; prod ต้องตั้ง `APP_POLICY_CONFIRMED=true` หลังเจ้าของอนุมัติจริง
2. **P2:** ยังไม่มี deployable business BPMN/outbox consumer จึงสร้างเอกสารเป็น `PENDING` และยังจองเอกสารใหม่ผ่าน UI ไม่ได้จน workflow sync เสร็จ Tests ที่จองงานใช้ ready/APPLIED fixture เฉพาะ test source ไม่มี API บังคับ APPLIED หรือ fake workflow completion
3. **Staging/P8:** ยังไม่ใช้ Google client/MFU account จริง, SMTP relay/MFU inbox จริง, HTTPS callback หรือ S3 service/bucket policy จริง Local private storage ผ่าน tests; S3 adapter มีแล้วแต่ยังไม่ผ่าน integration กับ storage จริง
4. **Containers/CI:** Docker Engine ไม่พร้อม จึงยังไม่ได้ build/start container stack และ CI workflow ยังไม่ได้รันบน remote runner; local equivalents ผ่าน การสร้าง Flowable-owned production schema/upgrade ต้องทำตาม release procedure ใน P6 ห้ามเปิด auto-update แบบ dev บน production
5. **P6/P7:** production monitoring, retention cleanup, backup/restore, mail operations escalation และ security assessment เต็มระบบยังเป็น phase ถัดไป Runtime หลัง proxy เห็น proxy address; ต้องยืนยัน trusted-proxy/rate-limit policy ตาม topology จริงใน P7 ไม่เชื่อ `X-Forwarded-For` จาก client โดยตรง
6. **Membership:** P0 เรื่องจำนวน memberships ยังเปิด API ปัจจุบันเพิ่ม/เปลี่ยน role ตาม Unit แบบ explicit โดยไม่ลบ membership อื่นอัตโนมัติ การย้ายที่ต้องถอน membership เดิมต้องยืนยัน policy และทำขั้นตอน audit/guards ก่อนเปิดใช้จริง

ไม่มีการอ่าน/นำ secrets ของแอปอ้างอิงมาใส่ repo, ส่งอีเมลออกไปยังบุคคลภายนอก หรือ deploy production ระหว่างงานนี้
