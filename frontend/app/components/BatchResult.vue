<script setup lang="ts">
import type { CommandResult } from '~/utils/types'
defineProps<{ result?: CommandResult }>()
const emit=defineEmits<{ refresh:[] }>()
const labels:Record<string,string>={
  ALREADY_RESERVED:'มีผู้จองไปแล้ว', RESERVATION_CONFLICT:'มีผู้จองไปแล้ว', ACTIVE_TRANSPORT:'มีผู้รับหรือจองไปแล้ว',
  TRANSPORT_CONFLICT:'มีผู้รับหรือจองไปแล้ว', STALE_VERSION:'ข้อมูลเปลี่ยนแล้ว อาจมีผู้รับหรือจองไปแล้ว โปรดโหลดล่าสุด',
  CUSTODY_CONFLICT:'มีผู้รับไปแล้ว หรืออยู่ระหว่างนำส่ง', WORKFLOW_SYNC_PENDING:'รอ workflow ประสาน',
  NOT_FOUND:'ไม่พบรายการหรือไม่มีสิทธิ์', FORBIDDEN:'ไม่มีสิทธิ์ดำเนินการ', WORKFLOW_PENDING:'รอ workflow ประสาน',
  INVALID_STATE:'สถานะเปลี่ยนแล้ว โปรดโหลดล่าสุด'
}
const reason=(code:string,message:string)=>labels[code]||message
</script>
<template><section v-if="result" class="notice" aria-live="polite" data-testid="batch-result"><h2>สำเร็จ {{(result.accepted||result.document_ids).length}} เรื่อง · ไม่สำเร็จ {{result.rejected?.length||0}} เรื่อง</h2><p>รายการที่สำเร็จบันทึกแล้ว ไม่ต้องส่งซ้ำ</p><ul v-if="result.rejected?.length"><li v-for="item in result.rejected" :key="item.document_id" class="break">หนังสือ #{{item.document_id.slice(-8)}}: {{reason(item.code,item.message)}}</li></ul><button v-if="result.rejected?.length" type="button" class="button" @click="emit('refresh')">โหลดล่าสุดเพื่อเลือกเรื่องที่ไม่สำเร็จใหม่</button></section></template>
