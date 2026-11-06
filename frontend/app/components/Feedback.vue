<script setup lang="ts">
import { ApiFailure } from '~/composables/useApi'
defineProps<{ error?: unknown; message?: string }>()
</script>
<template>
  <div v-if="error" role="alert" class="notice error"><p>{{ error instanceof Error ? error.message : 'ทำรายการไม่สำเร็จ กรุณาลองใหม่' }}</p><small v-if="error instanceof ApiFailure && error.correlation">รหัสอ้างอิง: {{ error.correlation }}</small><NuxtLink v-if="error instanceof ApiFailure && error.status === 401" to="/login">เข้าสู่ระบบอีกครั้ง</NuxtLink></div>
  <div v-else-if="message" role="status" class="notice success">{{ message }}</div>
</template>
