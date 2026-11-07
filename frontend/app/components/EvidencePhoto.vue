<script setup lang="ts">
import { ApiFailure } from '~/composables/useApi'
const props = defineProps<{ stationId?: string; documentId?: string; disabled?: boolean }>()
const emit = defineEmits<{ ready: [id: string]; changed: [] }>()
const api = useApi(); const inputId = useId(); const photo = ref<File>(); const preview = ref(''); const error = ref<unknown>(); const busy = ref(false); const stage = ref(''); const upload = ref<{id:string;status:string}>(); const bytesSent = ref(false); const finalized = ref(false)
let disposed = false
function choose(event: Event) {
  error.value = undefined; upload.value = undefined; bytesSent.value = false; finalized.value = false; emit('changed')
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''; photo.value = undefined
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (!['image/jpeg','image/png'].includes(file.type) || !file.size || file.size > 10*1024*1024) { error.value = new Error('เลือกรูป JPEG หรือ PNG ขนาดไม่เกิน 10 MB'); return }
  photo.value = file; preview.value = URL.createObjectURL(file)
}
async function send() {
  if (!photo.value || props.disabled) return
  busy.value = true; error.value = undefined
  try {
    if (!upload.value) {
      stage.value = 'กำลังตรวจ checksum…'
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await photo.value.arrayBuffer())), b=>b.toString(16).padStart(2,'0')).join('')
      upload.value = await api.command('/evidence/uploads', { ...(props.stationId ? {station_id:props.stationId} : {document_id:props.documentId}), content_type:photo.value.type, size_bytes:photo.value.size, checksum_sha256:hash })
    }
    const current = upload.value!
    if (!bytesSent.value) {
      stage.value = 'กำลังส่งรูป…'
      const response = await fetch(`/api/v1/evidence/uploads/${encodeURIComponent(current.id)}/content`, { method:'PUT', credentials:'same-origin',headers:{'Content-Type':photo.value.type,'X-CSRF-TOKEN':await api.token()},body:photo.value })
      if (!response.ok) throw new ApiFailure(response.status)
      bytesSent.value = true
    }
    stage.value = 'กำลังตรวจและยืนยันรูป…'
    await api.command(`/evidence/uploads/${encodeURIComponent(current.id)}/finalize`, {})
    if (!disposed) { finalized.value = true; emit('ready',current.id) }
  } catch (e) { error.value = e }
  finally { busy.value = false; stage.value = '' }
}
onBeforeUnmount(()=>{disposed=true;if(preview.value)URL.revokeObjectURL(preview.value)})
</script>
<template><div class="photo-input"><Feedback :error="error" /><div class="field"><label :for="inputId">ถ่ายหรือเลือกรูปหลักฐาน JPEG / PNG ไม่เกิน 10 MB</label><input :id="inputId" data-testid="photo-input" type="file" accept="image/jpeg,image/png" capture="environment" :disabled="busy||disabled" @change="choose" /></div><img v-if="preview" :src="preview" class="photo" alt="รูปหลักฐานที่เลือกก่อนยืนยัน" /><div class="actions"><button data-testid="photo-upload" type="button" class="button" :disabled="!photo||busy||disabled||finalized" @click="send">{{busy?stage:finalized?'ตรวจรูปสำเร็จแล้ว':'อัปโหลดและตรวจรูป'}}</button><span v-if="finalized" data-testid="photo-ready" role="status" class="status">รูปพร้อมใช้งาน</span></div><p v-if="error" class="hint">รูปยังไม่พร้อมใช้งาน กดอัปโหลดอีกครั้งสำหรับรูปเดิม การอัปโหลดไม่บันทึก Check รับหรือส่ง</p></div></template>
