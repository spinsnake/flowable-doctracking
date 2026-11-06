import type { Actor } from '~/utils/types'
export function useSession() {
  const actor = useState<Actor | null>('actor', () => null)
  const checked = useState('session-checked', () => false)
  const api = useApi()
  const admin = computed(() => actor.value?.system_roles.includes('ADMIN') ?? false)
  const unitAdmin = computed(() => actor.value?.memberships.some(m => m.is_active && m.role === 'UNIT_ADMIN') ?? false)
  const courier = computed(() => actor.value?.system_roles.includes('COURIER') ?? false)
  const unitIds = computed(() => actor.value?.memberships.filter(m => m.is_active).map(m => m.unit_id) || [])
  async function load() { try { actor.value = await api.get<Actor>('/auth/me') } finally { checked.value = true } }
  return { actor, checked, admin, unitAdmin, courier, unitIds, load }
}
