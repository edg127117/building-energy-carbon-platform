<template>
  <div class="admin-page">
    <AdminPageHeader title="角色权限" description="固定角色的后台页面与系统接入能力分开管理。" />
    <a-alert v-if="roles.error.value" type="error" show-icon :message="roles.error.value" />
    <div class="role-management-grid">
      <section class="admin-panel role-list">
        <button v-for="role in roles.roles.value" :key="role.id" type="button" :class="{ 'is-active': roles.selectedRole.value?.id === role.id }" @click="roles.selectRole(role)">
          <strong>{{ role.roleName }}</strong><span>{{ scopeLabel(role.dataScope, role.roleKey) }} · {{ role.status ? '启用' : '停用' }}</span>
        </button>
        <a-empty v-if="!roles.loading.value && roles.roles.value.length === 0" description="暂无固定角色" />
      </section>

      <section v-if="isThirdParty" class="admin-panel integration-panel">
        <div class="integration-heading">
          <div><h2>API 接入权限</h2><p>该角色用于系统间数据对接，不进入内部后台。</p></div>
          <span class="access-status"><i />已启用</span>
        </div>
        <div class="access-facts">
          <div><span>身份认证</span><strong>JWT 登录认证</strong></div>
          <div><span>数据范围</span><strong>仅限授权建筑</strong></div>
          <div><span>内部页面</span><strong>不开放</strong></div>
        </div>
        <div class="capability-section">
          <h3>当前开放能力</h3>
          <p>接口访问由后端固定角色校验，菜单勾选不会改变 API 权限。</p>
          <ul>
            <li><strong>建筑与设备信息</strong><span>读取已授权建筑及其设备资料</span></li>
            <li><strong>测点定义</strong><span>读取已授权建筑的测点信息</span></li>
            <li><strong>实时 HVAC 数据</strong><span>订阅已授权建筑的实时指标</span></li>
          </ul>
        </div>
      </section>

      <section v-else class="admin-panel">
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
.role-management-grid { display:grid; grid-template-columns:300px minmax(0,1fr); gap:20px; }
.role-list { display:flex; flex-direction:column; gap:8px; }
.role-list button { padding:16px 18px; display:flex; flex-direction:column; align-items:flex-start; gap:6px; color:#a9bfd2; border:1px solid transparent; background:transparent; border-radius:12px; cursor:pointer; text-align:left; }
.role-list button.is-active { color:#edf8ff; border-color:rgba(79,168,235,.28); background:rgba(52,137,201,.12); }
.role-list strong { font-size:15px; }.role-list span { color:#718ba3; font-size:12px; }
.role-tree-heading { display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:18px; }.role-tree-heading h2 { margin:0; font-size:18px; }.role-tree-heading p { margin:5px 0 0; color:#91a8bd; font-size:12px; }
.integration-panel { padding:28px; }
.integration-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:24px; }
.integration-heading h2 { margin:0; font-size:20px; color:#edf8ff; }
.integration-heading p { margin:7px 0 0; color:#91a8bd; font-size:13px; }
.access-status { display:inline-flex; align-items:center; gap:8px; padding:7px 12px; color:#a7e887; background:rgba(82,196,26,.09); border:1px solid rgba(82,196,26,.24); border-radius:8px; font-size:12px; white-space:nowrap; }
.access-status i { width:7px; height:7px; border-radius:50%; background:#73d13d; box-shadow:0 2px 8px rgba(115,209,61,.38); }
.access-facts { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); margin:26px 0 30px; border-top:1px solid rgba(104,157,194,.16); border-bottom:1px solid rgba(104,157,194,.16); }
.access-facts div { padding:18px 20px; display:flex; flex-direction:column; gap:7px; }
.access-facts div + div { border-left:1px solid rgba(104,157,194,.16); }
.access-facts span { color:#718ba3; font-size:12px; }.access-facts strong { color:#dcefff; font-size:14px; font-weight:600; }
.capability-section h3 { margin:0; color:#dcefff; font-size:16px; }.capability-section > p { margin:7px 0 18px; color:#829bb1; font-size:12px; }
.capability-section ul { margin:0; padding:0; list-style:none; display:grid; gap:10px; }
.capability-section li { display:grid; grid-template-columns:180px minmax(0,1fr); gap:20px; align-items:center; padding:14px 16px; background:rgba(29,70,99,.18); border-radius:10px; }
.capability-section li strong { color:#cae8fb; font-size:14px; }.capability-section li span { color:#829bb1; font-size:13px; }
@media (max-width:900px) { .role-management-grid { grid-template-columns:1fr; }.access-facts { grid-template-columns:1fr; }.access-facts div + div { border-left:0; border-top:1px solid rgba(104,157,194,.16); }.capability-section li { grid-template-columns:1fr; gap:4px; } }
</style>
