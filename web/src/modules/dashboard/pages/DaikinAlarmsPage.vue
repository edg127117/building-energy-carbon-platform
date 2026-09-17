<script setup lang="ts">
import { useRoute } from 'vue-router'
import DaikinMonitoringPanel from '../components/DaikinMonitoringPanel.vue'
const route = useRoute()
// 切换实时/历史入口时销毁旧列表及游标，权限由对应后端异常接口独立校验。
</script>
<template><DaikinMonitoringPanel :key="route.path" alarms :history="route.path.endsWith('/historyAlarms')" /></template>
