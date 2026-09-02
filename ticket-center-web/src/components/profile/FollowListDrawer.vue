<script setup lang="ts">
import { ref, watch } from 'vue'
import { ChevronRight, RefreshCw, UserRound } from 'lucide-vue-next'
import { followApi } from '../../services/api'
import type { ApiId, FollowListTab, User } from '../../types/api'
import { getErrorMessage } from '../../utils/errors'
import { avatarOrFallback } from '../../utils/images'

const props = defineProps<{
  visible: boolean
  userId: ApiId
  tab: FollowListTab
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'update:tab': [value: FollowListTab]
}>()

const users = ref<User[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
let requestId = 0

async function loadUsers(page = 1) {
  const currentRequest = ++requestId
  loading.value = true
  error.value = ''
  try {
    const params = { current: page, size: pageSize }
    const result = props.tab === 'followees'
      ? await followApi.followees(props.userId, params)
      : await followApi.fans(props.userId, params)
    if (currentRequest !== requestId) return
    users.value = result.records || []
    total.value = result.total || 0
    currentPage.value = result.current || 1
  } catch (requestError) {
    if (currentRequest !== requestId) return
    users.value = []
    total.value = 0
    error.value = getErrorMessage(requestError, '关系列表加载失败，请稍后重试')
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

function changeTab(value: string | number) {
  emit('update:tab', value as FollowListTab)
}

watch(
  [() => props.visible, () => props.tab, () => props.userId],
  ([visible]) => {
    if (visible) {
      void loadUsers(1)
    } else {
      requestId++
    }
  },
)
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="关注关系"
    size="min(420px, 100%)"
    destroy-on-close
    append-to-body
    @update:model-value="emit('update:visible', $event)"
  >
    <el-tabs :model-value="tab" stretch @update:model-value="changeTab">
      <el-tab-pane label="关注" name="followees" />
      <el-tab-pane label="粉丝" name="fans" />
    </el-tabs>

    <div v-if="loading" class="follow-list-loading" aria-busy="true">
      <div v-for="index in 6" :key="index" class="follow-list-skeleton">
        <el-skeleton-item variant="circle" class="follow-list-skeleton__avatar" />
        <el-skeleton-item variant="text" class="follow-list-skeleton__name" />
      </div>
    </div>

    <div v-else-if="error" class="follow-list-error">
      <el-alert :title="error" type="error" show-icon :closable="false" />
      <el-button plain @click="loadUsers(currentPage)">
        <RefreshCw :size="16" aria-hidden="true" />
        重新加载
      </el-button>
    </div>

    <el-empty
      v-else-if="users.length === 0"
      :description="tab === 'followees' ? '还没有关注任何人' : '还没有粉丝'"
      :image-size="80"
    />

    <template v-else>
      <ul class="follow-list">
        <li v-for="user in users" :key="String(user.id)">
          <RouterLink
            class="follow-list-user"
            :to="`/people/${user.id}`"
            @click="emit('update:visible', false)"
          >
            <el-avatar :size="42" :src="avatarOrFallback(user.icon, user.id)">
              <UserRound :size="20" aria-hidden="true" />
            </el-avatar>
            <span>{{ user.nickName }}</span>
            <ChevronRight :size="18" aria-hidden="true" />
          </RouterLink>
        </li>
      </ul>

      <div v-if="total > pageSize" class="follow-list-pagination">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          small
          @current-change="loadUsers"
        />
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.follow-list-loading,
.follow-list-error {
  display: grid;
  gap: var(--space-3);
}

.follow-list-error {
  justify-items: start;
}

.follow-list-skeleton {
  min-height: 58px;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding-inline: var(--space-2);
}

.follow-list-skeleton__avatar {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
}

.follow-list-skeleton__name {
  width: 9rem;
}

.follow-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--color-border-subtle);
}

.follow-list-user {
  min-height: 64px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2);
  border-bottom: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
}

.follow-list-user:hover {
  color: var(--color-primary);
  background: var(--color-surface-hover);
}

.follow-list-user span {
  overflow-wrap: anywhere;
  color: var(--color-ink);
  font-weight: 600;
}

.follow-list-user > svg {
  color: var(--color-ink-faint);
}

.follow-list-pagination {
  display: flex;
  justify-content: center;
  margin-top: var(--space-5);
}
</style>
