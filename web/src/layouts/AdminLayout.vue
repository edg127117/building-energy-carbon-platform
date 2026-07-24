<template>
  <div class="min-h-screen bg-[#070B14] text-zinc-100">
    <div class="pointer-events-none fixed inset-0">
      <div class="absolute inset-0 bg-[radial-gradient(1000px_540px_at_15%_10%,rgba(47,125,255,0.18),transparent_60%),radial-gradient(840px_520px_at_85%_40%,rgba(29,214,164,0.10),transparent_68%)]" />
      <div class="absolute inset-0 opacity-[0.10] [background-image:linear-gradient(to_right,rgba(255,255,255,0.08)_1px,transparent_1px),linear-gradient(to_bottom,rgba(255,255,255,0.08)_1px,transparent_1px)] [background-size:84px_84px]" />
    </div>

    <div class="relative z-10 flex min-h-screen">
      <aside class="w-[264px] border-r border-white/10 bg-black/20 backdrop-blur-xl">
        <div class="px-5 py-5">
          <div class="flex items-center gap-3">
            <div class="h-9 w-9 rounded-xl bg-[conic-gradient(from_220deg,rgba(47,125,255,0.9),rgba(29,214,164,0.9),rgba(47,125,255,0.9))]" />
            <div>
              <div class="text-sm font-semibold">能效碳效平台</div>
              <div class="text-[11px] tracking-[0.2em] text-zinc-400">CONTROL CONSOLE</div>
            </div>
          </div>
        </div>

        <div class="px-3">
          <a-menu
            :selectedKeys="[activeKey]"
            mode="inline"
            theme="dark"
            class="rounded-xl bg-transparent"
            @click="onMenuClick"
          >
            <a-menu-item key="dashboard">监控大屏</a-menu-item>
            <a-menu-item key="device">设备台账</a-menu-item>
          </a-menu>
        </div>

        <div class="mt-auto px-5 pb-5 pt-6 text-xs text-zinc-400">
          <div class="truncate">{{ auth.userInfo?.username }} · {{ auth.roles.join(', ') || '--' }}</div>
          <div class="mt-3 flex gap-2">
            <a-button size="small" type="primary" block @click="goDashboard">大屏</a-button>
            <a-button size="small" block @click="logout">退出</a-button>
          </div>
        </div>
      </aside>

      <main class="flex-1 px-6 py-6">
        <slot />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeKey = computed(() => {
  if (route.path.startsWith('/device')) return 'device'
  return 'dashboard'
})

function onMenuClick(e: { key: string }) {
  if (e.key === 'dashboard') router.push('/dashboard')
  if (e.key === 'device') router.push('/device')
}

function logout() {
  auth.logout()
  router.replace('/login')
}

function goDashboard() {
  router.push('/dashboard')
}
</script>

