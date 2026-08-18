<template>
  <div class="admin-page device-onboarding-page">
    <!-- THESIS: 未知设备先被识别、审阅、绑定，再独立启用身份；拒绝一步接入的危险捷径。 OWN-WORLD: 延续后台深蓝操作台，以状态标签和明确确认承载风险。 STORY: 从发现记录进入详情，完成归属和测点映射，复核后启用。 FIRST VIEWPORT: 最近发现、状态和上报次数优先。 AVOID: 不展示伪造数据，不自动启用身份，不猜命名规则。 -->
    <AdminPageHeader title="待绑定设备接入" description="查看有界发现记录，核对真实身份与样例，完成产品、资产归属和测点映射后，再单独确认启用设备身份。" />
    <a-alert v-if="pageError" class="admin-error" :type="management.pendingError.value?.forbidden ? 'warning' : 'error'" show-icon :message="pageError" />

    <section class="admin-panel">
      <div class="admin-filter-bar">
        <a-select v-model:value="status" allow-clear placeholder="全部状态" style="width:150px" :options="statusOptions" @change="applyFilters" />
        <a-input v-model:value="identity" allow-clear placeholder="搜索身份值" style="width:220px" @press-enter="applyFilters" />
        <a-input v-model:value="profileCode" allow-clear placeholder="Profile 编码" style="width:180px" @press-enter="applyFilters" />
        <a-button @click="applyFilters">查询</a-button>
      </div>
      <a-skeleton v-if="management.pendingLoading.value && management.pendingPage.value.items.length === 0" active :paragraph="{ rows: 6 }" />
      <a-table v-else row-key="pendingId" :data-source="management.pendingPage.value.items" :columns="columns" :loading="management.pendingLoading.value" :scroll="{ x: 1000 }" :pagination="pagination" @change="changePage">
        <template #emptyText><a-empty description="当前没有符合条件的待绑定设备" /></template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'identity'"><div class="pending-identity"><strong>{{ record.maskedIdentityValue }}</strong><span>{{ record.identityType }}</span></div></template>
          <template v-else-if="column.key === 'profile'"><span>{{ record.profileCode }} · v{{ record.lastProfileVersion }}</span></template>
          <template v-else-if="column.key === 'status'"><AssetStatusTag :status="record.status" /></template>
          <template v-else-if="column.key === 'lastSeenTime'"><span>{{ formatTime(record.lastSeenTime) }}</span></template>
          <template v-else-if="column.key === 'sampleTruncated'"><a-tag :color="record.sampleTruncated ? 'orange' : 'default'">{{ record.sampleTruncated ? '已截断' : '完整' }}</a-tag></template>
          <template v-else-if="column.key === 'actions'"><a-button type="link" @click="openDetail(record.pendingId)">检查</a-button></template>
        </template>
      </a-table>
    </section>

    <a-drawer :open="detailOpen" title="发现记录详情" width="min(900px, 95vw)" destroy-on-close @close="closeDetail">
      <a-skeleton v-if="management.pendingDetailLoading.value" active :paragraph="{ rows: 8 }" />
      <a-alert v-else-if="management.pendingDetailError.value" type="error" show-icon :message="management.pendingDetailError.value.message" />
      <template v-else-if="management.selectedPending.value">
        <div class="pending-detail-heading"><div><h2>{{ management.selectedPending.value.identityValue }}</h2><p>{{ management.selectedPending.value.identityType }} · {{ management.selectedPending.value.profileCode }} v{{ management.selectedPending.value.lastProfileVersion }}</p></div><AssetStatusTag :status="management.selectedPending.value.status" /></div>
        <a-descriptions bordered size="small" :column="2">
          <a-descriptions-item label="首次发现">{{ formatTime(management.selectedPending.value.firstSeenTime) }}</a-descriptions-item>
          <a-descriptions-item label="最近发现">{{ formatTime(management.selectedPending.value.lastSeenTime) }}</a-descriptions-item>
          <a-descriptions-item label="上报次数">{{ management.selectedPending.value.reportCount }}</a-descriptions-item>
          <a-descriptions-item label="事件时间来源">{{ management.selectedPending.value.latestTimeSource || '未提供' }}</a-descriptions-item>
          <a-descriptions-item label="事件时间">{{ formatTime(management.selectedPending.value.latestEventTime) }}</a-descriptions-item>
          <a-descriptions-item label="样例状态">{{ management.selectedPending.value.sampleTruncated ? '已按上限截断' : '未截断' }}</a-descriptions-item>
        </a-descriptions>
        <section class="pending-sample"><div><h3>最新指标样例</h3><p>仅用于管理员核对；前端不据此生成历史、指标或产品配置。</p></div><pre>{{ metricsJson }}</pre></section>
        <a-alert v-if="management.selectedPending.value.status === 'BOUND'" type="success" show-icon message="该发现记录已完成绑定。正式设备数据链请到“设备与测点”查看。" />
        <div class="drawer-actions">
          <a-button v-if="can('RESTORE')" :loading="statusRunning" @click="restore">恢复发现</a-button>
          <a-button v-if="can('IGNORE')" danger @click="openIgnore">忽略设备</a-button>
          <a-button v-if="can('BIND')" type="primary" @click="openWizard">开始接入</a-button>
        </div>
      </template>
    </a-drawer>

    <a-modal v-model:open="ignoreOpen" title="忽略该未知设备？" ok-text="确认忽略" cancel-text="取消" ok-type="danger" :confirm-loading="statusRunning" @ok="ignore">
      <p>忽略后仍保留有界发现记录，后续可以恢复；不会创建或修改正式设备。</p>
      <a-textarea v-model:value="ignoreReason" :maxlength="200" show-count placeholder="原因（可选）" />
    </a-modal>

    <DeviceBindingWizard v-if="wizardOpen && management.selectedPending.value" :open="wizardOpen" :pending="management.selectedPending.value" :products="productOptions" :product="management.selectedProduct.value" :product-loading="management.productDetailLoading.value" :buildings="assets.buildingOptions.value" :spaces="spaceOptions" :groups="groupOptions" :equipment="equipmentOptions" :points="assets.points.value" :bind-result="management.bindResult.value" :activation-result="management.activationResult.value" :binding="binding" :activating="activating" :operation-error="wizardError" @close="wizardOpen = false" @product-change="loadWizardProduct" @building-change="loadWizardBuilding" @equipment-change="loadWizardEquipment" @bind="bind" @activate="activate" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AssetStatusTag from '@/components/admin/AssetStatusTag.vue'
import DeviceBindingWizard from '@/components/admin/DeviceBindingWizard.vue'
import { useAssetManagement } from '@/composables/useAssetManagement'
import { useDeviceOnboardingManagement } from '@/composables/useDeviceOnboardingManagement'
import { canRunPendingAction, type DeviceBindRequest, type PendingDeviceAction } from '@/types/deviceOnboarding'
import type { AssetSpaceView } from '@/types/assets'
import { requestErrorMessage } from '@/utils/request'

const management = useDeviceOnboardingManagement()
const assets = useAssetManagement()
const status = ref<string>()
const identity = ref('')
const profileCode = ref('')
const explicitError = ref<string | null>(null)
const detailOpen = ref(false)
const ignoreOpen = ref(false)
const ignoreReason = ref('')
const wizardOpen = ref(false)
const wizardError = ref<string | null>(null)
const pageError = computed(() => explicitError.value ?? management.pendingError.value?.message ?? null)
const pagination = computed(() => ({ current: management.pendingPage.value.page, pageSize: management.pendingPage.value.size, total: management.pendingPage.value.total }))
const metricsJson = computed(() => JSON.stringify(management.selectedPending.value?.latestMetrics ?? {}, null, 2))
const productOptions = computed(() => management.productPage.value.items.filter((item) => item.status === 'ENABLED').map((item) => ({ label: `${item.productName}（${item.productCode}）`, value: item.productId })))
const spaceOptions = computed(() => flatten(assets.equipmentScopeSpaces.value).map((item) => ({ label: item.spaceName, value: item.spaceId })))
const groupOptions = computed(() => assets.equipmentScopeGroups.value.map((item) => ({ label: item.systemName, value: item.systemGroupId })))
const equipmentOptions = computed(() => assets.equipmentPage.value.items.map((item) => ({ label: `${item.equipmentName}${item.equipmentCode ? `（${item.equipmentCode}）` : ''}`, value: item.equipmentId })))
const statusRunning = computed(() => management.selectedPending.value ? management.running.value.has(`pending:status:${management.selectedPending.value.pendingId}`) : false)
const binding = computed(() => management.selectedPending.value ? management.running.value.has(`pending:bind:${management.selectedPending.value.pendingId}`) : false)
const activating = computed(() => management.bindResult.value ? management.running.value.has(`identity:activate:${management.bindResult.value.identityId}`) : false)
const statusOptions = [{ label: '待处理', value: 'DISCOVERED' }, { label: '已忽略', value: 'IGNORED' }, { label: '已绑定', value: 'BOUND' }]
const columns = [{ title: '设备身份', key: 'identity', width: 220 }, { title: 'Profile', key: 'profile', width: 190 }, { title: '状态', key: 'status', width: 100 }, { title: '上报次数', dataIndex: 'reportCount', key: 'reportCount', width: 100 }, { title: '最近发现', key: 'lastSeenTime', width: 180 }, { title: '样例', key: 'sampleTruncated', width: 90 }, { title: '操作', key: 'actions', width: 80, fixed: 'right' }]

function applyFilters() { void management.setPendingQuery({ status: status.value, identity: identity.value.trim() || undefined, profileCode: profileCode.value.trim() || undefined }).catch(showError) }
function changePage(page: { current?: number }) { void management.setPendingQuery({ page: page.current ?? 1 }, false).catch(showError) }
async function openDetail(pendingId: string) { detailOpen.value = true; try { await management.selectPending(pendingId) } catch (reason) { showError(reason) } }
function closeDetail() { detailOpen.value = false; wizardOpen.value = false; void management.selectPending(null) }
function can(action: PendingDeviceAction) { const pending = management.selectedPending.value; return pending ? canRunPendingAction(pending, action) : false }
function openIgnore() { ignoreReason.value = ''; ignoreOpen.value = true }
async function ignore() { const pending = management.selectedPending.value; if (!pending) return; try { await management.changePendingStatus(pending.pendingId, 'IGNORED', ignoreReason.value); ignoreOpen.value = false; message.success('设备已忽略，可随时恢复') } catch (reason) { showError(reason) } }
async function restore() { const pending = management.selectedPending.value; if (!pending) return; try { await management.changePendingStatus(pending.pendingId, 'DISCOVERED'); message.success('设备已恢复为待处理') } catch (reason) { showError(reason) } }
async function openWizard() { wizardError.value = null; wizardOpen.value = true; try { await Promise.all([management.setProductQuery({ page: 1, size: 100, status: 'ENABLED', keyword: undefined }), assets.ensureBuildingOptions()]) } catch (reason) { showWizardError(reason) } }
function loadWizardProduct(productId: string) { wizardError.value = null; void management.selectProduct(productId).catch(showWizardError) }
async function loadWizardBuilding(buildingId: string) { wizardError.value = null; try { await Promise.all([assets.loadEquipmentScope(buildingId), assets.setEquipmentQuery({ page: 1, size: 100, buildingId, spaceId: undefined, systemGroupId: undefined })]) } catch (reason) { showWizardError(reason) } }
function loadWizardEquipment(equipmentId: string) { wizardError.value = null; void assets.selectEquipment(equipmentId).catch(showWizardError) }
async function bind(request: DeviceBindRequest) { const pending = management.selectedPending.value; if (!pending) return; wizardError.value = null; try { await management.bind(pending.pendingId, request); message.success('设备已绑定，身份仍未启用') } catch (reason) { showWizardError(reason) } }
async function activate(identityId: string) { wizardError.value = null; try { await management.activate(identityId); message.success('设备身份已启用，配置已确认生效') } catch (reason) { showWizardError(reason) } }
function flatten(items: AssetSpaceView[]): AssetSpaceView[] { return items.flatMap((item) => [item, ...flatten(item.children)]) }
function formatTime(value: number) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未提供' }
function showError(reason: unknown) { explicitError.value = requestErrorMessage(reason) }
function showWizardError(reason: unknown) { wizardError.value = requestErrorMessage(reason) }
onMounted(() => { void management.loadPending().catch(showError) })
</script>

<style scoped>
.pending-identity { display: flex; flex-direction: column; gap: 3px; }.pending-identity strong { color: #e7f2fc; }.pending-identity span { color: #7892a8; font-size: 11px; }
.pending-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }.pending-detail-heading h2 { margin: 0; font-size: 20px; word-break: break-all; }.pending-detail-heading p { margin: 5px 0 0; color: #91a8bd; }
.pending-sample { margin: 24px 0; }.pending-sample h3 { margin: 0; font-size: 15px; }.pending-sample p { margin: 5px 0 12px; color: #91a8bd; font-size: 12px; }.pending-sample pre { max-height: 300px; overflow: auto; margin: 0; padding: 16px; color: #cfe4f4; background: #07131f; border-radius: 12px; font-size: 12px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }
</style>
