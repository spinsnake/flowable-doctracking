<script setup lang="ts">
import type { CommandResult } from '~/utils/types'
const props = defineProps<{ operationId?: string | null; syncStatus?: string }>()
const emit = defineEmits<{ applied: [] }>()
const api = useApi()
const status = ref(props.syncStatus || 'PENDING'); const error = ref<unknown>(); const checking = ref(false); const exhausted = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined; let generation = 0; let checks = 0
async function check(run: number) {
  if (!props.operationId || run !== generation) return
  checking.value = true; error.value = undefined
  try {
    const result = await api.get<CommandResult>(`/operations/${encodeURIComponent(props.operationId)}`)
    if (run !== generation) return
    status.value = result.process_sync_status; checks++
    if (status.value === 'APPLIED') emit('applied')
    else if (status.value === 'PENDING' && checks < 20) timer = setTimeout(() => check(run), 1500)
    else exhausted.value = status.value === 'PENDING'
  } catch (e) { if (run === generation) error.value = e }
  finally { if (run === generation) checking.value = false }
}
function reload() { clearTimeout(timer); generation++; checks = 0; exhausted.value = false; check(generation) }
watch(() => [props.operationId, props.syncStatus], () => { clearTimeout(timer); generation++; status.value = props.syncStatus || 'PENDING'; checks = 0; exhausted.value = false; if (props.operationId && status.value !== 'APPLIED') check(generation) }, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>
<template><div v-if="status !== 'APPLIED'" class="notice sync-notice" role="status" aria-live="polite"><div class="actions"><StatusTag :value="status" /><span>บันทึกคำสั่งแล้ว โปรดรอระบบเปิดขั้นตอนถัดไป</span></div><p v-if="status === 'FAILED_RETRYABLE'" class="hint">การประสานยังไม่สำเร็จ ระบบจะลองตามกำหนด หากค้างนานให้ติดต่อผู้ดูแล พร้อมเลขอ้างอิง {{operationId}} ไม่ต้องส่งคำสั่งเดิมซ้ำ</p><p v-if="exhausted" class="hint">ระบบยังดำเนินการอยู่ กดตรวจสถานะอีกครั้งภายหลัง</p><Feedback :error="error" /><button v-if="operationId" type="button" class="button actions" :disabled="checking" @click="reload">{{checking?'กำลังตรวจสถานะ…':'ตรวจสถานะอีกครั้ง'}}</button></div></template>
