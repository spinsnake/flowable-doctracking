<script setup lang="ts">
const { actor } = useSession()
const route = useRoute()
const denied = computed(() => Boolean(route.query.error))
const expired = computed(() => Boolean(route.query.expired))
</script>
<template><main id="main" class="login-screen"><section class="login-intro"><div class="brand"><span class="brand-mark">D</span><span>Document Tracking<small>มหาวิทยาลัยแม่ฟ้าหลวง</small></span></div><h1>ติดตามหนังสือ<br>ทุกช่วงการรับส่ง</h1><p>ทะเบียนหนังสือ เส้นทางหน่วยงาน และหลักฐานการรับส่ง อยู่ในพื้นที่ทำงานเดียวกัน</p></section><section class="login-form"><h2>เข้าสู่ระบบ</h2><p class="muted">สำหรับบุคลากรที่ได้รับสิทธิ์ใช้งาน</p><Feedback v-if="expired" :error="new Error('เซสชันหมดอายุหรือสิทธิ์ถูกเปลี่ยน กรุณาเข้าสู่ระบบใหม่')" /><Feedback v-if="denied" message="" :error="new Error('เข้าสู่ระบบไม่สำเร็จ บัญชีต้องเป็น @mfu.ac.th และได้รับสิทธิ์จากผู้ดูแล กรุณาลองใหม่หรือติดต่อผู้ดูแลระบบ')" /><NuxtLink v-if="actor" class="button primary" to="/">กลับสู่พื้นที่ทำงาน</NuxtLink><a v-else class="button primary" href="/api/v1/auth/google/start">เข้าสู่ระบบด้วย Google</a><p class="hint">ใช้บัญชี @mfu.ac.th ที่ผู้ดูแลเพิ่มไว้เท่านั้น ระบบจะแจ้งเตือนทางอีเมลเมื่อเข้าสู่ระบบสำเร็จ</p></section></main></template>
