export interface Membership { unit_id: string; role: 'UNIT_ADMIN' | 'STAFF'; is_active: boolean }
export interface Actor { id: string; email: string; display_name: string; is_active: boolean; identity_status: string; system_roles: string[]; memberships: Membership[]; station_ids: string[]; capabilities: string[]; version: number }
export interface Unit { id: string; code: string; name: string; is_active: boolean; provisioning_status: string; version: number }
export interface Station { id: string; code?: string; name: string; unit_id: string; is_active?: boolean }
export interface RouteTemplate { id: string; name: string; origin_unit_id: string; station_ids: string[]; version: number }
export interface RouteStep { step_id: string; station_id: string; sequence: number }
export interface DocumentRecord { id: string; title: string | null; document_number: string | null; origin_unit_id: string; origin_station_id: string; tracking_mode: string; policy_version: number; route_snapshot: RouteStep[]; business_state: string; version: number; current_route_step_id: string; process_sync_status: string; latest_operation_id?: string }
export interface TimelineEvent { event_id: string; event_type: string; sequence: number; recorded_at: string; actor_id: string | null; evidence_ids: string[] }
export interface Page<T> { items: T[]; next_cursor?: string | null }
export interface Reservation { id: string; owner_user_id: string; station_id: string; items: {document_id: string}[]; status: string; expires_at: string; version: number }
export interface Notification { id: string; recipient: string; status: string; attempt_count: number; version: number; last_error_code?: string; next_retry_at?: string }
export interface CommandResult { document_ids: string[]; operation_id: string; process_sync_status: string }
export const bangkok = (value: string) => new Intl.DateTimeFormat('th-TH', { timeZone: 'Asia/Bangkok', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const statuses: Record<string,string> = { REGISTERED: 'ลงทะเบียนแล้ว', PENDING: 'รอประสาน workflow', APPLIED: 'ประสานแล้ว', NOT_STARTED: 'ยังไม่เริ่ม workflow', FAILED_RETRYABLE: 'ประสานไม่สำเร็จ รอลองใหม่', ACTIVE: 'จองอยู่', CANCELLED: 'ยกเลิกแล้ว', EXPIRED: 'หมดอายุ', CONSUMED: 'รับหนังสือแล้ว', WAITING_UNIT_ADMIN: 'รอผู้ดูแลหน่วยงาน', READY: 'พร้อมใช้งาน', AWAITING_FIRST_GOOGLE_LOGIN: 'รอเข้า Google ครั้งแรก', GOOGLE_LINKED: 'ผูก Google แล้ว', PROCESSING: 'กำลังส่ง', RETRY_WAIT: 'รอลองใหม่', ACCEPTED_BY_PROVIDER: 'ผู้ให้บริการรับแล้ว', FAILED: 'ส่งไม่สำเร็จ' }
export const statusText = (value: string) => statuses[value] || value
