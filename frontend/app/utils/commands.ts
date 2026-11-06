// Keys contain no business payload. Reuse the same key after uncertain network outcomes.
export function commandKey(scope: string, body: unknown, memory: Map<string, string>, uuid = () => crypto.randomUUID()) {
  const fingerprint = JSON.stringify([scope, body])
  if (!memory.has(fingerprint)) memory.set(fingerprint, uuid())
  return { fingerprint, key: memory.get(fingerprint)! }
}
export function errorMessage(status: number, code?: string) {
  if (status === 401) return 'เซสชันหมดอายุ กรุณาเข้าสู่ระบบอีกครั้ง'
  if (status === 403) return 'บัญชีนี้ไม่มีสิทธิ์ทำรายการ กรุณาติดต่อผู้ดูแลระบบ'
  if (status === 404) return 'ไม่พบข้อมูล หรือบัญชีนี้ไม่มีสิทธิ์เข้าถึง'
  if (status === 409) return 'ข้อมูลเปลี่ยนแปลงหรือมีรายการซ้ำ กรุณาโหลดข้อมูลล่าสุดก่อนลองใหม่'
  if (status === 429) return 'ทำรายการถี่เกินไป กรุณารอสักครู่แล้วลองใหม่'
  if (status === 422) return `ข้อมูลไม่ผ่านเงื่อนไข${code ? ` (${code})` : ''} กรุณาตรวจข้อมูลแล้วลองใหม่`
  if (status === 501 || status === 503) return 'ฟังก์ชันนี้ยังไม่พร้อมใช้งาน กรุณาติดต่อผู้ดูแลระบบ'
  return 'ติดต่อระบบไม่สำเร็จ กรุณาลองใหม่ รายการเดิมจะใช้รหัสคำสั่งเดิมเพื่อป้องกันการบันทึกซ้ำ'
}
