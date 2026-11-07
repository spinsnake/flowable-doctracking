<script setup lang="ts">
import { bangkok, eventText, modelText, type DocumentRecord, type TimelineEvent, type Page, type Station, type CommandResult } from '~/utils/types'
const api = useApi(); const route = useRoute(); const { actor, unitIds } = useSession()
const id = computed(()=>encodeURIComponent(String(route.params.id)))
const doc = ref<DocumentRecord>(); const events = ref<TimelineEvent[]>([]); const cursor = ref<string|null>(); const stations = ref<Station[]>([])
const loading = ref(false); const saving = ref(false); const error = ref<unknown>(); const message = ref(''); const photoId = ref(''); const attached = ref(false); const operation = ref<CommandResult>()
const label = (station:string)=>stations.value.find(s=>s.id===station)?.name||station
const routePosition = (step:string)=>{const found=doc.value?.route_snapshot.find(s=>s.step_id===step);return found?found.sequence+1:'ไม่ระบุ'}
const canReady = computed(()=>{
  const d = doc.value
  if (!d || d.tracking_mode !== 'MODEL_B') return false
  if (d.business_state === 'WAIT_ORIGIN_READY') return unitIds.value.includes(d.origin_unit_id)
  const station = stations.value.find(s=>s.id===d.route_snapshot.find(r=>r.step_id===d.current_route_step_id)?.station_id)
  return d.business_state==='UNIT_PROCESSING' && !!station && unitIds.value.includes(station.unit_id) && actor.value?.station_ids.includes(station.id)
})
const canReturn = computed(()=>doc.value?.tracking_mode==='MODEL_B' && doc.value.business_state==='PENDING_ORIGIN_RETURN_RECEIPT' && unitIds.value.includes(doc.value.origin_unit_id))
async function load(more=false) {
  loading.value=true; error.value=undefined
  try {
    if (!more) { doc.value=await api.get<DocumentRecord>(`/documents/${id.value}`); stations.value=(await api.get<Page<Station>>('/stations')).items }
    const page=await api.get<Page<TimelineEvent>>(`/documents/${id.value}/timeline?limit=50${more&&cursor.value?`&cursor=${encodeURIComponent(cursor.value)}`:''}`)
    events.value=more?[...events.value,...page.items]:page.items; cursor.value=page.next_cursor
  } catch (e) { error.value=e } finally { loading.value=false }
}
async function workflow(action:'ready'|'confirm-return') {
  const d=doc.value; if(!d)return
  saving.value=true;error.value=undefined;message.value=''
  try {
    operation.value=await api.command<CommandResult>(`/documents/${d.id}/${action}`,{route_step_id:action==='ready'?d.current_route_step_id:d.pending_receipt_step_id,expected_version:d.version})
    message.value=action==='ready'?'บันทึกพร้อมจัดส่งแล้ว รอ workflow เปิดคิว Courier':'บันทึกยืนยันรับคืนแล้ว รอ workflow ประสาน'
    await load()
  } catch(e){error.value=e} finally {saving.value=false}
}
async function attach() {
  if(!doc.value||!photoId.value)return
  saving.value=true;error.value=undefined
  try { await api.command(`/documents/${doc.value.id}/evidence`,{expected_version:doc.value.version,evidence_upload_ids:[photoId.value]}); attached.value=true; message.value='แนบรูปในประวัติแล้ว การแนบรูปไม่ใช่ Check รับหรือส่งหนังสือ'; await load() }
  catch(e){error.value=e} finally {saving.value=false}
}
onMounted(()=>load())
</script>
<template>
  <div class="page-head"><div><h1>{{doc?.title||doc?.document_number||'รายละเอียดหนังสือ'}}</h1><p v-if="doc?.title&&doc.document_number">{{doc.document_number}}</p></div><button type="button" class="button" :disabled="loading||saving" @click="load()">โหลดล่าสุด</button></div>
  <Feedback :error="error" :message="message" />
  <OperationStatus v-if="operation || (doc && doc.process_sync_status!=='APPLIED')" :operation-id="operation?.operation_id || doc?.latest_operation_id" :sync-status="operation?.process_sync_status || doc?.process_sync_status" @applied="operation=undefined;load()" />
  <UProgress v-if="loading" />
  <template v-if="doc">
    <section class="panel">
      <div class="actions"><span class="status">{{modelText(doc.tracking_mode)}}</span><StatusTag :value="doc.business_state" /><StatusTag :value="doc.process_sync_status" /></div>
      <p v-if="doc.process_sync_status!=='APPLIED'" class="hint">ข้อมูลหนังสือบันทึกแล้ว ขั้นตอนถัดไปจะพร้อมเมื่อ workflow ประสานสำเร็จ</p>
      <p v-if="doc.business_state==='WAIT_ORIGIN_READY'" class="hint">หนังสือนี้ยังไม่เข้าคิว Courier ต้นทางต้องตรวจหนังสือและกดพร้อมจัดส่งก่อน</p>
      <div v-if="canReady||canReturn" class="actions">
        <button v-if="canReady" data-testid="document-ready" class="button primary" :disabled="saving||doc.process_sync_status!=='APPLIED'||!!operation" @click="workflow('ready')">{{saving?'กำลังบันทึก…':'พร้อมจัดส่ง'}}</button>
        <button v-if="canReturn" data-testid="document-return" class="button primary" :disabled="saving||doc.process_sync_status!=='APPLIED'||!!operation" @click="workflow('confirm-return')">{{saving?'กำลังบันทึก…':'ยืนยันรับคืนต้นทาง'}}</button>
      </div>
      <p v-if="doc.tracking_mode==='MODEL_A'" class="hint">Model A ใช้หลักฐานการพบและรับส่งของ Courier ไม่มี Unit READY หรือการยืนยันรับจากหน่วยงาน<span v-if="doc.business_state==='COURIER_TRACKING_COMPLETE'"> การติดตามการนำส่งครบแล้ว ไม่ได้ยืนยันว่าหน่วยงานรับคืน</span></p><p v-if="doc.business_state==='EXCEPTION'" class="notice sync-notice">พบตำแหน่งที่ต้องตรวจสอบ งานถูกพักไว้ก่อนรับส่งต่อ กรุณาติดต่อผู้มีสิทธิ์แก้ไขข้อยกเว้น</p><dl class="meta"><dt>เลขอ้างอิงระบบ</dt><dd>{{doc.id}}</dd><dt>Policy / เวอร์ชันข้อมูล</dt><dd>{{doc.policy_version}} / {{doc.version}}</dd></dl>
      <h2>เส้นทางที่ตรึงไว้</h2><ol class="route-steps"><li v-for="step in doc.route_snapshot" :key="step.step_id"><small>จุดที่ {{step.sequence+1}}{{step.step_id===doc.current_route_step_id?' · ปัจจุบัน':''}}</small>{{label(step.station_id)}}</li></ol>
    </section>
    <div class="split">
      <section class="panel"><h2>ประวัติหนังสือ</h2><div v-if="!events.length&&!loading" class="empty">ยังไม่มีเหตุการณ์ที่คุณเข้าถึงได้</div>
        <ol class="timeline"><li v-for="event in events" :key="event.event_id"><h3>{{eventText(event.event_type)}}</h3><p class="hint">{{bangkok(event.recorded_at)}} · ลำดับ {{event.sequence}}</p><p class="hint">ผู้บันทึก: {{event.event_type==='UNOBSERVED_TRANSFER'?'ไม่ทราบผู้นำส่งในช่วงที่ไม่มีหลักฐาน':event.actor_display_name || event.actor_id || 'ระบบ'}}</p><p v-if="event.observation?.reason" class="hint">เหตุผล: {{event.observation.reason}}</p><p v-if="event.observation?.observed_station_id" class="hint">พบที่: {{label(event.observation.observed_station_id)}}<span v-if="event.observation.proposed_route_step_id"> · จุดที่ {{routePosition(event.observation.proposed_route_step_id)}}</span></p><p v-if="event.event_type==='UNOBSERVED_TRANSFER'" class="hint">เวลาที่แสดงคือเวลาบันทึกช่องว่าง ไม่ทราบเวลารับหรือส่งของช่วงนี้</p><a v-for="evidence in event.evidence_ids" :key="evidence" class="button actions" :href="`/api/v1/evidence/${encodeURIComponent(evidence)}/content`" target="_blank" rel="noopener">ดูหลักฐานรูป</a></li></ol>
        <button v-if="cursor" class="button" :disabled="loading" @click="load(true)">โหลดประวัติเพิ่มเติม</button>
      </section>
      <section class="panel"><h2>รูปหลักฐาน</h2><p class="muted">รูปเป็นข้อมูลส่วนตัว ต้องผ่านการตรวจไฟล์ก่อนใช้งาน การแนบรูปในประวัติไม่เปลี่ยนสถานะหนังสือหรือผู้ถือครอง</p>
        <EvidencePhoto :key="doc.id" :document-id="doc.id" :disabled="saving||!!operation" @ready="photoId=$event" @changed="photoId='';attached=false" />
        <div class="actions"><button type="button" class="button primary" :disabled="!photoId||saving||!!operation||attached" @click="attach">{{saving?'กำลังแนบหลักฐาน…':attached?'แนบหลักฐานแล้ว':'แนบรูปในประวัติ'}}</button><a v-if="attached" :href="`/api/v1/evidence/${encodeURIComponent(photoId)}/content`" class="button" target="_blank" rel="noopener">เปิดรูปที่แนบแล้ว</a></div>
      </section>
    </div>
  </template>
</template>
