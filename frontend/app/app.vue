<script setup lang="ts">
import { ApiFailure } from '~/composables/useApi'
const route = useRoute()
const { actor, admin, unitAdmin, courier, load } = useSession()
const error = ref<unknown>()
const loading = ref(true)
const menuOpen = ref(false)
const publicPage = computed(() => route.path === '/login')
onMounted(async () => { try { await load() } catch (e) { if (!(e instanceof ApiFailure && e.status === 401)) error.value = e } finally { loading.value = false; if (!actor.value && !publicPage.value && !error.value) await navigateTo('/login') } })
watch(() => route.path, () => { menuOpen.value = false; if (!loading.value && !actor.value && !publicPage.value) navigateTo('/login') })
watch(actor, value => { if (!loading.value && !value && !publicPage.value) navigateTo('/login?expired=1') })
async function signOut() { try { await useApi().logout() } catch (e) { error.value = e } }
</script>
<template>
  <UApp><a class="skip-link" href="#main">ข้ามไปเนื้อหา</a>
    <div v-if="loading" class="initial-state" role="status"><UProgress class="loading-line" />กำลังตรวจสอบสิทธิ์…</div>
    <NuxtPage v-else-if="publicPage" />
    <div v-else-if="error && !actor" class="initial-state"><h1>เข้าใช้งานระบบ</h1><Feedback :error="error" /><UButton @click="reloadNuxtApp()">ลองเชื่อมต่ออีกครั้ง</UButton></div>
    <div v-else-if="actor" class="workspace">
      <aside class="sidebar" :class="{ open: menuOpen }"><NuxtLink class="brand" to="/"><span class="brand-mark">D</span><span>Document Tracking<small>มหาวิทยาลัยแม่ฟ้าหลวง</small></span></NuxtLink>
        <nav aria-label="เมนูหลัก">
          <NuxtLink to="/">ภาพรวม</NuxtLink><NuxtLink v-if="actor.memberships.some(m => m.is_active)" to="/documents">หนังสือของหน่วยงาน</NuxtLink><NuxtLink v-if="actor.memberships.some(m => m.is_active)" to="/documents/new">ลงทะเบียนหนังสือ</NuxtLink><NuxtLink v-if="actor.memberships.some(m => m.is_active)" to="/templates">Route Template</NuxtLink>
          <NuxtLink v-if="actor.station_ids.length" to="/worklist">งานประจำ Station</NuxtLink><NuxtLink v-if="courier" to="/reservations">การจองของฉัน</NuxtLink>
          <NuxtLink v-if="admin" to="/admin/units">จัดการหน่วยงาน</NuxtLink><NuxtLink v-if="admin || unitAdmin" to="/admin/users">จัดการผู้ใช้</NuxtLink><NuxtLink v-if="admin" to="/admin/notifications">อีเมลแจ้งเตือน</NuxtLink><NuxtLink to="/profile">บัญชีของฉัน</NuxtLink>
        </nav><div class="sidebar-foot">Google Workspace · MFU<br>สิทธิ์ตามบัญชีและหน่วยงาน</div>
      </aside>
      <div class="workspace-body"><header class="topbar"><button type="button" class="menu-toggle" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">เมนู</button><span class="workspace-name">พื้นที่ทำงาน · Document Tracking</span><div class="account"><NuxtLink to="/profile">{{ actor.display_name }}</NuxtLink><button type="button" @click="signOut">ออกจากระบบ</button></div></header>
        <main id="main" tabindex="-1"><Feedback :error="error" /><NuxtPage /></main><footer>Document Tracking · MFU <span>เวลาแสดงตามประเทศไทย (UTC+7)</span></footer>
      </div>
    </div>
  </UApp>
</template>
