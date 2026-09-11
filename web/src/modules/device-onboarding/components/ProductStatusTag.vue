<script setup lang="ts">
import { computed } from 'vue'
import { ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { productStatusKey } from '../models/onboarding'

const props = defineProps<{ status: string | null | undefined }>()
const statusKey = computed(() => productStatusKey(props.status))
const type = computed(() => statusKey.value === 'enabled' ? 'success' : statusKey.value === 'disabled' ? 'warning' : 'info')
</script>

<template><ElTag :type="type">{{ t(`deviceOnboarding.status.${statusKey}`) }}</ElTag></template>
