<script setup lang="ts">
import { computed } from 'vue'
import { ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { assetStatusKey } from '../models/assets'

const props = defineProps<{ status: string | null | undefined }>()
const statusKey = computed(() => assetStatusKey(props.status))
const type = computed(() => statusKey.value === 'active' ? 'success' : statusKey.value === 'disabled' ? 'warning' : 'info')
</script>

<template>
  <ElTag :type="type">{{ t(`assetManagement.status.${statusKey}`) }}</ElTag>
</template>
