<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { Camera, Loader2, MapPin, Upload, UserRound, X } from 'lucide-vue-next'

import { uploadApi, userApi } from '../../services/api'
import { useAuthStore } from '../../stores/auth'
import { useNotificationStore } from '../../stores/notifications'
import type { User, UserInfo, UserProfileUpdateRequest } from '../../types/api'
import { getErrorMessage } from '../../utils/errors'
import { avatarOrFallback } from '../../utils/images'

const props = defineProps<{
  visible: boolean
  user: User | null
  info: UserInfo | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'saved'): void
}>()

const auth = useAuthStore()
const notifications = useNotificationStore()

const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadingAvatar = ref(false)
const submitting = ref(false)
const errorMessage = ref('')

const form = reactive({
  nickName: '',
  icon: '',
  city: '',
  gender: null as boolean | null,
  birthday: '',
  introduce: '',
})

// 性别选项
const genderOptions = [
  { label: '保密', value: null },
  { label: '男', value: true },
  { label: '女', value: false },
]

function resetForm() {
  if (props.user) {
    form.nickName = props.user.nickName || ''
    form.icon = props.user.icon || ''
  }
  if (props.info) {
    form.city = props.info.city || ''
    form.gender = props.info.gender
    form.birthday = props.info.birthday || ''
    form.introduce = props.info.introduce || ''
  } else {
    form.city = ''
    form.gender = null
    form.birthday = ''
    form.introduce = ''
  }
  errorMessage.value = ''
  uploadingAvatar.value = false
}

watch(
  () => props.visible,
  (val) => {
    if (val) {
      resetForm()
    }
  },
  { immediate: true }
)

function triggerFileInput() {
  if (uploadingAvatar.value) return
  fileInputRef.value?.click()
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  // 格式校验
  const validTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  if (!validTypes.includes(file.type)) {
    errorMessage.value = '仅支持 JPG、PNG、WebP、GIF 格式的图片'
    target.value = ''
    return
  }

  // 大小校验 (5MB)
  if (file.size > 5 * 1024 * 1024) {
    errorMessage.value = '图片大小不能超过 5MB'
    target.value = ''
    return
  }

  uploadingAvatar.value = true
  errorMessage.value = ''

  try {
    const uploadedUrl = await uploadApi.image(file)
    form.icon = uploadedUrl
    notifications.notify('头像上传成功', 'success')
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '头像上传失败，请稍后重试')
  } finally {
    uploadingAvatar.value = false
    target.value = ''
  }
}

function closeDialog() {
  emit('update:visible', false)
}

async function handleSubmit() {
  if (!form.nickName.trim()) {
    errorMessage.value = '昵称不能为空'
    return
  }
  if (form.nickName.trim().length > 20) {
    errorMessage.value = '昵称长度不能超过 20 个字符'
    return
  }
  if (form.introduce && form.introduce.length > 255) {
    errorMessage.value = '个人介绍不能超过 255 个字符'
    return
  }

  submitting.value = true
  errorMessage.value = ''

  try {
    const payload: UserProfileUpdateRequest = {
      nickName: form.nickName.trim(),
      icon: form.icon.trim() || undefined,
      city: form.city.trim() || undefined,
      gender: form.gender,
      birthday: form.birthday || undefined,
      introduce: form.introduce.trim() || undefined,
    }

    await userApi.updateProfile(payload)
    await auth.fetchCurrentUser()
    notifications.notify('个人资料修改成功', 'success')
    emit('saved')
    closeDialog()
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '修改失败，请重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="编辑个人资料"
    width="520px"
    class="edit-profile-dialog"
    destroy-on-close
    align-center
    :show-close="false"
    @update:model-value="emit('update:visible', $event)"
  >
    <template #header>
      <div class="dialog-header">
        <div class="dialog-header__title">
          <span class="header-main-title">编辑个人资料</span>
          <span class="header-sub-title">修改您的公开昵称、头像与个人介绍</span>
        </div>
        <button
          type="button"
          class="dialog-close-btn"
          aria-label="关闭"
          @click="closeDialog"
        >
          <X :size="18" />
        </button>
      </div>
    </template>

    <div class="edit-profile-body">
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
        class="form-alert"
      />

      <!-- 隐藏的文件选择器 -->
      <input
        ref="fileInputRef"
        type="file"
        accept="image/png,image/jpeg,image/webp,image/gif"
        style="display: none"
        @change="handleFileChange"
      />

      <!-- 头像上传区域 (纯文件上传交互) -->
      <div class="avatar-upload-card">
        <div
          class="avatar-touch-target"
          role="button"
          tabindex="0"
          title="点击更换头像"
          @click="triggerFileInput"
        >
          <el-avatar :size="80" :src="avatarOrFallback(form.icon, user?.id)" class="avatar-img">
            <UserRound :size="36" aria-hidden="true" />
          </el-avatar>

          <!-- 悬浮微遮罩 / 上传中加载指示 -->
          <div class="avatar-hover-overlay" :class="{ 'avatar-hover-overlay--loading': uploadingAvatar }">
            <Loader2 v-if="uploadingAvatar" :size="22" class="spin-icon" />
            <Camera v-else :size="20" class="camera-icon" />
          </div>
        </div>

        <div class="avatar-action-info">
          <el-button
            type="primary"
            plain
            size="small"
            class="select-img-btn"
            :loading="uploadingAvatar"
            @click="triggerFileInput"
          >
            <Upload v-if="!uploadingAvatar" :size="14" aria-hidden="true" />
            <span>{{ uploadingAvatar ? '正在上传...' : '上传新头像' }}</span>
          </el-button>
          <p class="avatar-tip">支持 JPG、PNG、WebP 格式，小于 5MB</p>
        </div>
      </div>

      <!-- 基础信息表单 -->
      <div class="form-row form-row--2col">
        <div class="form-item">
          <label class="form-label" for="nickname-input">昵称 <span class="required-star">*</span></label>
          <el-input
            id="nickname-input"
            v-model="form.nickName"
            maxlength="20"
            show-word-limit
            placeholder="输入您的昵称"
          />
        </div>

        <div class="form-item">
          <label class="form-label" for="city-input">常居城市</label>
          <el-input
            id="city-input"
            v-model="form.city"
            maxlength="30"
            placeholder="如：北京、上海"
          >
            <template #prefix>
              <MapPin :size="15" class="input-icon" />
            </template>
          </el-input>
        </div>
      </div>

      <div class="form-row form-row--2col">
        <div class="form-item">
          <label class="form-label">性别</label>
          <div class="gender-segmented-group">
            <button
              v-for="opt in genderOptions"
              :key="String(opt.value)"
              type="button"
              class="gender-pill"
              :class="{ 'gender-pill--active': form.gender === opt.value }"
              @click="form.gender = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>

        <div class="form-item">
          <label class="form-label">生日</label>
          <el-date-picker
            v-model="form.birthday"
            type="date"
            placeholder="选择出生日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </div>
      </div>

      <!-- 个人签名介绍 -->
      <div class="form-item">
        <label class="form-label" for="introduce-input">个人介绍</label>
        <el-input
          id="introduce-input"
          v-model="form.introduce"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="向现场的乐迷介绍一下你自己..."
        />
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="closeDialog">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          class="save-btn"
          @click="handleSubmit"
        >
          {{ submitting ? '保存中...' : '保存修改' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.edit-profile-dialog :deep(.el-dialog__header) {
  padding: var(--space-5) var(--space-6) var(--space-3);
  margin-right: 0;
  border-bottom: 1px solid var(--color-border);
}

.edit-profile-dialog :deep(.el-dialog__body) {
  padding: var(--space-5) var(--space-6);
}

.edit-profile-dialog :deep(.el-dialog__footer) {
  padding: var(--space-3) var(--space-6) var(--space-5);
  border-top: 1px solid var(--color-border);
}

.dialog-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.dialog-header__title {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.header-main-title {
  font-size: var(--text-h2);
  font-weight: 700;
  color: var(--color-ink);
  letter-spacing: -0.01em;
}

.header-sub-title {
  font-size: var(--text-caption);
  color: var(--color-ink-muted);
}

.dialog-close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--color-ink-muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
}

.dialog-close-btn:hover {
  color: var(--color-ink);
  background-color: var(--color-surface-hover);
  border-color: var(--color-border);
}

.edit-profile-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.form-alert {
  margin-bottom: var(--space-2);
}

/* 头像上传卡片 */
.avatar-upload-card {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  padding: var(--space-4) var(--space-5);
  background-color: var(--color-surface-muted);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
}

.avatar-touch-target {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
}

.avatar-img {
  width: 100%;
  height: 100%;
  transition: transform 0.2s ease;
}

.avatar-hover-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background-color: rgba(0, 0, 0, 0.45);
  color: #ffffff;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.avatar-touch-target:hover .avatar-hover-overlay,
.avatar-hover-overlay--loading {
  opacity: 1;
}

.avatar-touch-target:hover .avatar-img {
  transform: scale(1.05);
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.avatar-action-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.select-img-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  border-radius: var(--radius-sm);
}

.avatar-tip {
  font-size: var(--text-caption);
  color: var(--color-ink-muted);
  margin: 0;
}

/* 表单布局 */
.form-row {
  display: grid;
  gap: var(--space-4);
}

.form-row--2col {
  grid-template-columns: 1fr 1fr;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.form-label {
  font-size: var(--text-caption);
  font-weight: 600;
  color: var(--color-ink);
}

.required-star {
  color: var(--color-danger);
}

.input-icon {
  color: var(--color-ink-faint);
}

/* 性别胶囊分段 */
.gender-segmented-group {
  display: flex;
  height: 32px;
  padding: 2px;
  background-color: var(--color-surface-muted);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.gender-pill {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-caption);
  font-weight: 500;
  color: var(--color-ink-muted);
  background: transparent;
  border: none;
  border-radius: calc(var(--radius-sm) - 2px);
  cursor: pointer;
  transition: all 0.15s ease;
}

.gender-pill:hover {
  color: var(--color-ink);
}

.gender-pill--active {
  color: var(--color-ink);
  font-weight: 600;
  background-color: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
}

.save-btn {
  min-width: 90px;
}
</style>
