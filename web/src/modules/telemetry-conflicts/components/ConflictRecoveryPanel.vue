<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSession } from '@/modules/auth/public'
import { ElAlert, ElButton, ElCard, ElCheckbox, ElEmpty, ElSkeleton, ElTable, ElTableColumn } from '@/shared/ui'
import { t } from '@/locales'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { telemetryConflictApi } from '../api/telemetry-conflicts'
import type { RecoveryTask } from '../models/telemetry-conflicts'

const props = defineProps<{ buildingId: string; ownerId: number | null }>()
const session = useSession()
const rows = ref<RecoveryTask[]>([])
const loading = ref(false)
const saving = ref(false)
const confirmed = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
let generation = 0
const canRetry = computed(() => session.user != null && (session.user.roles.includes('PLATFORM_ADMIN') || session.user.id === props.ownerId))

async function load() {
  const ticket = ++generation
  rows.value = []
  error.value = null
  loading.value = true
  try {
    const result = await telemetryConflictApi.recovery(props.buildingId)
    if (ticket === generation) rows.value = result
  } catch (reason) {
    if (ticket === generation) error.value = requestErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}

async function retry(row: RecoveryTask) {
  if (!canRetry.value || !confirmed.value || loading.value || saving.value || !rows.value.includes(row)) return
  const ticket = generation
  saving.value = true
  error.value = null
  notice.value = null
  try {
    await telemetryConflictApi.retry({ kind: row.kind, id: row.id })
    if (ticket !== generation) return
    notice.value = t('telemetryConflicts.recovery.queued')
  } catch (reason) {
    if (ticket === generation) error.value = requestErrorMessage(reason)
    return
  } finally {
    if (ticket === generation) {
      saving.value = false
      confirmed.value = false
    }
  }
  // 服务端已接受重试与后续查询失败是两件事；保留入队回执，不宣称后台已恢复。
  if (ticket === generation) await load()
}

watch(() => props.buildingId, () => {
  saving.value = false
  confirmed.value = false
  notice.value = null
  void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <ElCard shadow="never" class="recovery-panel">
    <template #header>{{ t('telemetryConflicts.recovery.title') }}</template>
    <p>{{ t('telemetryConflicts.recovery.boundary') }}</p>
    <ElAlert v-if="notice" :title="notice" type="success" :closable="false" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElButton :disabled="loading || saving" @click="load">{{ t('telemetryConflicts.actions.refresh') }}</ElButton>
    <ElCheckbox v-model="confirmed" data-testid="recovery-confirm" :disabled="!canRetry || saving">{{ t('telemetryConflicts.recovery.confirm') }}</ElCheckbox>
    <ElSkeleton v-if="loading" :rows="3" animated />
    <ElEmpty v-else-if="!rows.length" :description="error ? t('telemetryConflicts.recovery.unavailable') : t('telemetryConflicts.recovery.empty')" />
    <ElTable v-else :data="rows">
      <ElTableColumn prop="kind" :label="t('telemetryConflicts.recovery.kind')" min-width="150" />
      <ElTableColumn prop="id" :label="t('telemetryConflicts.recovery.id')" min-width="200" />
      <ElTableColumn prop="pointId" :label="t('telemetryConflicts.fields.pointId')" min-width="150" />
      <ElTableColumn prop="retryCount" :label="t('telemetryConflicts.recovery.retryCount')" min-width="100" />
      <ElTableColumn prop="errorCode" :label="t('telemetryConflicts.recovery.errorCode')" min-width="160" />
      <ElTableColumn :label="t('telemetryConflicts.recovery.retry')" min-width="130">
        <template #default="{ row }"><ElButton data-testid="retry-recovery" :disabled="!canRetry || !confirmed || saving" :loading="saving" @click="retry(row as RecoveryTask)">{{ t('telemetryConflicts.recovery.retry') }}</ElButton></template>
      </ElTableColumn>
    </ElTable>
  </ElCard>
</template>

<style scoped>
.recovery-panel :deep(.el-card__body) { display: grid; gap: var(--bec-space-group); }
p { margin: 0; color: var(--bec-color-text-secondary); }
</style>
