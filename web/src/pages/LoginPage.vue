<template>
  <!--
    认证入口通过 auth Store 串联 /auth/register、/auth/login 和 /auth/me。
    注册账号只有 BUILDING_OWNER 角色且没有建筑范围；登录成功后按 redirect 参数或
    /hvac-demo 进入应用。页面只负责交互，JWT 与角色的最终校验由后端完成。
  -->
  <div class="relative min-h-screen bg-[#070B14] text-zinc-100">
    <div class="pointer-events-none absolute inset-0">
      <div class="absolute inset-0 bg-[radial-gradient(1200px_700px_at_20%_10%,rgba(47,125,255,0.24),transparent_60%),radial-gradient(900px_520px_at_85%_35%,rgba(29,214,164,0.12),transparent_65%),radial-gradient(800px_560px_at_55%_92%,rgba(255,77,109,0.10),transparent_70%)]" />
      <div
        class="absolute inset-0 opacity-[0.12] [background-image:linear-gradient(to_right,rgba(255,255,255,0.10)_1px,transparent_1px),linear-gradient(to_bottom,rgba(255,255,255,0.10)_1px,transparent_1px)] [background-size:64px_64px]"
      />
      <div class="absolute inset-0 [mask-image:radial-gradient(55%_55%_at_50%_35%,black,transparent)]">
        <div class="absolute -inset-24 bg-[conic-gradient(from_190deg_at_50%_50%,rgba(47,125,255,0.25),rgba(29,214,164,0.12),rgba(255,77,109,0.12),rgba(47,125,255,0.25))] animate-[spin_14s_linear_infinite]" />
      </div>
    </div>

    <div class="relative mx-auto flex min-h-screen max-w-[1120px] items-center px-6">
      <div class="grid w-full grid-cols-1 gap-10 md:grid-cols-2">
        <div class="flex flex-col justify-center">
          <div class="flex items-center gap-3">
            <div class="h-10 w-10 rounded-2xl bg-[conic-gradient(from_220deg,rgba(47,125,255,0.95),rgba(29,214,164,0.95),rgba(255,77,109,0.90),rgba(47,125,255,0.95))]" />
            <div>
              <div class="text-sm font-semibold tracking-wide">能效碳效智慧管控平台</div>
              <div class="text-[11px] tracking-[0.22em] text-zinc-400">HIGH-CONCURRENCY · REALTIME · RBAC</div>
            </div>
          </div>
          <div class="mt-7 text-3xl font-semibold leading-tight text-zinc-50">
            让中央空调数据可追溯，让调适指标可复核。
          </div>
          <div class="mt-4 max-w-[420px] text-sm leading-6 text-zinc-400">
            V1 聚焦 HVAC 19 测点采集、数据质量、四项指标计算、建筑权限和查询能力，不提供设备控制。
          </div>
          <div class="mt-8 flex gap-3">
            <div class="rounded-xl border border-white/10 bg-black/20 px-4 py-3 text-xs text-zinc-300">
              API: <span class="text-zinc-100">{{ apiBase }}</span>
            </div>
            <div class="rounded-xl border border-white/10 bg-black/20 px-4 py-3 text-xs text-zinc-300">
              范围: <span class="text-zinc-100">HVAC V1</span>
            </div>
          </div>
        </div>

        <div class="flex items-center md:justify-end">
          <div
            class="w-full max-w-[420px] overflow-hidden rounded-3xl border border-white/10 bg-white/[0.04] shadow-[0_0_0_1px_rgba(255,255,255,0.04),0_18px_70px_rgba(0,0,0,0.55)] backdrop-blur-xl"
          >
            <div class="p-6">
              <div class="mb-5">
                <div class="text-sm font-semibold text-zinc-50">登录 / 注册</div>
                <div class="mt-1 text-xs text-zinc-400">使用 JWT Token + RBAC 骨架，适合 Demo 快速演示。</div>
              </div>

              <a-segmented v-model:value="mode" :options="modes" block class="mb-5" />

              <a-form layout="vertical" :model="form" @finish="onSubmit">
                <a-form-item label="账号" name="username" :rules="[{ required: true, message: '请输入账号' }]">
                  <a-input v-model:value="form.username" placeholder="例如：admin / user_demo" autocomplete="username" />
                </a-form-item>
                <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
                  <a-input-password v-model:value="form.password" placeholder="默认：123456" autocomplete="current-password" />
                </a-form-item>
                <a-form-item
                  v-if="mode === 'register'"
                  label="昵称"
                  name="nickname"
                  :rules="[{ required: true, message: '请输入昵称' }]"
                >
                  <a-input v-model:value="form.nickname" placeholder="用于页面显示" autocomplete="nickname" />
                </a-form-item>

                <a-button type="primary" html-type="submit" :loading="loading" block size="large">
                  {{ mode === 'login' ? '登录进入平台' : '注册并登录' }}
                </a-button>

                <div class="mt-4 grid grid-cols-2 gap-3 text-xs text-zinc-400">
                  <div class="rounded-xl border border-white/10 bg-black/20 px-3 py-2">
                    管理员：<span class="text-zinc-100">admin / 123456</span>
                  </div>
                  <div class="rounded-xl border border-white/10 bg-black/20 px-3 py-2">
                    注册账号：默认 <span class="text-zinc-100">BUILDING_OWNER</span>，不自动授予建筑
                  </div>
                </div>
              </a-form>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const modes = [
  { label: '登录', value: 'login' },
  { label: '注册', value: 'register' },
]

const form = reactive({
  username: '',
  password: '',
  nickname: '',
})

const loading = ref(false)

const apiBase = computed(() => import.meta.env.VITE_API_BASE ?? 'http://localhost:8081/api')
/**
 * 提交登录或“先注册后登录”流程。
 * 成功后保留原受保护页面 redirect；失败由 API/Store 抛出并在表单显示，不自行伪造会话。
 */
async function onSubmit() {
  loading.value = true
  try {
    if (mode.value === 'register') {
      await auth.register({ username: form.username, password: form.password, nickname: form.nickname })
    }
    await auth.login({ username: form.username, password: form.password })
    message.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/hvac-demo'
    router.replace(redirect)
  } catch (error: unknown) {
    const errorMessage = error instanceof Error && error.message
      ? error.message
      : '操作失败'
    message.error(errorMessage)
  } finally {
    loading.value = false
  }
}
</script>
