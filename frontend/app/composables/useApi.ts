import { commandKey, errorMessage } from '~/utils/commands'
const pending = new Map<string, string>()
export class ApiFailure extends Error { constructor(public status: number, public code?: string, public correlation?: string) { super(errorMessage(status, code)) } }
export function useApi() {
  const csrf = useState<string | null>('csrf', () => null)
  async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    let response: Response
    try { response = await fetch(`/api/v1${path}`, { credentials: 'same-origin', cache: 'no-store', ...options }) }
    catch { throw new ApiFailure(0) }
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}))
      if (response.status === 401) { useState('actor').value = null; csrf.value = null; }
      throw new ApiFailure(response.status, payload.code, payload.correlation_id)
    }
    return response.status === 204 ? undefined as T : await response.json()
  }
  async function token() {
    if (!csrf.value) csrf.value = (await request<{ token: string }>('/auth/csrf')).token
    return csrf.value!
  }
  async function command<T>(path: string, body: unknown, method = 'POST'): Promise<T> {
    const actor = useState<{id: string} | null>('actor').value
    const operation = commandKey(`${actor?.id}:${method}:${path}`, body, pending)
    const result = await request<T>(path, { method, headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': await token(), 'Idempotency-Key': operation.key }, body: JSON.stringify(body) })
    pending.delete(operation.fingerprint)
    return result
  }
  async function logout() {
    await request('/auth/logout', { method: 'POST', headers: { 'X-CSRF-TOKEN': await token() } })
    useState('actor').value = null; csrf.value = null; pending.clear(); await navigateTo('/login')
  }
  return { get: request, command, token, logout }
}
