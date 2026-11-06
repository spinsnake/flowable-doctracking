<script setup lang="ts">
import { bangkok, type DocumentRecord, type TimelineEvent, type Page, type Station } from '~/utils/types'
import { ApiFailure } from '~/composables/useApi'
const api=useApi();const route=useRoute();const id=computed(()=>encodeURIComponent(String(route.params.id)));const doc=ref<DocumentRecord>();const events=ref<TimelineEvent[]>([]);const cursor=ref<string|null>();const stations=ref<Station[]>([]);const loading=ref(false);const uploading=ref(false);const error=ref<unknown>();const uploadError=ref<unknown>();const message=ref('');const photo=ref<File>();const preview=ref('');const evidenceId=ref('');const uploadInput=ref<HTMLInputElement>();const stage=ref('');const upload=ref<{id:string;status:string}>();const uploadedBytes=ref(false)
function label(station:string){return stations.value.find(s=>s.id===station)?.name||station}
async function load(more=false){loading.value=true;error.value=undefined;try{if(!more){doc.value=await api.get<DocumentRecord>(`/documents/${id.value}`);stations.value=(await api.get<Page<Station>>('/stations')).items}const page=await api.get<Page<TimelineEvent>>(`/documents/${id.value}/timeline?limit=50${more&&cursor.value?`&cursor=${encodeURIComponent(cursor.value)}`:''}`);events.value=more?[...events.value,...page.items]:page.items;cursor.value=page.next_cursor}catch(e){error.value=e}finally{loading.value=false}}
function choose(event:Event){uploadError.value=undefined;message.value='';evidenceId.value='';upload.value=undefined;uploadedBytes.value=false;const file=(event.target as HTMLInputElement).files?.[0];if(preview.value)URL.revokeObjectURL(preview.value);photo.value=undefined;preview.value='';if(!file)return;if(!['image/jpeg','image/png'].includes(file.type)||file.size>10*1024*1024){uploadError.value=new Error('เลือกรูป JPEG หรือ PNG ขนาดไม่เกิน 10 MB');return}photo.value=file;preview.value=URL.createObjectURL(file)}
async function sendPhoto() {
  if (!photo.value || !doc.value) return
  uploading.value = true; uploadError.value = undefined
  try {
    if (!upload.value) {
      stage.value = 'กำลังตรวจ checksum…'
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await photo.value.arrayBuffer())), b => b.toString(16).padStart(2, '0')).join('')
      upload.value = await api.command<{id:string;status:string}>('/evidence/uploads', { document_id: doc.value.id, content_type: photo.value.type, size_bytes: photo.value.size, checksum_sha256: hash })
    }
    const currentUpload = upload.value
    if (!uploadedBytes.value) {
      stage.value = 'กำลังส่งรูป…'
      const response = await fetch(`/api/v1/evidence/uploads/${currentUpload.id}/content`, { method: 'PUT', credentials: 'same-origin', headers: { 'Content-Type': photo.value.type, 'X-CSRF-TOKEN': await api.token() }, body: photo.value })
      if (!response.ok) throw new ApiFailure(response.status)
      uploadedBytes.value = true
    }
    if (currentUpload.status !== 'FINALIZED') {
      stage.value = 'กำลังตรวจและยืนยันรูป…'
      await api.command(`/evidence/uploads/${currentUpload.id}/finalize`, {})
      currentUpload.status = 'FINALIZED'
    }
    stage.value = 'กำลังแนบหลักฐาน…'
    await api.command(`/documents/${doc.value.id}/evidence`, { expected_version: doc.value.version, evidence_upload_ids: [currentUpload.id] })
    evidenceId.value = currentUpload.id
    message.value = 'แนบรูปในประวัติแล้ว การแนบรูปไม่ใช่ Check รับหรือส่งหนังสือ'
    await load()
  } catch (e) { uploadError.value = e }
  finally { uploading.value = false; stage.value = '' }
}
onMounted(()=>load());onBeforeUnmount(()=>{if(preview.value)URL.revokeObjectURL(preview.value)})
</script>
<template><div class="page-head"><div><h1>{{doc?.title||doc?.document_number||'รายละเอียดหนังสือ'}}</h1><p v-if="doc?.title&&doc.document_number">{{doc.document_number}}</p></div><button type="button" class="button" :disabled="loading" @click="load()">โหลดล่าสุด</button></div><Feedback :error="error" /><UProgress v-if="loading" /><template v-if="doc"><section class="panel"><div class="actions"><span class="status">Model {{doc.tracking_mode}}</span><StatusTag :value="doc.business_state" /><StatusTag :value="doc.process_sync_status" /></div><p v-if="doc.process_sync_status!=='APPLIED'" class="hint">ข้อมูลหนังสือบันทึกแล้ว สถานะ workflow แสดงแยกจากสถานะงาน การเปิดขั้นตอนถัดไปจะพร้อมเมื่อระบบประสานสำเร็จ</p><dl class="meta"><dt>เลขอ้างอิงระบบ</dt><dd>{{doc.id}}</dd><dt>Policy / เวอร์ชันข้อมูล</dt><dd>{{doc.policy_version}} / {{doc.version}}</dd></dl><h2>เส้นทางที่ตรึงไว้</h2><ol class="route-steps"><li v-for="step in doc.route_snapshot" :key="step.step_id"><small>จุดที่ {{step.sequence+1}}{{step.step_id===doc.current_route_step_id?' · ปัจจุบัน':''}}</small>{{label(step.station_id)}}</li></ol></section><div class="split"><section class="panel"><h2>ประวัติหนังสือ</h2><div v-if="!events.length&&!loading" class="empty">ยังไม่มีเหตุการณ์ที่คุณเข้าถึงได้</div><ol class="timeline"><li v-for="event in events" :key="event.event_id"><h3>{{event.event_type}}</h3><p class="hint">{{bangkok(event.recorded_at)}} · ลำดับ {{event.sequence}}</p><p v-if="!event.actor_id" class="hint">ไม่มีข้อมูลผู้ส่งต่อที่ยืนยัน</p><a v-for="evidence in event.evidence_ids" :key="evidence" class="button actions" :href="`/api/v1/evidence/${encodeURIComponent(evidence)}/content`" target="_blank" rel="noopener">ดูหลักฐานรูป</a></li></ol><button v-if="cursor" class="button" :disabled="loading" @click="load(true)">โหลดประวัติเพิ่มเติม</button></section><section class="panel"><h2>รูปหลักฐาน</h2><p class="muted">อัปโหลดรูปส่วนตัวเพื่อให้ระบบตรวจไฟล์ก่อนใช้งาน รูปไม่เปลี่ยนสถานะหนังสือหรือ custody</p><Feedback :error="uploadError" :message="message" /><div class="field inline-reason"><label for="evidence-photo">เลือกรูป JPEG / PNG ไม่เกิน 10 MB</label><input id="evidence-photo" ref="uploadInput" type="file" accept="image/jpeg,image/png" capture="environment" :disabled="uploading" @change="choose" /></div><img v-if="preview" :src="preview" alt="รูปหลักฐานที่เลือกก่อนอัปโหลด" class="photo" /><div class="actions"><button type="button" class="button primary" :disabled="!photo||uploading||!!evidenceId" @click="sendPhoto">{{uploading?stage:evidenceId?'ยืนยันรูปแล้ว':'อัปโหลดและตรวจรูป'}}</button><a v-if="evidenceId" :href="`/api/v1/evidence/${evidenceId}/content`" target="_blank" rel="noopener" class="button">เปิดรูปที่ยืนยันแล้ว</a></div><p v-if="uploadError" class="hint">รูปยังไม่พร้อมใช้งาน กดลองใหม่สำหรับรูปเดิม ระบบยังไม่บันทึก Check รับหรือส่ง</p></section></div></template></template>
