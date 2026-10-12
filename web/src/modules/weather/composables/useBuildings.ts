import { onMounted, ref, watch } from 'vue'
import { useSession } from '@/modules/auth/public'
import { weatherApi } from '../api/weather'
import type { Building } from '../models/weather'
import { useResource } from './useResource'

export function useBuildings() {
  const session = useSession()
  const selected = ref('')
  const resource = useResource<Building[]>()
  const storageKey = `weather-building:${session.user?.id ?? ''}`
  async function reload() {
    await resource.load('buildings', weatherApi.buildings)
    const buildings = resource.data.value
    if (!buildings) return
    let saved = ''
    try { saved = sessionStorage.getItem(storageKey) ?? '' } catch { /* 存储被禁用时仍可选建筑。 */ }
    selected.value = buildings.find(item => item.buildingId === (selected.value || saved))?.buildingId ?? buildings[0]?.buildingId ?? ''
  }
  watch(selected, value => { try { sessionStorage.setItem(storageKey, value) } catch { /* 不影响查询。 */ } })
  onMounted(reload)
  return { ...resource, selected, reload }
}
