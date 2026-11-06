# P0 — Execution Report และหลักฐานตรวจสอบ

วันที่: 2026-11-06 (Asia/Bangkok)

สถานะ: ทำ technical baseline และ compatibility proof แล้ว; **P0 ยังไม่ผ่าน Exit Gate G0** เพราะ business/infra dependencies ยังไม่ครบ การทำ API contract และ spike ไม่ใช่การสร้าง P1 backend หรือเปิดใช้ Google login แล้ว

## ผลส่งมอบ

| งาน | หลักฐาน | ผล |
|---|---|---|
| P0-01 | [Decision Log](Decision_Log.md) | ครบ D01–D17 และ X01–X09 แยก confirmed requirement/baseline/proposed/open พร้อม owner/blocking tasks |
| P0-02–04 | [Design Baseline §2–4](Design_Baseline.md) | Transition A/B, reported/current/pending step, repeated Station และ permission matrix ครบ; field/image business scope ยังติด D11/D13 |
| P0-10 | [Compatibility spike](../../spikes/flowable-compatibility/README.md) | Build/test ผ่าน PostgreSQL จริง 3 tests, ไม่มี fail/error/skip |
| P0-11 | [Design Baseline §5](Design_Baseline.md) และ spike | กำหนด B1/W1/L1/M1 boundaries; พิสูจน์ W1 JDBC/engine rollback แล้ว ส่วน login session/audit/email runtime proof เป็น P1-15 |
| P0-12 | [OpenAPI](../../contracts/openapi.yaml), [scope](../../contracts/README.md) | OpenAPI 3.0.3: 28 paths / 33 operations; ตรวจ schema/reference และ security declarations แล้ว |
| P0-13 | [UX gaps](Design_Baseline.md#6-ux-gaps-และ-actionerror-states) | ครบ return/conflict/expiry/upload/sync/exception/central-user/login-email states |
| เตรียม infra handoff | [Environment template](../../deploy/.env.example) | มีชื่อ config และเงื่อนไข callback/secret/sender; ค่าว่างไม่ถือว่า infra พร้อม |

## Compatibility ที่รันจริง

จบการทดสอบเวลา **2026-11-06 14:08:29 +07:00** บน Windows โดยใช้:

| Component | เวอร์ชันที่ใช้จริง |
|---|---|
| Java | Eclipse Temurin 21.0.13+9 LTS |
| Maven | 3.9.11 |
| Spring Boot | 4.0.9 — pin ใน spike parent POM |
| Flowable OSS | 8.0.0 — pin ใน spike POM |
| PostgreSQL server | 17.11 — native disposable cluster แยกจากฐานงานเดิม |

เครื่องไม่มี Java/Maven ใน PATH และ Docker daemon ใช้งานไม่ได้ในตอนตรวจ จึงดาวน์โหลด portable JDK/Maven และ PostgreSQL 17 runtime ไว้ใต้ `D:\doc_flowable\.local` โดยไม่ติดตั้ง global หรือแก้ฐาน DOCTRACKING เดิม JDK ตรวจ SHA-256 กับ release checksum, Maven ตรวจ SHA-512 และแพ็กเกจ PostgreSQL ทดสอบตรวจ integrity จาก registry ก่อนใช้

PostgreSQL 17.11 ที่ใช้ทดสอบมาจาก native package `@embedded-postgres/windows-x64@17.11.0-beta.19`; ชื่อแพ็กเกจมี beta แต่ `postgres --version` รายงาน server 17.11 ไม่ใช่การยืนยัน production packaging หรือ Docker image จนกว่าจะตรวจซ้ำกับ deployment image จริง

ใช้ cluster/database สำหรับ spike เท่านั้น, bind `127.0.0.1`, SCRAM authentication, port แยก และ schema ใหม่แบบ UUID ต่อ run; ไม่ใช้ H2 และไม่ fallback ข้าม test เมื่อ DB หาย Secrets/runtime downloads/generated reports อยู่ข้างนอก Git repo หรือถูก ignore

หลังทดสอบปิด PostgreSQL cluster ชั่วคราวอย่างถูกต้องแล้ว และตรวจว่า test port ไม่เปิดอยู่ โดยไม่หยุด PostgreSQL/บริการของโครงการอื่น

คำสั่งทดสอบจริง เมื่อเตรียม `JAVA_HOME`, `SPIKE_DB_URL`, `SPIKE_DB_USER`, `SPIKE_DB_PASSWORD` ใน environment แล้ว:

```powershell
# cwd: spikes/flowable-compatibility
& 'D:\doc_flowable\.local\p0-tools\apache-maven-3.9.11\bin\mvn.cmd' `
  --batch-mode --no-transfer-progress `
  '-Dmaven.repo.local=D:\doc_flowable\.local\m2' test
```

| Test | สิ่งที่พิสูจน์ | ผล |
|---|---|---|
| `deployCompleteAndExecutePersistedTimer` | Boot engine → deploy BPMN → user task → persisted timer → execute job → next task → end | PASS |
| `applicationInboxAndEngineCompletionRollbackTogether` | App JDBC และ engine ใช้ datasource/transaction manager เดียว; injected failure rollback inbox/task/timer และ retry commit ACK ร่วมกัน | PASS |
| `committedEventRetryDoesNotAdvanceNextTask` | Replay event ที่ commit แล้วไม่ complete task ถัดไปและมี inbox record เดียว | PASS |

Surefire: **Tests run 3, Failures 0, Errors 0, Skipped 0**, เวลา test suite 15.12 วินาที, Maven `BUILD SUCCESS` เก็บผล generated ที่ `spikes/flowable-compatibility/target/surefire-reports/` และ log ของ run นี้ที่ `D:\doc_flowable\.local\p0-maven.log`

Timer ถูก execute ผ่าน ManagementService แบบ deterministic โดยปิด async executor นี่พิสูจน์ persistence/execution ไม่ใช่เวลาจริงของ timer acquisition, worker crash restart, cluster/throughput, full A/B workflow หรือ production schema upgrade ส่วนเหล่านั้นยังต้องทดสอบใน P2/P6 การตั้ง `database-schema-update=true` ใช้เฉพาะ disposable spike schema ไม่ใช่ production policy

Login session/audit/email atomicity, Google จริง, SMTP จริง, frontend runtime และ S3 storage ยังไม่ได้รับรองจากการทดสอบนี้ เวอร์ชัน frontend ในแผนยังเป็น candidate ต้อง pin/test พร้อม skeleton P1 ไม่ตีความผล Java spike ว่า frontend dependencies ผ่านแล้ว

## OpenAPI validation

ใช้ Python 3.12.14, PyYAML 6.0.3 และ `openapi-spec-validator` 0.7.2 ตรวจ `validate_spec()` สำเร็จ พร้อมตรวจ operation IDs, CSRF และ idempotency declarations:

- 28 paths / 33 unique operations
- 19 mutations ต้อง CSRF; OAuth callback ใช้ state/nonce/PKCE ตาม flow ของตัวเอง
- 18 business/provisioning commands ต้อง Idempotency-Key; logout ยกเว้น idempotency แต่ยังต้อง CSRF
- คำสั่งต่อเรื่องมี `expected_version`, errors 403/409/422, 202/poll operation และ `process_sync_status`
- `tracking_mode`/route snapshot ไม่รับแก้รายเรื่อง และ default policy เปลี่ยนเฉพาะเรื่องใหม่
- Schema fixtures ยอมรับชื่อเรื่องอย่างเดียว/เลขหนังสืออย่างเดียว และปฏิเสธกรณีไม่มีทั้งสองค่า; route sequence เริ่ม 0 และแยก `pending_receipt_step_id` จาก current cursor
- Review แก้ sync guard ให้ใช้ `APPLIED` ตรง enum และยกเลิก reservation ของผู้อื่นต้องมี resolution grant/resource scope/reason; ชื่อ role ADMIN/Coordinator อย่างเดียวไม่พอ

รันทวน schema หลังติดตั้งเครื่องมือตรวจใน environment ที่แยกไว้:

```powershell
python -m pip install openapi-spec-validator==0.7.2 PyYAML==6.0.3
python -c "import yaml; from openapi_spec_validator import validate_spec; validate_spec(yaml.safe_load(open('contracts/openapi.yaml', encoding='utf-8'))); print('OpenAPI valid')"
```

ใน run นี้ติดตั้งเครื่องมือไว้เฉพาะ `D:\doc_flowable\.local\p0-tools\python` และเรียกด้วย bundled Python + `PYTHONPATH` ไม่มีการเพิ่ม dependency เหล่านี้ให้แอปจริง

## งานที่ยังไม่ปิดและผลต่อ phase ถัดไป

| งานค้าง | สถานะ/สิ่งที่ต้องได้ | งานที่ยังทำต่อได้ |
|---|---|---|
| P0-05 | รอยืนยัน Unit→หลาย Station และหนึ่ง active membership รวม lifecycle guards | skeleton/contract และ code ที่ไม่บังคับ cardinality ก่อนยืนยัน |
| P0-06 | รอยืนยัน TTL 10 นาที, bulk atomicity, direct-photo และ exception resolution map | single-item plumbing, guard interfaces และ isolated tests; ยังไม่เปิด policy จริง |
| P0-07 | duplicate number rule, classification/retention/SLA/RPO/RTO ยังไม่มีคำตอบ | ใช้ internal UUID; ยังไม่เพิ่ม unique number/cleanup/timer policy ที่เดาเอง |
| P0-08 | Google client/secret/hosted-domain/callback และ bootstrap MFU email ของแอปใหม่นี้ยังไม่ยืนยัน | isolated test provider และ Google config boundary; ไม่ถือว่ามี staging Google login แล้ว |
| P0-09 | ยังไม่ยืนยัน relay/sender/TLS/auth, failed-job owner/test inbox | mail sink/template/retry ใน isolated tests; ยังไม่ส่งถึงบุคคลจริง |
| P0-14 | MVP exclusions และเวลาใหม่ยังต้องยืนยันร่วม scope/infra | ใช้ baseline ในแผนเพื่อออกแบบ; ไม่สัญญาเวลาส่งมอบใหม่ |
| G0 | ยังไม่ผ่านเพราะรายการยืนยัน/เตรียมบริการข้างต้นค้าง | เริ่มเฉพาะ P1 ส่วนที่ไม่ขึ้นกับคำตอบตาม checklist |

ตรวจ presence ของ config อ้างอิงใน `D:\git\DOCTRACKING` โดยไม่แสดงค่า พบว่าชุด Google client/secret/domain/redirect ยังไม่มีค่าพร้อมใช้ แม้มี SMTP-related config บางชื่อก็ยังไม่ยืนยันว่าอนุญาตใช้กับแอปใหม่นี้ จึงไม่คัดลอก secrets, provision Google account หรือส่งอีเมลจริง

ข้อเสนอที่ส่งให้ผู้ใช้ยืนยันระหว่างทำงานคือ Unit/Station cardinality, active membership, TTL 10 นาที, bulk all-or-nothing/reselect, direct photo required, online-only และ new-document-only mode switch; **ไม่มีการใช้เวลาที่ผ่านไปแทนการยืนยัน** เมื่อมีคำตอบให้แก้ Decision Log/contract/baseline และ checklist ตามหลักฐาน

## เอกสารทางการที่ตรวจประกอบ

- [Flowable 8 dependency POM](https://github.com/flowable/flowable-engine/blob/flowable-8.0.0/modules/flowable-dependencies/pom.xml)
- [Spring Boot 4.0 system requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
- [Flowable Spring transaction integration](https://www.flowable.com/open-source/docs/bpmn/ch05-Spring)

เอกสารทางการใช้เลือก candidate; ผล integration ข้างต้นคือหลักฐานว่าชุดที่ pin นี้รันร่วมกันในสภาพแวดล้อม spike ได้ ไม่ใช่ production readiness หรือ security sign-off
