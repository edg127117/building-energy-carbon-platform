<template>
  <div class="admin-page">
    <AdminPageHeader title="角色权限" description="选择角色后查看可管理内容。" />
    <a-alert v-if="roles.error.value" type="error" show-icon :message="roles.error.value" />
    <div class="role-management-grid" :class="{ 'has-selection': roles.selectedRole.value }">
      <section class="admin-panel role-list">
        <button v-for="role in roles.roles.value" :key="role.id" type="button" :class="{ 'is-active': roles.selectedRole.value?.id === role.id }" @click="roles.selectRole(role)">
          <strong>{{ roleLabel(role.roleName, role.roleKey) }}</strong><span>{{ scopeLabel(role.dataScope, role.roleKey) }} · {{ role.status ? '启用' : '停用' }}</span>
        </button>
        <a-empty v-if="!roles.loading.value && roles.roles.value.length === 0" description="暂无固定角色" />
      </section>

      <section v-if="isThirdParty" class="admin-panel integration-panel">
        <div class="integration-heading">
          <h2>接口调用方</h2>
          <p>给需要从平台读取数据的对接人员或系统账号使用，不进入后台页面。</p>
        </div>
        <div class="management-list">
          <div><span>账号</span><strong>创建账号或指定接口调用方角色</strong></div>
          <div><span>账号状态</span><strong>启用或停用账号</strong></div>
          <div><span>数据范围</span><strong>分配账号可以读取的建筑</strong></div>
        </div>
        <div class="management-action">
          <RouterLink to="/system/users">管理接口账号</RouterLink>
          <p>具体接口范围、密钥、调用额度和调用记录目前不能在这里配置。</p>
        </div>
      </section>

      <section v-else-if="roles.selectedRole.value" class="admin-panel">
        <div class="role-tree-heading"><div><h2>菜单授权</h2><p>勾选该角色可以进入的后台页面。</p></div><a-button type="primary" :loading="roles.saving.value" :disabled="!roles.selectedRole.value" @click="save">保存菜单授权</a-button></div>
        <RoleMenuTree :tree="roles.tree.value" :checked-ids="roles.checkedIds.value" @update:checked-ids="roles.checkedIds.value = $event" />
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import RoleMenuTree from '@/components/admin/RoleMenuTree.vue'
import { useRoleManagement } from '@/composables/useRoleManagement'

/** 页面不提供角色 CRUD；第三方接口能力与内部页面菜单保持为两套独立权限语义。 */
const roles = useRoleManagement()
const isThirdParty = computed(() => roles.selectedRole.value?.roleKey === 'THIRD_PARTY')

function scopeLabel(scope: string, roleKey: string) {
  if (roleKey === 'THIRD_PARTY') return '按建筑授权'
  if (scope === 'ALL') return '全部建筑'
  if (scope === 'BUILDING') return '按建筑授权'
  return '仅本人'
}

function roleLabel(roleName: string, roleKey: string) {
  return roleKey === 'THIRD_PARTY' ? '接口调用方' : roleName
}

async function save() {
  try {
    await roles.save()
    message.success('角色菜单授权已替换')
  } catch {
    // 错误已展示在页面，保留当前勾选便于修正。
  }
}

onMounted(() => { void roles.load() })
</script>

<style scoped>
.role-management-grid { display:grid; grid-template-columns:minmax(360px,460px); gap:20px; }
.role-management-grid.has-selection { grid-template-columns:300px minmax(0,1fr); }
.role-list { display:flex; flex-direction:column; gap:8px; }
.role-list button { padding:16px 18px; display:flex; flex-direction:column; align-items:flex-start; gap:6px; color:#a9bfd2; border:1px solid transparent; background:transparent; border-radius:12px; cursor:pointer; text-align:left; }
.role-list button.is-active { color:#edf8ff; border-color:rgba(79,168,235,.28); background:rgba(52,137,201,.12); }
.role-list strong { font-size:15px; }.role-list span { color:#718ba3; font-size:12px; }
.role-tree-heading { display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:18px; }.role-tree-heading h2 { margin:0; font-size:18px; }.role-tree-heading p { margin:5px 0 0; color:#91a8bd; font-size:12px; }
.integration-panel { padding:28px; }
.integration-heading h2 { margin:0; font-size:20px; color:#edf8ff; }
.integration-heading p { max-width:620px; margin:7px 0 0; color:#91a8bd; font-size:13px; }
.management-list { margin:26px 0 22px; border-top:1px solid rgba(104,157,194,.16); }
.management-list div { display:grid; grid-template-columns:140px minmax(0,1fr); gap:24px; padding:17px 0; border-bottom:1px solid rgba(104,157,194,.16); }
.management-list span { color:#829bb1; font-size:13px; }.management-list strong { color:#dcefff; font-size:14px; font-weight:600; }
.management-action { display:flex; align-items:center; gap:18px; }
.management-action a { padding:9px 16px; color:#eef8ff; background:#2479d8; border-radius:8px; font-size:13px; text-decoration:none; }
.management-action a:hover { background:#3188e8; }.management-action a:focus-visible { outline:2px solid #79bfff; outline-offset:3px; }
.management-action p { margin:0; color:#718ba3; font-size:12px; }
@media (max-width:900px) { .role-management-grid,.role-management-grid.has-selection { grid-template-columns:1fr; }.management-list div { grid-template-columns:1fr; gap:5px; }.management-action { align-items:flex-start; flex-direction:column; } }
</style>
