<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { t } from '@/locales'
import { ElAlert, ElCard, ElFormItem, ElOption, ElPagination, ElSelect } from '@/shared/ui'
import { useClassificationBuildings } from '../composables/use-classification-buildings'
import CarbonPlanPanel from '../components/CarbonPlanPanel.vue'

/** 独立配置页面复用建筑授权目录；由配置平台注册路由，不嵌入运营或大屏页面。 */
const buildings = useClassificationBuildings()
const buildingId = ref('')
const dirty = ref(false)
onMounted(() => { void buildings.search() })
</script>

<template>
  <main class="bec-flow">
    <ElCard shadow="never">
      <ElFormItem :label="t('energyClassification.building.label')">
        <ElSelect v-model="buildingId" :disabled="dirty" filterable remote clearable :remote-method="keyword => buildings.search(1, keyword)" :loading="buildings.loading.value" :placeholder="t('energyClassification.building.placeholder')">
          <ElOption v-for="building in buildings.records.value" :key="building.buildingId" :value="building.buildingId" :label="building.buildingName" />
          <template #footer><ElPagination small layout="total, prev, next" :current-page="buildings.page.value" :page-size="20" :total="buildings.total.value" @current-change="buildings.search" /></template>
        </ElSelect>
      </ElFormItem>
      <ElAlert v-if="buildings.error.value" :title="buildings.error.value" type="error" :closable="false" />
    </ElCard>
    <CarbonPlanPanel :building-id="buildingId" @dirty-change="dirty = $event" />
  </main>
</template>
