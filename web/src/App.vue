<template>
  <a-config-provider :locale="zhCN" :theme="antdTheme">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'

const antdTheme = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#2F7DFF',
    colorInfo: '#2F7DFF',
    colorSuccess: '#1DD6A4',
    colorWarning: '#FFB020',
    colorError: '#FF4D6D',
    borderRadius: 10,
  },
}
</script>
