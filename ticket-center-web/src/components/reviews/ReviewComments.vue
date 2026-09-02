<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { reviewApi } from '../../services/api'
import { useAuthStore } from '../../stores/auth'
import { useNotificationStore } from '../../stores/notifications'
import type { ApiId, EventReviewComment } from '../../types/api'
import { getErrorMessage } from '../../utils/errors'
import { formatDateTime } from '../../utils/format'
import { avatarOrFallback } from '../../utils/images'

const props = defineProps<{
  reviewId: ApiId
}>()

const emit = defineEmits<{
  countChange: [count: number]
}>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

const comments = ref<EventReviewComment[]>([])
const total = ref(0)
const currentPage = ref(1)
const loading = ref(false)
const error = ref('')
const content = ref('')
const submitting = ref(false)
const deletingId = ref<ApiId | null>(null)
const pageSize = 10
let requestId = 0

const canSubmit = computed(
  () => content.value.trim().length > 0 && content.value.trim().length <= 500,
)

function isOwnComment(comment: EventReviewComment): boolean {
  return auth.user != null && String(auth.user.id) === String(comment.userId)
}

function goToLogin() {
  void router.push({ name: 'login', query: { redirect: route.fullPath } })
}

async function loadComments(page = 1) {
  const activeRequest = ++requestId
  loading.value = true
  error.value = ''
  try {
    const result = await reviewApi.comments(props.reviewId, { current: page, size: pageSize })
    if (activeRequest !== requestId) return
    comments.value = result.records || []
    total.value = result.total || 0
    currentPage.value = result.current || 1
    emit('countChange', total.value)
  } catch (requestError) {
    if (activeRequest !== requestId) return
    error.value = getErrorMessage(requestError, '评论加载失败，请稍后重试')
  } finally {
    if (activeRequest === requestId) loading.value = false
  }
}

async function submitComment() {
  if (!auth.isAuthenticated) {
    goToLogin()
    return
  }
  if (!canSubmit.value || submitting.value) return

  submitting.value = true
  try {
    await reviewApi.createComment(props.reviewId, content.value.trim())
    content.value = ''
    await loadComments(1)
    notifications.notify('评论已发布', 'success')
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '评论发布失败，请重试'), 'error')
  } finally {
    submitting.value = false
  }
}

async function removeComment(comment: EventReviewComment) {
  if (!isOwnComment(comment) || deletingId.value != null) return
  deletingId.value = comment.id
  try {
    await reviewApi.removeComment(comment.id)
    const nextTotal = Math.max(0, total.value - 1)
    const lastPage = Math.max(1, Math.ceil(nextTotal / pageSize))
    await loadComments(Math.min(currentPage.value, lastPage))
    notifications.notify('评论已删除', 'success')
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '评论删除失败，请重试'), 'error')
  } finally {
    deletingId.value = null
  }
}

watch(
  () => String(props.reviewId),
  () => {
    content.value = ''
    comments.value = []
    total.value = 0
    void loadComments(1)
  },
  { immediate: true },
)
</script>

<template>
  <section id="review-comments" class="review-comments" aria-labelledby="review-comments-title">
    <header class="review-comments__heading">
      <h2 id="review-comments-title">评论</h2>
      <span>{{ total }} 条</span>
    </header>

    <form v-if="auth.isAuthenticated" class="comment-composer" @submit.prevent="submitComment">
      <el-input
        v-model="content"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 6 }"
        maxlength="500"
        show-word-limit
        resize="none"
        placeholder="写下你的评论"
        aria-label="评论内容"
      />
      <div class="comment-composer__actions">
        <el-button
          type="primary"
          native-type="submit"
          :disabled="!canSubmit"
          :loading="submitting"
        >
          发布评论
        </el-button>
      </div>
    </form>

    <div v-else class="comment-login">
      <span>登录后可以参与评论</span>
      <el-button type="primary" plain @click="goToLogin">去登录</el-button>
    </div>

    <div v-if="loading" class="comment-loading" aria-busy="true">
      <div v-for="index in 3" :key="index" class="comment-loading__item">
        <el-skeleton animated :rows="2" />
      </div>
    </div>

    <div v-else-if="error" class="comment-error">
      <el-alert :title="error" type="error" show-icon :closable="false" />
      <el-button type="primary" plain @click="loadComments(currentPage)">重新加载</el-button>
    </div>

    <el-empty
      v-else-if="comments.length === 0"
      description="还没有评论"
      :image-size="72"
    />

    <template v-else>
      <ul class="comment-list">
        <li v-for="comment in comments" :key="String(comment.id)" class="comment-item">
          <RouterLink :to="`/people/${comment.userId}`" class="comment-item__avatar">
            <el-avatar
              :size="36"
              :src="avatarOrFallback(comment.userIcon, comment.userId)"
              :alt="`${comment.userName}的头像`"
            />
          </RouterLink>

          <div class="comment-item__body">
            <div class="comment-item__meta">
              <RouterLink :to="`/people/${comment.userId}`">
                {{ comment.userName || '用户' }}
              </RouterLink>
              <time :datetime="comment.createTime.replace(' ', 'T')">
                {{ formatDateTime(comment.createTime) }}
              </time>
            </div>
            <p>{{ comment.content }}</p>
          </div>

          <el-popconfirm
            v-if="isOwnComment(comment)"
            title="确定删除这条评论吗？"
            confirm-button-text="删除"
            cancel-button-text="取消"
            confirm-button-type="danger"
            @confirm="removeComment(comment)"
          >
            <template #reference>
              <el-button
                class="comment-item__delete"
                text
                circle
                :icon="Trash2"
                :loading="String(deletingId) === String(comment.id)"
                aria-label="删除评论"
              />
            </template>
          </el-popconfirm>
        </li>
      </ul>

      <el-pagination
        v-if="total > pageSize"
        v-model:current-page="currentPage"
        class="comment-pagination"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        @current-change="loadComments"
      />
    </template>
  </section>
</template>

<style scoped>
.review-comments {
  display: grid;
  gap: var(--space-5);
  margin-top: var(--space-10);
  padding-top: var(--space-6);
  border-top: 1px solid var(--color-border);
}

.review-comments__heading,
.comment-composer__actions,
.comment-login,
.comment-item__meta {
  display: flex;
  align-items: center;
}

.review-comments__heading {
  justify-content: space-between;
  gap: var(--space-4);
}

.review-comments__heading h2 {
  font-size: var(--text-section);
}

.review-comments__heading span,
.comment-login,
.comment-item__meta time {
  color: var(--color-ink-muted);
  font-size: var(--text-secondary);
}

.comment-composer,
.comment-loading,
.comment-error {
  display: grid;
  gap: var(--space-3);
}

.comment-composer__actions {
  justify-content: flex-end;
}

.comment-login {
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-4);
  background: var(--color-surface-muted);
  border-radius: var(--radius-sm);
}

.comment-loading__item,
.comment-item {
  padding-block: var(--space-4);
  border-bottom: 1px solid var(--color-border-subtle);
}

.comment-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.comment-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--space-3);
}

.comment-item__avatar {
  line-height: 0;
}

.comment-item__body {
  min-width: 0;
  display: grid;
  gap: var(--space-2);
}

.comment-item__meta {
  flex-wrap: wrap;
  gap: var(--space-2);
}

.comment-item__meta a {
  color: var(--color-ink);
  font-size: var(--text-secondary);
  font-weight: 700;
}

.comment-item__meta a:hover {
  color: var(--color-primary);
}

.comment-item p {
  max-width: 72ch;
  color: var(--color-ink-soft);
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.comment-item__delete {
  color: var(--color-ink-muted);
}

.comment-item__delete:hover {
  color: var(--color-danger);
}

.comment-pagination {
  justify-self: center;
}

@media (max-width: 36rem) {
  .comment-login {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
