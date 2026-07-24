<template>
  <div class="min-h-screen bg-[#070B14] text-zinc-100">
    <div class="pointer-events-none fixed inset-0">
      <div class="absolute inset-0 bg-[radial-gradient(1100px_600px_at_20%_10%,rgba(47,125,255,0.22),transparent_60%),radial-gradient(900px_520px_at_80%_30%,rgba(29,214,164,0.12),transparent_65%),radial-gradient(800px_540px_at_55%_95%,rgba(255,77,109,0.10),transparent_70%)]" />
      <div class="absolute inset-0 opacity-[0.12] [background-image:linear-gradient(to_right,rgba(255,255,255,0.08)_1px,transparent_1px),linear-gradient(to_bottom,rgba(255,255,255,0.08)_1px,transparent_1px)] [background-size:72px_72px]" />
      <div class="absolute inset-0 bg-[radial-gradient(circle_at_50%_50%,rgba(255,255,255,0.06),transparent_52%)]" />
    </div>

    <header class="relative z-10 border-b border-white/10 bg-black/20 backdrop-blur-xl">
      <div class="mx-auto flex max-w-[1400px] items-center justify-between px-6 py-4">
        <div class="flex items-center gap-3">
          <div class="h-9 w-9 rounded-xl bg-[conic-gradient(from_200deg,rgba(47,125,255,0.9),rgba(29,214,164,0.9),rgba(255,77,109,0.85),rgba(47,125,255,0.9))]" />
          <div>
            <div class="text-sm font-semibold tracking-wide">能效碳效智慧管控平台</div>
            <div class="text-[11px] tracking-[0.22em] text-zinc-400">REALTIME ENERGY INTEL</div>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <div class="hidden text-xs text-zinc-400 md:block">
            {{ auth.userInfo?.username }} · {{ auth.roles.join(', ') || '--' }}
          </div>
          <a-button size="small" type="primary" @click="goDevice">设备台账</a-button>
          <a-button size="small" @click="logout">退出</a-button>
        </div>
      </div>
    </header>

    <main class="relative z-10 mx-auto max-w-[1400px] px-6 py-6">
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const auth = useAuthStore()

function logout() {
  auth.logout()
  router.replace('/login')
}

function goDevice() {
  router.push('/device')
}
</script>

