<script setup lang="ts">
import { ref } from 'vue'
import { ElTabs, ElTabPane } from '@/shared/ui'
import { t } from '@/locales'
import IndexPage from './IndexPage.vue'
import DaikinMonitoringPanel from '../components/DaikinMonitoringPanel.vue'
const tab = ref('daikin')
// 仅挂载活动页签，继承冷站的实时连接与厂家分钟查询不会同时驻留。
</script>
<template>
  <ElTabs v-model="tab">
    <ElTabPane :label="t('dashboard.daikin.title')" name="daikin"><DaikinMonitoringPanel v-if="tab === 'daikin'" /></ElTabPane>
    <ElTabPane :label="t('dashboard.daikin.inherited')" name="inherited"><IndexPage v-if="tab === 'inherited'" /></ElTabPane>
  </ElTabs>
</template>
