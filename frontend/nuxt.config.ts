export default defineNuxtConfig({
  compatibilityDate: '2026-11-06',
  ssr: false,
  modules: ['@nuxt/ui'],
  css: ['~/assets/css/main.css'],
  devtools: { enabled: false },
  ui: { fonts: false },
  colorMode: { preference: 'light', fallback: 'light' },
  app: { head: { htmlAttrs: { lang: 'th' }, title: 'MFU · ติดตามหนังสือ', meta: [{ name: 'description', content: 'ระบบติดตามการรับส่งหนังสือ มหาวิทยาลัยแม่ฟ้าหลวง' }] } },
  nitro: { preset: 'static' },
  devServer: { port: 3000 },
  vite: { server: { proxy: { '/api': { target: process.env.API_PROXY_TARGET || 'http://localhost:8080', changeOrigin: false } } } }
})
