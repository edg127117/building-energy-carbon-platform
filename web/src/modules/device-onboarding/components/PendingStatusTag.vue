<script setup lang="ts">
import { computed } from 'vue'
import { ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { pendingStatusKey } from '../models/onboarding'

const props = defineProps<{ status: string | null | undefined }>()
const statusKey = computed(() => pendingStatusKey(props.status))
const type = computed(() => statusKey.value === 'bound' ? 'success' : statusKey.value === 'ignored' ? 'info' : 'warning')
</script>

<template><ElTag :type="type">{{ t(`deviceOnboarding.status.${statusKey}`) }}</ElTag></template>
