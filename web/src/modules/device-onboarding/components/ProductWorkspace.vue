<script setup lang="ts">
import { ref } from 'vue'
import { ElAlert, ElButton, ElDrawer, ElInput } from '@/shared/ui'
import { t } from '@/locales'
import { ChangeRequestControl, useSensitiveChange } from '@/modules/access-control/public'
import ProductEditorDialog from './ProductEditorDialog.vue'
import { useDeviceOnboarding } from '../composables/use-device-onboarding'
import type { DeviceProductDetail, DeviceProductForm } from '../models/onboarding'

const props = defineProps<{ product: DeviceProductDetail | null }>()
// 就地编辑仍复用产品接口和敏感变更审批，选中产品不代表启用成功。
const emit = defineEmits<{ selected: [productId: string] }>()
const management = useDeviceOnboarding()
const changes = useSensitiveChange()
const open = ref(false)
const editing = ref<DeviceProductDetail | null>(null)
const busy = ref(false)
const copyOpen = ref(false)
const copyCode = ref('')
const copyName = ref('')
async function copyProduct() {
  if (!props.product || !copyCode.value.trim() || !copyName.value.trim()) return
  busy.value = true
  try { const detail = await management.copyProduct(props.product.productId, copyCode.value.trim(), copyName.value.trim()); if (detail) { copyOpen.value = false; emit('selected', detail.productId) } }
  catch { /* 复制失败保留输入和后端错误。 */ }
  finally { busy.value = false }
}
function edit(product: DeviceProductDetail | null) {
  editing.value = product
  open.value = true
  void management.loadEquipmentTypes()
}
async function save(form: DeviceProductForm) {
  busy.value = true
  try {
    const detail = await management.saveProduct(editing.value?.productId ?? null, form)
    if (detail) { open.value = false; emit('selected', detail.productId) }
  } catch { /* 操作错误保留在抽屉内，保存失败不关闭输入。 */ }
  finally { busy.value = false }
}
async function enable() {
  if (!props.product) return
  try { await changes.start('ENABLE_DEVICE_PRODUCT', { productId: props.product.productId }, `enable:${props.product.productId}`) }
  catch { /* 审批错误由公共状态展示。 */ }
}
async function approval(action: () => Promise<unknown>, refresh = false) {
  try { await action(); if (refresh && props.product) emit('selected', props.product.productId) }
  catch { /* 执行失败不提前更新产品状态。 */ }
}
</script>

<template>
  <div class="product-tools">
    <ElButton @click="edit(null)">{{ t('deviceOnboarding.actions.createProduct') }}</ElButton>
    <ElButton v-if="product?.allowedActions.includes('COPY')" @click="copyCode = ''; copyName = ''; copyOpen = true">{{ t('deviceOnboarding.actions.copyProduct') }}</ElButton>
    <ElButton v-if="product?.allowedActions.includes('UPDATE')" @click="edit(product)">{{ t('deviceOnboarding.forms.editProduct') }}</ElButton>
    <ElButton v-if="product?.allowedActions.includes('ENABLE')" :loading="changes.pending.value.size > 0" @click="enable">{{ t('protocolConfiguration.flow.enableProduct') }}</ElButton>
  </div>
  <ElAlert v-if="changes.error.value" :title="changes.error.value" type="error" :closable="false" />
  <ChangeRequestControl
    v-if="changes.current.value" business-view :change="changes.current.value" :busy="changes.pending.value.size > 0"
    @lookup="id => approval(() => changes.load(id))" @submit="id => approval(() => changes.submit(id))"
    @withdraw="id => approval(() => changes.withdraw(id))" @approve="(id, comment) => approval(() => changes.approve(id, comment))"
    @reject="(id, comment) => approval(() => changes.reject(id, comment))" @execute="id => approval(() => changes.execute(id), true)"
  />
  <ElDrawer v-model="copyOpen" :title="t('deviceOnboarding.forms.copyProduct')">
    <ElAlert v-if="management.operationError.value" :title="management.operationError.value.message" type="error" :closable="false" />
    <label>{{ t('deviceOnboarding.labels.productCode') }}<ElInput v-model="copyCode" maxlength="50" /></label>
    <label>{{ t('deviceOnboarding.labels.productName') }}<ElInput v-model="copyName" maxlength="100" /></label>
    <ElButton type="primary" :disabled="!copyCode.trim() || !copyName.trim()" :loading="busy" @click="copyProduct">{{ t('deviceOnboarding.actions.copyProduct') }}</ElButton>
  </ElDrawer>
  <ElDrawer v-model="open" :title="t(editing ? 'deviceOnboarding.forms.editProduct' : 'deviceOnboarding.forms.createProduct')" size="min(900px, 100%)">
    <ElAlert v-if="management.operationError.value" :title="management.operationError.value.message" type="error" :closable="false" />
    <ProductEditorDialog
      :open="open" embedded :product="editing" :submitting="busy" :equipment-types="management.equipmentTypes.value"
      :equipment-types-loading="management.equipmentTypesLoading.value" :equipment-types-error="management.equipmentTypesError.value?.message"
      @close="open = false" @save="save"
    />
  </ElDrawer>
</template>

<style scoped>
.product-tools { display: flex; flex-wrap: wrap; gap: var(--bec-space-group); }
</style>
