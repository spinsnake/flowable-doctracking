<script setup lang="ts">
import { statusText } from '~/utils/types'
defineProps<{ value: string }>()
</script>
<template><span class="status" :class="{ warn: ['PENDING','FAILED_RETRYABLE','RETRY_WAIT','WAITING_UNIT_ADMIN','FAILED','EXCEPTION','DROPPED_OFF_UNCONFIRMED'].includes(value) }">{{ statusText(value) }}</span></template>
