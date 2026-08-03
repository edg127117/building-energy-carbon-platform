<template>
  <!--
    前端 403 提示页只提供导航和本地退出操作，不代表后端授权结果可以由页面修改；
    受保护接口仍以服务端角色和建筑范围校验为准。
  -->
  <div class="min-h-screen bg-[#070B14] px-6 py-14 text-zinc-100">
    <div class="mx-auto max-w-[720px]">
      <div class="rounded-3xl border border-white/10 bg-white/[0.04] p-7 backdrop-blur-xl">
        <div class="text-sm tracking-[0.22em] text-zinc-400">403</div>
        <div class="mt-2 text-2xl font-semibold">无权限访问</div>
        <div class="mt-3 text-sm leading-6 text-zinc-400">
          当前账号缺少访问该页面/操作的权限。请使用管理员账号登录，或联系管理员为你授予对应角色。
        </div>
        <div class="mt-6 flex gap-3">
          <a-button type="primary" @click="goDashboard">返回大屏</a-button>
          <a-button @click="goLogin">重新登录</a-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const auth = useAuthStore()

function goDashboard() {
  router.push('/dashboard')
}

function goLogin() {
  auth.logout()
  router.replace('/login')
}
</script>
