<template>
  <div class="admin-page">
    <AdminPageHeader title="用户管理" description="维护账号生命周期、固定角色和建筑范围。状态与安全约束由后端最终裁决。">
      <a-button type="primary" @click="openCreate">新建用户</a-button>
    </AdminPageHeader>
    <section class="admin-panel">
      <div class="admin-filter-bar">
        <a-input v-model:value="keyword" allow-clear placeholder="搜索用户名、昵称或手机号" style="width:260px" @press-enter="applyFilters" />
        <a-select v-model:value="status" allow-clear placeholder="全部状态" style="width:130px" :options="statusOptions" @change="applyFilters" />
        <span>包含已删除</span><a-switch v-model:checked="includeDeleted" @change="applyFilters" />
        <a-button @click="applyFilters">查询</a-button>
      </div>
      <a-alert v-if="users.error.value" class="admin-error" type="error" show-icon :message="users.error.value" />
      <a-table
        :data-source="users.page.value.records" :columns="columns" row-key="id" :loading="users.loading.value"
        :pagination="{ current: users.page.value.current, pageSize: users.page.value.size, total: users.page.value.total }"
        @change="changePage"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'"><a-tag :color="record.delFlag ? 'default' : record.status ? 'green' : 'orange'">{{ record.delFlag ? '已删除' : record.status ? '启用' : '停用' }}</a-tag></template>
          <template v-else-if="column.key === 'roles'"><a-space wrap><a-tag v-for="role in record.roles" :key="role">{{ adminRoleLabel(role) }}</a-tag></a-space></template>
          <template v-else-if="column.key === 'actions'">
            <a-space v-if="record.delFlag"><a-button type="link" @click="confirmRestore(record)">恢复</a-button></a-space>
            <a-space v-else wrap>
              <a-button type="link" @click="openEdit(record)">编辑</a-button><a-button type="link" @click="openRoles(record)">角色</a-button>
              <a-button type="link" @click="openBuildings(record)">建筑</a-button><a-button type="link" @click="openPassword(record)">重置密码</a-button>
              <a-button type="link" @click="confirmStatus(record)">{{ record.status ? '停用' : '启用' }}</a-button>
              <a-button type="link" danger @click="confirmDelete(record)">删除</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </section>
    <UserEditorDrawer :open="editorOpen" :mode="editorMode" :user="selected" :submitting="isPending(editorKey)" @close="editorOpen=false" @save="saveEditor" />
    <UserRoleAssignmentDrawer :open="roleOpen" :user="selected" :submitting="isPending(`roles:${selected?.id}`)" @close="roleOpen=false" @save="saveRoles" />
    <UserBuildingAssignmentDrawer :open="buildingOpen" :user="selected" :buildings="buildings" :submitting="isPending(`buildings:${selected?.id}`)" @close="buildingOpen=false" @save="saveBuildings" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import UserEditorDrawer from '@/components/admin/UserEditorDrawer.vue'
import UserRoleAssignmentDrawer from '@/components/admin/UserRoleAssignmentDrawer.vue'
import UserBuildingAssignmentDrawer from '@/components/admin/UserBuildingAssignmentDrawer.vue'
import { pageBuildings } from '@/api/systemAdmin'
import { useUserManagement } from '@/composables/useUserManagement'
import { adminRoleLabel } from '@/domain/adminRoles'
import type { BuildingOption, FormalRoleKey, UserAdminView, UserStatus } from '@/types/admin'

const users = useUserManagement()
// 页面只编排受确认的账号命令与确认对话框，最后管理员、自操作等安全规则由后端裁决。
const keyword = ref('')
const status = ref<UserStatus | undefined>()
const includeDeleted = ref(false)
const buildings = ref<BuildingOption[]>([])
const selected = ref<UserAdminView | null>(null)
const editorOpen = ref(false); const roleOpen = ref(false); const buildingOpen = ref(false)
const editorMode = ref<'create' | 'edit' | 'password'>('create')
const columns = [
  { title: '用户名', dataIndex: 'username', key: 'username' }, { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
  { title: '状态', key: 'status', width: 90 }, { title: '角色', key: 'roles' }, { title: '建筑数', dataIndex: ['buildingIds', 'length'], key: 'buildingCount', width: 90 },
  { title: '操作', key: 'actions', width: 360 },
]
const statusOptions = [{ label: '启用', value: 1 }, { label: '停用', value: 0 }]
const editorKey = computed(() => editorMode.value === 'create' ? 'create' : `${editorMode.value === 'edit' ? 'update' : 'password'}:${selected.value?.id}`)
const isPending = (key: string) => users.pending.value.has(key)
function applyFilters() { void users.setQuery({ keyword: keyword.value.trim() || undefined, status: status.value, includeDeleted: includeDeleted.value }).catch(() => undefined) }
function changePage(pagination: { current?: number }) { void users.setQuery({ page: pagination.current ?? 1 }, false).catch(() => undefined) }
function openCreate() { selected.value = null; editorMode.value = 'create'; editorOpen.value = true }
function openEdit(user: UserAdminView) { selected.value = user; editorMode.value = 'edit'; editorOpen.value = true }
function openPassword(user: UserAdminView) { selected.value = user; editorMode.value = 'password'; editorOpen.value = true }
function openRoles(user: UserAdminView) { selected.value = user; roleOpen.value = true }
function openBuildings(user: UserAdminView) { selected.value = user; buildingOpen.value = true }
async function saveEditor(value: { username?: string; password?: string; nickname?: string | null; phone?: string | null }) {
  try {
    if (editorMode.value === 'create') await users.createUser({ username: value.username!, password: value.password!, nickname: value.nickname, phone: value.phone })
    else if (editorMode.value === 'edit') await users.updateUser(selected.value!.id, { nickname: value.nickname, phone: value.phone })
    else await users.resetUserPassword(selected.value!.id, value.password!)
    editorOpen.value = false; message.success('用户信息已保存')
  } catch { /* 统一客户端保留后端业务消息，抽屉保持打开。 */ }
}
async function saveRoles(roles: FormalRoleKey[]) { try { await users.replaceUserRoles(selected.value!.id, roles); roleOpen.value = false; message.success('角色已替换') } catch { /* 业务错误由 Composable 保留，抽屉保持打开。 */ } }
async function saveBuildings(ids: string[]) { try { await users.replaceUserBuildings(selected.value!.id, ids); buildingOpen.value = false; message.success('建筑授权已替换') } catch { /* 业务错误由 Composable 保留，抽屉保持打开。 */ } }
function confirmStatus(user: UserAdminView) { Modal.confirm({ title: user.status ? '停用该用户？' : '启用该用户？', content: '状态变更由后端校验，停用会撤销旧登录态。', okText: user.status ? '确认停用' : '确认启用', cancelText: '取消', onOk: () => user.status ? users.disableUser(user.id) : users.enableUser(user.id) }) }
function confirmDelete(user: UserAdminView) { Modal.confirm({ title: '删除该用户？', content: '账号将逻辑删除并失去当前授权。', okText: '确认删除', cancelText: '取消', okType: 'danger', onOk: () => users.deleteUser(user.id) }) }
function confirmRestore(user: UserAdminView) { Modal.confirm({ title: '恢复该用户？', content: '恢复后需重新检查角色和建筑授权。', okText: '确认恢复', cancelText: '取消', onOk: () => users.restoreUser(user.id) }) }
onMounted(() => { void users.loadUsers().catch(() => undefined); void pageBuildings({ page: 1, size: 100 }).then((result) => { buildings.value = result.records }).catch(() => undefined) })
</script>
