<template>
  <a-badge :status="badgeStatus" :text="text" />
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status?: number | null
}>()

const badgeStatus = computed(() => {
  if (props.status === 1) return 'success'
  if (props.status === 2) return 'warning'
  return 'default'
})

const text = computed(() => {
  if (props.status === 1) return '在线'
  if (props.status === 2) return '故障'
  return '离线'
})
</script>

