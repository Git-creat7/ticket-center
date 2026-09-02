<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { Compass, Radio, Ticket, UserRound } from 'lucide-vue-next'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { avatarOrFallback } from '../../utils/images'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const navItems = [
  { to: '/discover', label: '发现', icon: Compass },
  { to: '/following', label: '动态', icon: Radio },
  { to: '/orders', label: '票夹', icon: Ticket },
  { to: '/me', label: '我的', icon: UserRound },
]

const activeNavPath = computed(() => {
  const current = navItems.find((item) => route.path.startsWith(item.to))
  return current ? current.to : '/discover'
})

const userAvatar = computed(() => avatarOrFallback(auth.user?.icon, auth.user?.id))

onMounted(() => {
  if (auth.isAuthenticated && !auth.user) {
    void auth.fetchCurrentUser()
  }
})

watch(
  () => auth.isAuthenticated,
  (isAuthenticated) => {
    if (!isAuthenticated && route.meta.requiresAuth) {
      void router.replace({ name: 'login', query: { redirect: route.fullPath } })
    }
  },
)
</script>

<template>
  <div v-if="route.meta.bare" class="bare-shell">
    <main id="main-content" tabindex="-1">
      <slot />
    </main>
  </div>

  <div v-else class="app-shell">
    <header class="desktop-header">
      <div class="desktop-header__inner page-container">
        <RouterLink class="brand" to="/discover" aria-label="Ticket Center 首页">
          <el-icon class="brand__icon" :size="20">
            <Ticket aria-hidden="true" />
          </el-icon>
          <span class="brand__title">Ticket Center</span>
        </RouterLink>

        <el-menu
          class="desktop-nav"
          :default-active="activeNavPath"
          mode="horizontal"
          :ellipsis="false"
          router
          aria-label="主导航"
        >
          <el-menu-item
            v-for="item in navItems.slice(0, 3)"
            :key="item.to"
            :index="item.to"
          >
            {{ item.label }}
          </el-menu-item>
        </el-menu>

        <div class="account-area">
          <el-button
            v-if="auth.isAuthenticated"
            class="account-button"
            text
            aria-label="进入个人中心"
            @click="router.push('/me')"
          >
            <el-avatar :size="32" :src="userAvatar" />
            <span class="account-button__name">{{ auth.user?.nickName || '我的' }}</span>
          </el-button>
          <el-button v-else type="primary" @click="router.push('/login')">
            登录
          </el-button>
        </div>
      </div>
    </header>

    <main id="main-content" class="app-main" tabindex="-1">
      <slot />
    </main>

    <el-menu
      class="mobile-nav"
      :default-active="activeNavPath"
      mode="horizontal"
      :ellipsis="false"
      router
      aria-label="移动端底部导航"
    >
      <el-menu-item v-for="item in navItems" :key="item.to" :index="item.to">
        <component :is="item.icon" :size="20" stroke-width="2" aria-hidden="true" />
        <span class="mobile-nav__label">{{ item.label }}</span>
      </el-menu-item>
    </el-menu>
  </div>
</template>

<style scoped lang="scss">
.bare-shell {
  min-height: 100dvh;
}

.desktop-header {
  display: none;
}

.app-main {
  min-height: 100dvh;
  padding-bottom: calc(5rem + env(safe-area-inset-bottom));
  outline: 0;
}

.mobile-nav {
  --el-menu-bg-color: var(--el-bg-color);
  --el-menu-hover-bg-color: var(--el-fill-color-light);
  --el-menu-active-color: var(--el-color-primary);
  --el-menu-horizontal-height: 3.5rem;

  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: var(--z-sticky);
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  min-height: calc(4rem + env(safe-area-inset-bottom));
  padding: var(--space-1) max(var(--space-2), env(safe-area-inset-right))
    max(var(--space-1), env(safe-area-inset-bottom))
    max(var(--space-2), env(safe-area-inset-left));
  border-right: 0;
  border-bottom: 0;
  border-left: 0;
  border-top: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);

  :deep(.el-menu-item) {
    min-width: 44px;
    height: 3.5rem;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    padding: 0;
    border-bottom: 0;
    color: var(--el-text-color-secondary);
    font-size: var(--text-caption);
    line-height: 1;
  }

  :deep(.el-menu-item:hover),
  :deep(.el-menu-item:focus) {
    color: var(--el-color-primary);
    background: var(--el-fill-color-light);
  }

  :deep(.el-menu-item.is-active) {
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
    border-bottom: 0;
  }
}

.mobile-nav__label {
  line-height: 1.2;
}

@media (min-width: 48rem) {
  .desktop-header {
    position: sticky;
    top: 0;
    z-index: var(--z-sticky);
    display: block;
    border-bottom: 1px solid var(--el-border-color-light);
    background: var(--el-bg-color);

    &__inner {
      min-height: 4rem;
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: center;
      gap: var(--space-6);
    }
  }

  .brand {
    display: inline-flex;
    align-items: center;
    gap: var(--space-3);
    text-decoration: none;

    &__icon {
      width: 32px;
      height: 32px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: var(--el-border-radius-base);
      color: #ffffff;
      background: var(--el-color-primary);
    }

    &__title {
      color: var(--el-text-color-primary);
      font-size: var(--text-subheading);
      font-weight: 600;
      letter-spacing: 0;
    }
  }

  .desktop-nav {
    --el-menu-bg-color: transparent;
    --el-menu-hover-bg-color: var(--el-fill-color-light);
    --el-menu-active-color: var(--el-color-primary);
    --el-menu-horizontal-height: 4rem;

    min-width: 0;
    border-bottom: 0;

    :deep(.el-menu-item) {
      padding-inline: var(--space-5);
      font-size: var(--text-secondary);
    }
  }

  .account-area {
    display: flex;
    align-items: center;
    justify-content: flex-end;
  }

  .account-button {
    height: 40px;
    padding: 4px var(--space-3) 4px 4px;
    color: var(--el-text-color-primary);
  }

  .account-button__name {
    max-width: 8rem;
    margin-left: var(--space-2);
    overflow: hidden;
    font-size: var(--text-secondary);
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .app-main {
    min-height: calc(100dvh - 4rem);
    padding-bottom: 0;
  }

  .mobile-nav {
    display: none;
  }
}
</style>
