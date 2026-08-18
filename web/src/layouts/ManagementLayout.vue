<template>
  <!-- 管理壳只消费受控菜单和认证状态，业务页面通过唯一 RouterView 独立加载。 -->
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <RouterLink class="admin-brand" to="/hvac-demo">
        <span class="admin-brand-mark"><Snowflake :size="18" /></span>
        <span><strong>经常性调适平台</strong><small>后台管理</small></span>
      </RouterLink>

      <nav class="admin-navigation" aria-label="后台管理导航">
        <div v-if="menu.loading && !menu.loaded" class="admin-nav-state">正在加载菜单…</div>
        <div v-else-if="menu.error && !menu.loaded" class="admin-nav-state is-error">
          <span>{{ menu.error }}</span>
          <button type="button" @click="retryMenu">重新加载</button>
        </div>
        <div v-else-if="managementGroups.length === 0" class="admin-nav-state">当前账号没有已接入的管理入口</div>
        <section v-for="group in managementGroups" :key="group.id" class="admin-nav-group">
          <div class="admin-nav-group-title">{{ group.label }}</div>
          <RouterLink
            v-for="item in leafItems(group)"
            :key="item.id"
            :to="item.path!"
            class="admin-nav-link"
            :class="{ 'is-active': route.path === item.path }"
          >
            <component :is="iconFor(item.path)" :size="17" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </section>
      </nav>

      <div class="admin-sidebar-foot">
        <RouterLink to="/hvac-demo" class="admin-return"><Gauge :size="16" />返回 HVAC 大屏</RouterLink>
      </div>
    </aside>

    <section class="admin-workspace">
      <header class="admin-topbar">
        <div><span>系统后台</span><small>仅平台管理员可访问</small></div>
        <div class="admin-account">
          <span class="admin-avatar">{{ accountInitial }}</span>
          <span><strong>{{ auth.userInfo?.username ?? '管理员' }}</strong><small>平台管理员</small></span>
          <button data-test="logout" type="button" @click="logout"><LogOut :size="16" />退出</button>
        </div>
      </header>
      <div v-if="menu.error && menu.loaded" class="admin-inline-warning">
        菜单刷新失败，当前仍显示最近一次成功结果。<button type="button" @click="retryMenu">重试</button>
      </div>
      <main class="admin-content"><RouterView /></main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Building2, Cpu, Gauge, KeyRound, ListPlus, LogOut, Menu, Network, PackageSearch, Snowflake, UserRoundCog } from 'lucide-vue-next'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import type { AdminNavigationItem } from '@/domain/adminNavigation'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const menu = useMenuStore()

const managementGroups = computed(() => menu.navigation.filter((item) => leafItems(item).some((leaf) => leaf.admin)))
const accountInitial = computed(() => (auth.userInfo?.username || 'A').slice(0, 1).toUpperCase())

function leafItems(item: AdminNavigationItem): AdminNavigationItem[] {
  const own = item.path && item.admin ? [item] : []
  return [...own, ...item.children.flatMap(leafItems)]
}

function iconFor(path?: string) {
  if (path === '/system/users') return UserRoundCog
  if (path === '/system/roles') return KeyRound
  if (path === '/system/building-access') return Building2
  if (path === '/system/buildings') return Network
  if (path === '/system/devices') return Cpu
  if (path === '/system/device-products') return PackageSearch
  if (path === '/system/device-onboarding') return ListPlus
  return Menu
}

function retryMenu() {
  void menu.reload().catch(() => undefined)
}

function logout() {
  auth.logout()
  menu.clear()
  void router.push('/login')
}

onMounted(() => {
  void menu.ensureLoaded().catch(() => undefined)
})
</script>
