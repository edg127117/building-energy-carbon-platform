<template>
  <div class="admin-page product-management-page">
    <!-- THESIS: 把产品与测点模板作为可审计的接入前置配置，而不是散落的表单字段。 OWN-WORLD: 延续后台深蓝操作台、低饱和边界和单一蓝色主操作。 STORY: 筛选产品、检查模板、只在后端许可时编辑或启停。 FIRST VIEWPORT: 产品清单及当前状态一眼可见。 AVOID: 不把状态做成装饰性统计卡，不在前端推断写权限。 -->
    <AdminPageHeader title="产品与测点模板" description="维护设备产品、期望协议和标准测点模板；产品启用后通过复制草稿演进，避免直接改写在用配置。">
      <a-button type="primary" @click="openCreate">新建产品草稿</a-button>
    </AdminPageHeader>

    <a-alert v-if="pageError" class="admin-error" :type="management.productsError.value?.forbidden ? 'warning' : 'error'" show-icon :message="pageError" />

    <section class="admin-panel">
      <div class="admin-filter-bar">
        <a-select v-model:value="status" allow-clear placeholder="全部状态" style="width:140px" :options="statusOptions" @change="applyFilters" />
        <a-input v-model:value="keyword" allow-clear placeholder="搜索编码、名称或型号" style="width:260px" @press-enter="applyFilters" />
        <a-button @click="applyFilters">查询</a-button>
      </div>
      <a-skeleton v-if="management.productsLoading.value && management.productPage.value.items.length === 0" active :paragraph="{ rows: 6 }" />
      <a-table v-else row-key="productId" :data-source="management.productPage.value.items" :columns="columns" :loading="management.productsLoading.value" :scroll="{ x: 1040 }" :pagination="pagination" @change="changePage">
        <template #emptyText><a-empty description="没有符合筛选条件的产品模板" /></template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'product'"><div class="product-primary"><strong>{{ record.productName }}</strong><span>{{ record.productCode }}</span></div></template>
          <template v-else-if="column.key === 'manufacturer'"><span>{{ [record.manufacturer, record.model].filter(Boolean).join(' / ') || '未填写' }}</span></template>
          <template v-else-if="column.key === 'profile'"><span>{{ record.expectedProfileCode }} · {{ record.identityType }}</span></template>
          <template v-else-if="column.key === 'status'"><AssetStatusTag :status="record.status" /></template>
          <template v-else-if="column.key === 'updateTime'"><span>{{ formatTime(record.updateTime) }}</span></template>
          <template v-else-if="column.key === 'actions'"><a-button type="link" @click="openDetail(record.productId)">详情</a-button></template>
        </template>
      </a-table>
    </section>

    <a-drawer :open="detailOpen" title="产品模板详情" width="min(860px, 94vw)" destroy-on-close @close="detailOpen = false">
      <a-skeleton v-if="management.productDetailLoading.value" active :paragraph="{ rows: 8 }" />
      <a-alert v-else-if="management.productDetailError.value" type="error" show-icon :message="management.productDetailError.value.message" />
      <template v-else-if="management.selectedProduct.value">
        <div class="product-detail-heading"><div><h2>{{ management.selectedProduct.value.productName }}</h2><p>{{ management.selectedProduct.value.productCode }} · {{ management.selectedProduct.value.equipmentTypeCode }}</p></div><AssetStatusTag :status="management.selectedProduct.value.status" /></div>
        <a-descriptions bordered size="small" :column="2">
          <a-descriptions-item label="厂商 / 型号">{{ [management.selectedProduct.value.manufacturer, management.selectedProduct.value.model].filter(Boolean).join(' / ') || '未填写' }}</a-descriptions-item>
          <a-descriptions-item label="身份类型">{{ management.selectedProduct.value.identityType }}</a-descriptions-item>
          <a-descriptions-item label="期望 Profile">{{ management.selectedProduct.value.expectedProfileCode }}</a-descriptions-item>
          <a-descriptions-item label="更新时间">{{ formatTime(management.selectedProduct.value.updateTime) }}</a-descriptions-item>
        </a-descriptions>
        <section class="product-detail-points"><h3>测点模板</h3><a-table row-key="templatePointId" size="small" :pagination="false" :data-source="management.selectedProduct.value.points" :columns="pointColumns" :scroll="{ x: 680 }"><template #bodyCell="{ column, record }"><template v-if="column.key === 'range'">{{ formatRange(record.minValue, record.maxValue, record.unit) }}</template><template v-else-if="column.key === 'flags'"><a-space wrap><a-tag v-if="record.required" color="blue">必需</a-tag><a-tag v-if="record.forCalc" color="green">参与计算</a-tag><a-tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</a-tag></a-space></template></template></a-table></section>
        <div class="drawer-actions">
          <a-button v-if="can('COPY')" @click="openCopy">复制草稿</a-button>
          <a-button v-if="can('UPDATE')" @click="openEdit">编辑草稿</a-button>
          <a-button v-if="can('ENABLE')" type="primary" :loading="isRunning('enable')" @click="confirmEnabled(true)">启用产品</a-button>
          <a-button v-if="can('DISABLE')" danger :loading="isRunning('disable')" @click="confirmEnabled(false)">停用产品</a-button>
        </div>
      </template>
    </a-drawer>

    <DeviceProductDrawer :open="editorOpen" :product="editingProduct" :submitting="management.running.value.has(editingProduct ? `product:update:${editingProduct.productId}` : 'product:create')" @close="editorOpen = false" @save="saveProduct" />
    <a-modal v-model:open="copyOpen" title="复制为新产品草稿" ok-text="复制" cancel-text="取消" :confirm-loading="copySubmitting" @ok="copyProduct">
      <a-form layout="vertical"><a-form-item label="新产品编码" required><a-input v-model:value="copyCode" maxlength="50" /></a-form-item><a-form-item label="新产品名称" required><a-input v-model:value="copyName" maxlength="100" /></a-form-item></a-form>
      <a-alert v-if="copyError" type="error" show-icon :message="copyError" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AssetStatusTag from '@/components/admin/AssetStatusTag.vue'
import DeviceProductDrawer from '@/components/admin/DeviceProductDrawer.vue'
import { useDeviceOnboardingManagement } from '@/composables/useDeviceOnboardingManagement'
import { canRunProductAction, type DeviceProductAction, type DeviceProductDetail, type DeviceProductForm } from '@/types/deviceOnboarding'
import { requestErrorMessage } from '@/utils/request'

const management = useDeviceOnboardingManagement()
const status = ref<string>()
const keyword = ref('')
const explicitError = ref<string | null>(null)
const detailOpen = ref(false)
const editorOpen = ref(false)
const editingProduct = ref<DeviceProductDetail | null>(null)
const copyOpen = ref(false)
const copyCode = ref('')
const copyName = ref('')
const copyError = ref<string | null>(null)
const pageError = computed(() => explicitError.value ?? management.productsError.value?.message ?? null)
const pagination = computed(() => ({ current: management.productPage.value.page, pageSize: management.productPage.value.size, total: management.productPage.value.total }))
const copySubmitting = computed(() => management.selectedProduct.value ? management.running.value.has(`product:copy:${management.selectedProduct.value.productId}`) : false)
const statusOptions = [{ label: '草稿', value: 'DRAFT' }, { label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }]
const columns = [{ title: '产品', key: 'product', width: 210 }, { title: '厂商 / 型号', key: 'manufacturer', width: 190 }, { title: '设备类型', dataIndex: 'equipmentTypeCode', key: 'equipmentTypeCode', width: 110 }, { title: '协议与身份', key: 'profile', width: 210 }, { title: '测点', dataIndex: 'pointCount', key: 'pointCount', width: 80 }, { title: '状态', key: 'status', width: 90 }, { title: '更新时间', key: 'updateTime', width: 170 }, { title: '操作', key: 'actions', width: 80, fixed: 'right' }]
const pointColumns = [{ title: '指标编码', dataIndex: 'metricCode', key: 'metricCode', width: 150 }, { title: '名称模板', dataIndex: 'pointNameTemplate', key: 'pointNameTemplate', width: 160 }, { title: '后缀', dataIndex: 'suffixCode', key: 'suffixCode', width: 80 }, { title: '范围', key: 'range', width: 150 }, { title: '属性', key: 'flags', width: 190 }]

function applyFilters() { void management.setProductQuery({ status: status.value, keyword: keyword.value.trim() || undefined }).catch(showError) }
function changePage(page: { current?: number }) { void management.setProductQuery({ page: page.current ?? 1 }, false).catch(showError) }
function openCreate() { editingProduct.value = null; editorOpen.value = true }
async function openDetail(productId: string) { detailOpen.value = true; try { await management.selectProduct(productId) } catch (reason) { showError(reason) } }
function openEdit() { editingProduct.value = management.selectedProduct.value; editorOpen.value = true }
function can(action: DeviceProductAction) { const product = management.selectedProduct.value; return product ? canRunProductAction(product, action) : false }
function isRunning(action: 'enable' | 'disable') { const product = management.selectedProduct.value; return product ? management.running.value.has(`product:${action}:${product.productId}`) : false }
async function saveProduct(form: DeviceProductForm) { try { await management.saveProduct(editingProduct.value?.productId ?? null, form); editorOpen.value = false; message.success('产品草稿已保存') } catch (reason) { showError(reason) } }
function openCopy() { const product = management.selectedProduct.value; if (!product) return; copyCode.value = `${product.productCode}_COPY`; copyName.value = `${product.productName} 副本`; copyError.value = null; copyOpen.value = true }
async function copyProduct() { const product = management.selectedProduct.value; if (!product || !copyCode.value.trim() || !copyName.value.trim()) { copyError.value = '请填写新产品编码和名称。'; return } try { await management.copyProduct(product.productId, copyCode.value.trim(), copyName.value.trim()); copyOpen.value = false; detailOpen.value = false; message.success('已复制为新草稿') } catch (reason) { copyError.value = requestErrorMessage(reason) } }
function confirmEnabled(enabled: boolean) { const product = management.selectedProduct.value; if (!product) return; Modal.confirm({ title: enabled ? '启用该产品？' : '停用该产品？', content: enabled ? '启用后产品草稿及测点模板不能直接编辑。' : '停用只阻止后续接入选择，不改写已绑定设备。', okText: enabled ? '确认启用' : '确认停用', cancelText: '取消', okType: enabled ? 'primary' : 'danger', onOk: async () => { try { await management.changeProductEnabled(product.productId, enabled); message.success(enabled ? '产品已启用' : '产品已停用') } catch (reason) { showError(reason); throw reason } } }) }
function formatTime(value: number) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
function formatRange(min: number | null, max: number | null, unit: string) { return `${min ?? '—'} ～ ${max ?? '—'} ${unit}` }
function showError(reason: unknown) { explicitError.value = requestErrorMessage(reason) }
onMounted(() => { void management.loadProducts().catch(showError) })
</script>

<style scoped>
.product-primary { display: flex; flex-direction: column; gap: 3px; }.product-primary strong { color: #e7f2fc; }.product-primary span { color: #7892a8; font-size: 11px; }
.product-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }.product-detail-heading h2 { margin: 0; font-size: 21px; }.product-detail-heading p { margin: 5px 0 0; color: #91a8bd; }
.product-detail-points { margin-top: 24px; }.product-detail-points h3 { margin: 0 0 12px; font-size: 15px; }
</style>
