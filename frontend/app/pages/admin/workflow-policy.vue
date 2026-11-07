<script setup lang="ts">
import { bangkok, modelText, type WorkflowPolicy } from '~/utils/types'
import { ApiFailure } from '~/composables/useApi'
const api=useApi();const {admin}=useSession();const policy=ref<WorkflowPolicy>();const selected=ref('');const reason=ref('');const loading=ref(false);const saving=ref(false);const conflicted=ref(false);const error=ref<unknown>();const message=ref('')
async function load(){if(!admin.value)return;loading.value=true;error.value=undefined;try{policy.value=await api.get<WorkflowPolicy>('/admin/workflow-policy');selected.value=policy.value.default_tracking_mode;conflicted.value=false}catch(e){error.value=e}finally{loading.value=false}}
async function save(){if(!policy.value||!reason.value.trim()||selected.value===policy.value.default_tracking_mode||conflicted.value)return;saving.value=true;error.value=undefined;message.value='';try{const previous=policy.value.default_tracking_mode;policy.value=await api.command<WorkflowPolicy>('/admin/workflow-policy',{default_tracking_mode:selected.value,expected_version:policy.value.version,reason:reason.value.trim()},'PUT');message.value=`เปลี่ยนค่าเริ่มต้น ${modelText(previous)} → ${modelText(policy.value.default_tracking_mode)} แล้ว ใช้เฉพาะหนังสือที่สร้างหลังการเปลี่ยนแปลง`;reason.value=''}catch(e){error.value=e;conflicted.value=e instanceof ApiFailure&&e.status===409}finally{saving.value=false}}
onMounted(load)
</script>
<template>
  <div class="page-head"><div><h1>รูปแบบติดตามเริ่มต้น</h1><p>กำหนด Model สำหรับหนังสือใหม่ทั้งระบบ หนังสือที่ลงทะเบียนแล้วคง Model, policy และสำเนาเส้นทางเดิม</p></div><button class="button" type="button" :disabled="loading||saving" @click="load">โหลดล่าสุด</button></div>
  <Feedback v-if="!admin" :error="new Error('เฉพาะผู้ดูแลส่วนกลางเท่านั้น')" />
  <template v-else><Feedback :error="error" :message="message" /><UProgress v-if="loading" />
    <form v-if="policy" class="panel" @submit.prevent="save"><h2>ค่าเริ่มต้นปัจจุบัน: {{modelText(policy.default_tracking_mode)}}</h2><dl class="meta"><dt>Policy version</dt><dd data-testid="policy-version">{{policy.version}}</dd><dt v-if="policy.changed_at">เปลี่ยนเมื่อ</dt><dd v-if="policy.changed_at">{{bangkok(policy.changed_at)}}</dd><dt v-if="policy.changed_by">ผู้เปลี่ยน</dt><dd v-if="policy.changed_by" class="break">{{policy.changed_by}}</dd><dt v-if="policy.change_reason">เหตุผลล่าสุด</dt><dd v-if="policy.change_reason">{{policy.change_reason}}</dd></dl>
      <div class="field"><label for="policy-mode">ค่าเริ่มต้นใหม่</label><select id="policy-mode" data-testid="policy-mode" v-model="selected" required :disabled="saving"><option value="MODEL_A">Model A · ติดตามจากหลักฐาน Courier</option><option value="MODEL_B">Model B · หน่วยงานยืนยันรับและพร้อมจัดส่ง</option></select></div>
      <p class="hint">Model A ไม่มี Unit READY หรือการยืนยันรับจากหน่วยงาน จบเป็น “ติดตามการนำส่งครบแล้ว” ส่วน Model B จบเมื่อหน่วยงานต้นทางยืนยันรับคืน</p>
      <div class="field inline-reason"><label for="policy-reason">เหตุผลการเปลี่ยนค่าเริ่มต้น</label><textarea id="policy-reason" data-testid="policy-reason" v-model="reason" required maxlength="1000" :disabled="saving" /></div>
      <p v-if="selected!==policy.default_tracking_mode" class="notice sync-notice">กำลังเปลี่ยน {{modelText(policy.default_tracking_mode)}} → {{modelText(selected)}} การเปลี่ยนนี้ไม่ย้าย workflow หรือแก้ประวัติและรูปของหนังสือเดิม</p><p v-if="conflicted" class="hint">ค่าถูกเปลี่ยนหรือเวอร์ชันไม่ตรง โปรดโหลดล่าสุดและตรวจค่าใหม่ก่อนยืนยัน เหตุผลที่กรอกยังคงอยู่</p>
      <button data-testid="policy-save" class="button primary" :disabled="saving||loading||conflicted||!reason.trim()||selected===policy.default_tracking_mode">{{saving?'กำลังบันทึก…':'บันทึกค่าเริ่มต้นใหม่'}}</button>
    </form>
  </template>
</template>
