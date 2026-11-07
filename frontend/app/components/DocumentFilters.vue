<script setup lang="ts">
import { statusText } from '~/utils/types'
defineProps<{states:string[]}>()
const mode=defineModel<string>('mode',{default:''});const state=defineModel<string>('state',{default:''})
const prefix=useId()
</script>
<template>
  <div class="form-grid">
    <div class="field">
      <label :for="`${prefix}-mode`">กรองรูปแบบในรายการที่โหลดแล้ว</label>
      <select :id="`${prefix}-mode`" v-model="mode" data-testid="filter-mode">
        <option value="">ทุกรูปแบบ</option>
        <option value="MODEL_A">Model A</option>
        <option value="MODEL_B">Model B</option>
      </select>
    </div>
    <div class="field">
      <label :for="`${prefix}-state`">กรองสถานะในรายการที่โหลดแล้ว</label>
      <select :id="`${prefix}-state`" v-model="state" data-testid="filter-state">
        <option value="">ทุกสถานะ</option>
        <option v-for="value in states" :key="value" :value="value">{{statusText(value)}}</option>
      </select>
    </div>
  </div>
</template>
