<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElEmpty,
  ElMessage,
  ElPopconfirm,
  ElSpace,
  ElTable,
  ElTableColumn,
  ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import ChangeRequestControl from '../components/ChangeRequestControl.vue'
import MenuEditorDialog from '../components/MenuEditorDialog.vue'
import { useMenuManagement } from '../composables/use-menu-management'
import type { MenuCommand, MenuNode, MenuType } from '../models/access-control'
import { useSession } from '@/modules/auth/public'

type MenuRow = MenuNode & { depth: number }

const management = useMenuManagement()
const currentMenu = useSession()
const tree = management.tree
const loading = management.loading
const error = management.error
const changes = management.changes
const currentChange = changes.current
const changeBusy = computed(() => changes.pending.value.size > 0)
const editorVisible = ref(false)
const editingMenu = ref<MenuNode | null>(null)
const initialParentId = ref(0)

const rows = computed<MenuRow[]>(() => flattenTree(tree.value))
const parentOptions = computed(() => management.parentOptions(editingMenu.value?.id))

function flattenTree(items: MenuNode[], depth = 0): MenuRow[] {
  return items.flatMap(item => [
    { ...item, depth },
    ...flattenTree(item.children, depth + 1),
  ])
}

function typeLabel(menuType: MenuType) {
  const keys: Record<MenuType, string> = { M: 'typeDirectory', C: 'typePage', F: 'typePermission' }
  return t(`accessControl.menu.${keys[menuType]}`)
}

function navigationStatus(menu: MenuNode) {
  if (menu.status !== 1) return t('accessControl.menu.disabled')
  return menu.visible === 1
    ? t('accessControl.menu.visibleEnabled')
    : t('accessControl.menu.hidden')
}

function openCreate(parentId = 0) {
  editingMenu.value = null
  initialParentId.value = parentId
  editorVisible.value = true
}

function openEdit(menu: MenuNode) {
  editingMenu.value = menu
  initialParentId.value = menu.parentId
  editorVisible.value = true
}

function openChild(menu: MenuNode) {
  if (menu.menuType === 'F') {
    ElMessage.warning(t('accessControl.menu.cannotAddChild'))
    return
  }
  openCreate(menu.id)
}

async function submitMenu(command: MenuCommand) {
  try {
    if (editingMenu.value) await management.requestUpdate({ ...command, id: editingMenu.value.id })
    else await management.requestCreate(command)
    editorVisible.value = false
    ElMessage.success(t('accessControl.change.submitted'))
  } catch {
    // 失败状态由页面保留，表单不关闭。
  }
}

async function requestDelete(menu: MenuNode) {
  if (menu.children.length > 0) return
  try {
    await management.requestDelete(menu.id)
    ElMessage.success(t('accessControl.change.submitted'))
  } catch {
    // 失败状态由页面保留。
  }
}

async function afterExecute(requestId: string) {
  try {
    const result = await changes.execute(requestId)
    if (result?.status === 'EXECUTED') {
      await management.load()
      await currentMenu.refresh()
    }
  } catch {
    // 失败状态由申请控件与页面错误提示保留。
  }
}

onMounted(() => { void management.load().catch(() => undefined) })
</script>

<template>
  <section class="menu-page" :aria-label="t('accessControl.menu.title')">
    <header class="page-heading">
      <div><h1>{{ t('accessControl.menu.title') }}</h1><p>{{ t('accessControl.menu.description') }}</p></div>
      <ElSpace wrap>
        <ElButton :loading="loading" @click="management.load">{{ t('accessControl.action.refresh') }}</ElButton>
        <ElButton type="primary" @click="openCreate()">{{ t('accessControl.action.add') }}</ElButton>
      </ElSpace>
    </header>

    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <ElTable v-if="rows.length || loading" v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn :label="t('accessControl.menu.nameColumn')" min-width="var(--bec-navigation-width)">
        <template #default="{ row }"><span class="menu-name" :style="{ paddingInlineStart: `calc(${row.depth} * var(--bec-space-group))` }">{{ row.menuName }}</span></template>
      </ElTableColumn>
      <ElTableColumn :label="t('accessControl.menu.typeColumn')"><template #default="{ row }"><ElTag>{{ typeLabel(row.menuType) }}</ElTag></template></ElTableColumn>
      <ElTableColumn :label="t('accessControl.menu.navigationColumn')"><template #default="{ row }"><ElTag :type="row.status === 1 && row.visible === 1 ? 'success' : 'info'">{{ navigationStatus(row as MenuNode) }}</ElTag></template></ElTableColumn>
      <ElTableColumn :label="t('accessControl.menu.actionColumn')" min-width="var(--bec-navigation-width)">
        <template #default="{ row }">
          <ElSpace wrap>
            <ElButton text @click="openEdit(row as MenuNode)">{{ t('accessControl.action.edit') }}</ElButton>
            <ElButton text @click="openChild(row as MenuNode)">{{ t('accessControl.action.addChild') }}</ElButton>
            <ElPopconfirm :title="row.children.length ? t('accessControl.menu.cannotDeleteParent') : t('accessControl.action.delete')" :disabled="row.children.length > 0" @confirm="requestDelete(row as MenuNode)">
              <template #reference><ElButton text type="danger" :disabled="row.children.length > 0">{{ t('accessControl.action.delete') }}</ElButton></template>
            </ElPopconfirm>
          </ElSpace>
        </template>
      </ElTableColumn>
    </ElTable>
    <ElEmpty v-else :description="t('accessControl.menu.empty')" />

    <ChangeRequestControl
      :change="currentChange"
      :busy="changeBusy"
      @lookup="changes.load"
      @submit="changes.submit"
      @withdraw="changes.withdraw"
      @approve="changes.approve"
      @reject="changes.reject"
      @execute="afterExecute"
    />
    <MenuEditorDialog
      v-model="editorVisible"
      :menu="editingMenu"
      :initial-parent-id="initialParentId"
      :parent-options="parentOptions"
      :submitting="changeBusy"
      @submit="submitMenu"
    />
  </section>
</template>

<style scoped>
.menu-page { display: grid; align-content: start; gap: var(--bec-space-section); min-width: 0; }
.page-heading { display: flex; align-items: start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
h1 { margin: 0; font-size: var(--bec-font-size-system); }
p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.menu-name { display: inline-block; }
</style>
