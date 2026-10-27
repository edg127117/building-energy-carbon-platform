<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { EnergyClassificationPage } from '@/modules/energy-classification/public'
import EnergyReportingPage from './EnergyReportingPage.vue'
import EnergyParameterPackagesPage from './EnergyParameterPackagesPage.vue'

const route = useRoute()
const professionalMode = computed(() => route.query.mode === 'professional')
const parameterMode = computed(() => route.query.mode === 'parameters')
</script>

<template>
  <EnergyParameterPackagesPage v-if="parameterMode" />
  <EnergyClassificationPage v-else-if="professionalMode" />
  <EnergyReportingPage v-else />
</template>
