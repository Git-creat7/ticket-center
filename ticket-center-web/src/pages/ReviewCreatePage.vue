<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { UploadFile } from 'element-plus'
import { ArrowLeft, ImagePlus, Trash2 } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import EventImage from '../components/events/EventImage.vue'
import EmptyState from '../components/ui/EmptyState.vue'
import { eventApi, reviewApi, uploadApi } from '../services/api'
import { useNotificationStore } from '../stores/notifications'
import type { EventDetail } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { imageOrFallback, joinImages } from '../utils/images'

const route = useRoute()
const router = useRouter()
const notifications = useNotificationStore()

const event = ref<EventDetail | null>(null)
const title = ref('')
const content = ref('')
const images = ref<string[]>([])
const loadingEvent = ref(true)
const uploading = ref(false)
const submitting = ref(false)
const formError = ref('')

const eventId = computed(() => {
  const value = route.query.eventId
  return typeof value === 'string' && value ? value : null
})

async function loadEvent() {
  if (!eventId.value) {
    loadingEvent.value = false
    return
  }
  try {
    event.value = await eventApi.getById(eventId.value)
  } catch (requestError) {
    notifications.notify(getErrorMessage(requestError, '活动信息加载失败'), 'error')
  } finally {
    loadingEvent.value = false
  }
}

async function uploadSelectedFile(file: File) {
  if (!file.type.startsWith('image/')) {
    notifications.notify('请选择 JPG、PNG、GIF 或 WebP 图片', 'error')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    notifications.notify('单张图片不能超过 10 MB', 'error')
    return
  }
  if (images.value.length >= 6) {
    notifications.notify('最多上传 6 张图片', 'info')
    return
  }

  uploading.value = true
  try {
    images.value.push(await uploadApi.image(file))
    notifications.notify('图片上传完成', 'success')
  } catch {
    notifications.notify('图片服务暂不可用，可以先发布纯文字评价', 'error')
  } finally {
    uploading.value = false
  }
}

function handleUploadChange(uploadFile: UploadFile) {
  if (uploadFile.raw) void uploadSelectedFile(uploadFile.raw)
}

async function removeImage(url: string) {
  images.value = images.value.filter((image) => image !== url)
  try {
    await uploadApi.deleteImage(url)
  } catch {
    // 评价已不再引用该图片，远端清理失败不阻断编辑。
  }
}

async function submitReview() {
  formError.value = ''
  if (!eventId.value) return
  if (!title.value.trim()) {
    formError.value = '请填写评价标题'
    return
  }
  if (!content.value.trim()) {
    formError.value = '请写下你的真实体验'
    return
  }
  if (title.value.length > 255 || content.value.length > 2048) {
    formError.value = '标题或正文超过字数限制，请精简后重试'
    return
  }

  submitting.value = true
  try {
    const id = await reviewApi.create({
      eventId: eventId.value,
      title: title.value.trim(),
      content: content.value.trim(),
      images: joinImages(images.value) || undefined,
    })
    notifications.notify('评价已发布', 'success')
    await router.replace(`/reviews/${id}`)
  } catch (requestError) {
    formError.value = getErrorMessage(requestError, '评价发布失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadEvent)
</script>

<template>
  <div class="page-container review-create">
    <el-button text :icon="ArrowLeft" class="review-create__back" @click="router.back()">
      返回
    </el-button>

    <el-skeleton v-if="loadingEvent" :rows="4" animated class="review-create__loading" />

    <EmptyState
      v-else-if="!eventId || !event"
      title="请选择要评价的活动"
      description="从活动详情进入发布页，评价才能关联到正确活动。"
    >
      <RouterLink to="/discover"><el-button type="primary">浏览活动</el-button></RouterLink>
    </EmptyState>

    <template v-else>
      <header class="review-create__header">
        <div class="review-create__poster">
          <EventImage :src="event.mainImage" :alt="event.name" :category-name="event.categoryName" eager />
        </div>
        <div>
          <span class="review-create__label">评价活动</span>
          <h1>{{ event.name }}</h1>
        </div>
      </header>

      <el-form label-position="top" class="review-form" @submit.prevent="submitReview">
        <el-form-item label="标题" required>
          <el-input
            v-model="title"
            maxlength="255"
            show-word-limit
            clearable
            placeholder="一句话概括这次体验"
          />
        </el-form-item>

        <el-form-item label="正文" required>
          <el-input
            v-model="content"
            type="textarea"
            :rows="8"
            maxlength="2048"
            show-word-limit
            resize="vertical"
            placeholder="现场氛围、视野、交通和服务怎么样？"
          />
        </el-form-item>

        <el-form-item label="现场图片">
          <div class="review-images">
            <div v-if="images.length" class="review-images__grid">
              <div v-for="image in images" :key="image" class="review-images__item">
                <img :src="imageOrFallback(image)" alt="待发布的评价图片" />
                <el-button
                  class="review-images__remove"
                  type="danger"
                  circle
                  :icon="Trash2"
                  aria-label="移除图片"
                  @click="removeImage(image)"
                />
              </div>
            </div>

            <el-upload
              :show-file-list="false"
              :auto-upload="false"
              accept="image/jpeg,image/png,image/gif,image/webp"
              :disabled="uploading || images.length >= 6"
              :on-change="handleUploadChange"
            >
              <el-button :icon="ImagePlus" :loading="uploading" :disabled="images.length >= 6">
                {{ images.length >= 6 ? '已达 6 张上限' : '添加图片' }}
              </el-button>
            </el-upload>
          </div>
        </el-form-item>

        <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" />

        <div class="review-form__actions">
          <el-button type="primary" native-type="submit" :loading="submitting" :disabled="uploading">
            发布评价
          </el-button>
        </div>
      </el-form>
    </template>
  </div>
</template>

<style scoped>
.review-create {
  max-width: 48rem;
  padding-block: var(--space-4) var(--space-16);
}

.review-create__back {
  margin-bottom: var(--space-4);
}

.review-create__loading {
  padding: var(--space-6);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

.review-create__header {
  display: grid;
  grid-template-columns: 7rem 1fr;
  align-items: center;
  gap: var(--space-5);
  padding-bottom: var(--space-5);
  border-bottom: 1px solid var(--color-border);
}

.review-create__poster {
  width: 7rem;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  border-radius: var(--radius-sm);
}

.review-create__label {
  color: var(--color-ink-muted);
  font-size: var(--text-secondary);
}

.review-create__header h1 {
  margin-top: var(--space-2);
  font-size: var(--text-section);
}

.review-form {
  margin-top: var(--space-8);
}

.review-images {
  width: 100%;
  display: grid;
  gap: var(--space-3);
}

.review-images__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-2);
}

.review-images__item {
  position: relative;
  aspect-ratio: 1;
}

.review-images__item img {
  width: 100%;
  height: 100%;
  border-radius: var(--radius-sm);
  object-fit: cover;
}

.review-images__remove {
  position: absolute;
  top: var(--space-2);
  right: var(--space-2);
}

.review-form__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-5);
}

@media (min-width: 48rem) {
  .review-create {
    padding-block: var(--space-8) var(--space-16);
  }

  .review-images__grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 30rem) {
  .review-create__header {
    grid-template-columns: 5.5rem 1fr;
  }

  .review-create__poster {
    width: 5.5rem;
  }

  .review-form__actions :deep(.el-button) {
    width: 100%;
  }
}
</style>
