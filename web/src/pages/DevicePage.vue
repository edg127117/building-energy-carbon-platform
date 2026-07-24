<template>
  <AdminLayout>
    <div class="mx-auto max-w-[1200px]">
      <div class="mb-5 flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div>
          <div class="text-lg font-semibold text-zinc-50">设备台账</div>
          <div class="mt-1 text-xs tracking-[0.18em] text-zinc-400">MySQL 台账 · RBAC 鉴权 · 控制抽屉骨架</div>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <a-button @click="refresh" :loading="loading">刷新</a-button>
          <a-button v-if="auth.isAdmin" type="primary" @click="openAdd">新增设备</a-button>
        </div>
      </div>

      <div class="rounded-3xl border border-white/10 bg-white/[0.04] p-5 backdrop-blur-xl">
        <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
          <a-input v-model:value="query.keyword" placeholder="按设备ID/名称搜索" allow-clear />
          <a-select
            v-model:value="query.status"
            placeholder="状态筛选"
            allow-clear
            :options="[
              { label: '在线', value: 1 },
              { label: '离线', value: 0 },
              { label: '故障', value: 2 },
            ]"
          />
          <div class="flex items-center justify-end">
            <div class="text-xs text-zinc-400">共 {{ deviceStore.totalCount }} 台设备</div>
          </div>
        </div>

        <div class="mt-5">
          <a-table
            :dataSource="deviceStore.devices"
            :columns="columns"
            :rowKey="(r:any) => r.id"
            :pagination="{
              current: query.page,
              pageSize: query.pageSize,
              total: deviceStore.totalCount,
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              onChange: (p: number, ps: number) => { query.page = p; query.pageSize = ps; fetchPage() },
            }"
            :loading="loading"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <StatusBadge :status="record.status" />
              </template>
              <template v-else-if="column.key === 'actions'">
                <div class="flex flex-wrap gap-2">
                  <!-- TODO: 下期恢复 指令下发功能 -->
                  <!-- <a-button size="small" @click="openControl(record)">下发指令</a-button> -->
                  <a-popconfirm
                    v-if="auth.isAdmin"
                    title="确认删除该设备？"
                    ok-text="删除"
                    cancel-text="取消"
                    @confirm="onDelete(record.id)"
                  >
                    <a-button size="small" danger>删除</a-button>
                  </a-popconfirm>
                </div>
              </template>
            </template>
          </a-table>
        </div>
      </div>
    </div>

    <a-modal
      v-model:open="addModalOpen"
      title="新增设备"
      ok-text="提交"
      cancel-text="取消"
      :confirmLoading="addSubmitting"
      @ok="submitAdd"
    >
      <a-form layout="vertical" :model="addForm">
        <a-form-item label="设备ID" name="deviceId" :rules="[{ required: true, message: '请输入设备ID' }]">
          <a-input v-model:value="addForm.deviceId" placeholder="例如：meter-001" />
        </a-form-item>
        <a-form-item label="设备名称" name="deviceName" :rules="[{ required: true, message: '请输入设备名称' }]">
          <a-input v-model:value="addForm.deviceName" placeholder="例如：1号车间总电表" />
        </a-form-item>
        <a-form-item label="能源类型" name="deviceType" :rules="[{ required: true, message: '请选择类型' }]">
          <a-select
            v-model:value="addForm.deviceType"
            :options="[
              { label: '电表', value: 1 },
              { label: '水表', value: 2 },
            ]"
          />
        </a-form-item>
        <a-form-item label="安装位置" name="location">
          <a-input v-model:value="addForm.location" placeholder="例如：1栋配电房" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- TODO: 下期恢复 指令下发抽屉 -->
    <!--
    <a-drawer v-model:open="drawerOpen" title="下发指令" placement="right" width="420">
      <div v-if="drawerDevice" class="space-y-4">
        <div class="rounded-2xl border border-white/10 bg-black/20 p-4">
          <div class="text-sm font-semibold text-zinc-100">{{ drawerDevice.deviceName || drawerDevice.deviceId }}</div>
          <div class="mt-1 text-xs text-zinc-400">deviceId：{{ drawerDevice.deviceId }}</div>
        </div>

        <a-form layout="vertical" :model="cmdForm">
          <a-form-item label="指令类型">
            <a-select
              v-model:value="cmdForm.commandType"
              :options="[
                { label: '开关控制（Demo）', value: 1 },
                { label: '阈值设定（Demo）', value: 2 },
              ]"
            />
          </a-form-item>

          <a-form-item v-if="cmdForm.commandType === 1" label="开关">
            <a-segmented v-model:value="cmdForm.switchValue" :options="switchOptions" block />
          </a-form-item>

          <a-form-item v-if="cmdForm.commandType === 2" label="功率阈值(kW)">
            <a-input-number v-model:value="cmdForm.threshold" :min="0" :max="99999" class="w-full" />
          </a-form-item>
        </a-form>

        <div class="rounded-2xl border border-white/10 bg-black/20 p-4 text-xs leading-6 text-zinc-300">
          <div>API：POST /api/control/issue</div>
          <div>等待 WebSocket reply：/api/ws/dashboard</div>
          <div class="mt-2 text-zinc-500">注：Demo 阶段只需要链路跑通，参数结构后续可对接硬件协议。</div>
        </div>

        <a-button type="primary" block size="large" :loading="cmdSubmitting" @click="submitCommand">执行</a-button>
      </div>
    </a-drawer>
    -->
  </AdminLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { message, notification } from 'ant-design-vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useAuthStore } from '@/store/auth'
import { useDeviceStore } from '@/store/device'
import { addDeviceApi, deleteDeviceApi, type Device } from '@/api/device'
import { issueCommandApi } from '@/api/control'
import { DashboardWsClient } from '@/utils/websocket'
import type { WsDeviceMessage } from '@/types/ws'

const auth = useAuthStore()
const deviceStore = useDeviceStore()

const loading = ref(false)

const query = reactive<{ page: number; pageSize: number; keyword: string; status: number | null }>({
  page: 1,
  pageSize: 20,
  keyword: '',
  status: null,
})

const columns = [
  { title: '设备ID', dataIndex: 'deviceId', key: 'deviceId' },
  { title: '名称', dataIndex: 'deviceName', key: 'deviceName' },
  { title: '类型', dataIndex: 'deviceType', key: 'deviceType' },
  { title: '位置', dataIndex: 'location', key: 'location' },
  { title: '状态', key: 'status' },
  { title: '操作', key: 'actions' },
]

async function fetchPage() {
  loading.value = true
  try {
    await deviceStore.fetchList({
      page: query.page,
      pageSize: query.pageSize,
      deviceId: query.keyword || undefined,
      status: query.status,
    })
  } finally {
    loading.value = false
  }
}

async function refresh() {
  query.page = 1
  await fetchPage()
}

async function onDelete(id: number) {
  try {
    await deleteDeviceApi(id)
    message.success('删除成功')
    await refresh()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

const addModalOpen = ref(false)
const addSubmitting = ref(false)
const addForm = reactive({
  deviceId: '',
  deviceName: '',
  deviceType: 1,
  location: '',
})

function openAdd() {
  addModalOpen.value = true
}

async function submitAdd() {
  addSubmitting.value = true
  try {
    await addDeviceApi({
      deviceId: addForm.deviceId,
      deviceName: addForm.deviceName,
      deviceType: addForm.deviceType,
      location: addForm.location || undefined,
    })
    message.success('新增成功')
    addModalOpen.value = false
    addForm.deviceId = ''
    addForm.deviceName = ''
    addForm.deviceType = 1
    addForm.location = ''
    await refresh()
  } catch (e: any) {
    message.error(e?.message || '新增失败')
  } finally {
    addSubmitting.value = false
  }
}

const drawerOpen = ref(false)
const drawerDevice = ref<Device | null>(null)
const cmdSubmitting = ref(false)
const pendingCommandId = ref<string | null>(null)

const cmdForm = reactive({
  commandType: 1,
  switchValue: 'on',
  threshold: 10,
})

const switchOptions = computed(() => {
  if (drawerDevice.value?.status !== 1) {
    return [{ label: '开启（设备离线，仅可上线）', value: 'on' }]
  }
  return [
    { label: '开启', value: 'on' },
    { label: '关闭', value: 'off' },
  ]
})

function openControl(d: Device) {
  drawerDevice.value = d
  drawerOpen.value = true
  cmdSubmitting.value = false
  pendingCommandId.value = null
  // 离线设备默认选"开启"，只能上线
  if (d.status !== 1) {
    cmdForm.switchValue = 'on'
    cmdForm.commandType = 1
  }
}

function parseCommandId(text: string) {
  const m = text.match(/追踪号[:：]\\s*([A-Za-z0-9\\-]+)/)
  return m?.[1] ?? null
}

async function submitCommand() {
  if (!drawerDevice.value) return
  cmdSubmitting.value = true
  try {
    const params: Record<string, unknown> = {}
    if (cmdForm.commandType === 1) params.switch = cmdForm.switchValue === 'on'
    if (cmdForm.commandType === 2) params.threshold = cmdForm.threshold

    const res = await issueCommandApi({
      deviceId: drawerDevice.value.deviceId,
      commandType: cmdForm.commandType,
      params,
    })

    const commandId = typeof res.data === 'string' ? parseCommandId(res.data) : null
    if (commandId) {
      pendingCommandId.value = commandId
      deviceStore.markCommandPending(commandId, drawerDevice.value.deviceId)
    } else {
      // 未解析到追踪号时直接释放 loading（如后端报文格式变更）
      cmdSubmitting.value = false
    }

    notification.info({
      message: '指令已下发',
      description: commandId ? `追踪号：${commandId}` : '等待设备回执...',
      placement: 'topRight',
      duration: 2.6,
    })

    // 前端超时兜底：15 秒后无论是否收到 ACK，都解除 loading
    if (commandId) {
      window.setTimeout(() => {
        if (pendingCommandId.value === commandId) {
          deviceStore.clearCommandPending(commandId)
          pendingCommandId.value = null
          cmdSubmitting.value = false
          message.warning(`指令 ${commandId} 超时未收到回执，已自动释放`)
        }
      }, 15000)
    }
  } catch (e: any) {
    message.error(e?.message || '下发失败')
    cmdSubmitting.value = false
  }
}

watch(
  () => pendingCommandId.value,
  (id, _prev, onCleanup) => {
    if (!id) return
    const timer = window.setInterval(() => {
      if (!pendingCommandId.value) return
      if (!deviceStore.pendingCommandIds[pendingCommandId.value]) {
        cmdSubmitting.value = false
        pendingCommandId.value = null
      }
    }, 400)
    onCleanup(() => window.clearInterval(timer))
  },
)

let ws: DashboardWsClient | null = null

function handleWsMessage(msg: WsDeviceMessage) {
  deviceStore.applyWsMessage(msg)
  if (msg.type === 'reply') {
    const commandId = String(msg.data?.commandId ?? '')
    const ok = Boolean(msg.data?.success ?? true)
    notification[ok ? 'success' : 'error']({
      message: ok ? '指令回执：成功' : '指令回执：失败',
      description: commandId ? `追踪号：${commandId}` : `设备：${msg.deviceId}`,
      placement: 'topRight',
      duration: 2.8,
    })
    // 直接匹配当前待处理指令，立即解除按钮 loading
    if (commandId && pendingCommandId.value === commandId) {
      cmdSubmitting.value = false
      pendingCommandId.value = null
    }
  }
}

onMounted(async () => {
  await refresh()
  ws = new DashboardWsClient({ onMessage: handleWsMessage })
  ws.connect()
})

onBeforeUnmount(() => {
  ws?.close()
  ws = null
})
</script>
