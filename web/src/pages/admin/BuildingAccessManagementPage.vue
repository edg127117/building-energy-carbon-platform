<template>
  <div class="admin-page">
    <AdminPageHeader title="建筑授权" description="直接授权会立即替换用户范围；申请审核只处理用户已提交的访问申请。不维护建筑档案。" />
    <a-alert v-if="access.error.value" type="error" show-icon :message="access.error.value" />
    <section class="admin-panel access-direct-panel">
      <div><h2>直接授权</h2><p>选择用户后检查现有建筑范围，保存将立即全量替换。</p></div>
      <a-select v-model:value="selectedUserId" show-search option-filter-prop="label" placeholder="选择用户" style="width:320px" :options="userOptions" />
      <a-button type="primary" :disabled="!selectedUser" @click="assignmentOpen=true">配置建筑范围</a-button>
    </section>
    <section class="admin-panel">
      <div class="access-review-heading"><div><h2>申请审核</h2><p>批准会写入正式授权；拒绝不改变用户当前范围。</p></div><a-select v-model:value="access.status.value" style="width:150px" :options="statusOptions" @change="reload" /></div>
      <a-table :columns="columns" :data-source="access.requests.value" row-key="id" :loading="access.loading.value" :pagination="false">
        <template #bodyCell="{ column, record }"><template v-if="column.key === 'status'"><a-tag :color="statusColor(record.status)">{{ record.status }}</a-tag></template><template v-else-if="column.key === 'actions'"><a-button type="link" @click="openReview(record)">{{ record.status === 'PENDING' ? '审核' : '查看' }}</a-button></template></template>
      </a-table>
    </section>
    <UserBuildingAssignmentDrawer :open="assignmentOpen" :user="selectedUser" :buildings="access.buildings.value" :submitting="access.pending.value" @close="assignmentOpen=false" @save="assign" />
    <BuildingAccessReviewDrawer :open="reviewOpen" :request="selectedRequest" :submitting="access.pending.value" @close="reviewOpen=false" @approve="approve" @reject="reject" />
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import UserBuildingAssignmentDrawer from '@/components/admin/UserBuildingAssignmentDrawer.vue'
import BuildingAccessReviewDrawer from '@/components/admin/BuildingAccessReviewDrawer.vue'
import { useBuildingAccessManagement } from '@/composables/useBuildingAccessManagement'
import type { BuildingAccessRequestView } from '@/types/admin'
/** 两种授权路径在页面中明确分区，建筑档案和最终权限判断仍由后端拥有。 */
const access=useBuildingAccessManagement(); const selectedUserId=ref<number>(); const assignmentOpen=ref(false); const reviewOpen=ref(false); const selectedRequest=ref<BuildingAccessRequestView|null>(null)
const selectedUser=computed(()=>access.users.value.find((item)=>item.id===selectedUserId.value)??null)
const userOptions=computed(()=>access.users.value.map((item)=>({label:`${item.username}${item.nickname?` · ${item.nickname}`:''}`,value:item.id})))
const statusOptions=[{label:'待审核',value:'PENDING'},{label:'全部',value:'ALL'},{label:'已批准',value:'APPROVED'},{label:'已拒绝',value:'REJECTED'},{label:'已取消',value:'CANCELLED'}]
const columns=[{title:'申请人',dataIndex:'username',key:'username'},{title:'建筑',dataIndex:'buildingName',key:'building'},{title:'原因',dataIndex:'reason',key:'reason'},{title:'状态',key:'status',width:100},{title:'操作',key:'actions',width:90}]
function reload(){void access.load().catch(()=>undefined)} function statusColor(status:string){return status==='APPROVED'?'green':status==='REJECTED'?'red':status==='PENDING'?'blue':'default'}
function openReview(request:BuildingAccessRequestView){selectedRequest.value=request;reviewOpen.value=true}
async function assign(ids:string[]){if(!selectedUser.value)return;try{await access.assignBuildings(selectedUser.value.id,ids);assignmentOpen.value=false;message.success('建筑授权已替换')}catch{}}
async function approve(comment?:string){try{await access.approve(selectedRequest.value!.id,comment);reviewOpen.value=false;message.success('申请已批准')}catch{}}
async function reject(comment?:string){try{await access.reject(selectedRequest.value!.id,comment);reviewOpen.value=false;message.success('申请已拒绝')}catch{}}
onMounted(()=>{void Promise.all([access.load(),access.loadOptions()]).catch(()=>undefined)})
</script>
<style scoped>
.access-direct-panel,.access-review-heading{display:flex;align-items:center;gap:18px}.access-direct-panel>div,.access-review-heading>div{flex:1}.access-direct-panel h2,.access-review-heading h2{margin:0;font-size:18px}.access-direct-panel p,.access-review-heading p{margin:4px 0 0;color:#91a8bd;font-size:12px}.review-comment{margin-top:20px}
</style>
