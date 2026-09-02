<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ArrowRight, Lock, MessageSquareCode, Phone, Ticket } from 'lucide-vue-next'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { authApi } from '../services/api'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import { getErrorMessage } from '../utils/errors'

// 路由与认证状态
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

// 登录模式：'code' 为短信验证码登录，'password' 为密码登录
const loginMode = ref<'code' | 'password'>('code')
const loginModeOptions = [
  { label: '验证码登录', value: 'code' },
  { label: '密码登录', value: 'password' },
]

// 表单数据
const phone = ref('')
const code = ref('')
const password = ref('')

// 校验与加载状态
const phoneError = ref('')
const codeError = ref('')
const passwordError = ref('')
const formError = ref('')
const sendingCode = ref(false)
const loggingIn = ref(false)
const countdown = ref(0)
let countdownTimer: ReturnType<typeof setInterval> | undefined

// 发送短信验证码按钮文本
const sendCodeLabel = computed(() => {
  if (sendingCode.value) return '发送中'
  if (countdown.value > 0) return `${countdown.value} 秒后重发`
  return '获取验证码'
})

// 清理表单错误提示
function clearErrors(): void {
  phoneError.value = ''
  codeError.value = ''
  passwordError.value = ''
  formError.value = ''
}

// 手机号格式校验
function validatePhone(): boolean {
  const value = phone.value.trim()
  if (!/^1\d{10}$/.test(value)) {
    phoneError.value = '请输入 11 位有效手机号码'
    return false
  }
  return true
}

// 验证码格式校验
function validateCode(): boolean {
  const value = code.value.trim()
  if (!/^[a-zA-Z0-9]{6}$/.test(value)) {
    codeError.value = '请输入 6 位短信验证码'
    return false
  }
  return true
}

// 密码格式校验
function validatePassword(): boolean {
  if (password.value.length < 8) {
    passwordError.value = '密码长度不能少于 8 位'
    return false
  }
  return true
}

// 启动 60 秒倒计时
function startCountdown(): void {
  countdown.value = 60
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && countdownTimer) {
      clearInterval(countdownTimer)
      countdownTimer = undefined
    }
  }, 1000)
}

// 发送短信验证码
async function sendCode(): Promise<void> {
  clearErrors()
  if (!validatePhone()) return

  sendingCode.value = true
  try {
    await authApi.sendCode(phone.value.trim())
    startCountdown()
    notifications.notify('验证码发送成功，请注意查收短信', 'success')
  } catch (error) {
    formError.value = getErrorMessage(error, '验证码发送失败，请稍后重试')
  } finally {
    sendingCode.value = false
  }
}

// 执行登录逻辑
async function handleSubmit(): Promise<void> {
  clearErrors()
  const phoneValid = validatePhone()
  if (!phoneValid) return

  loggingIn.value = true
  try {
    if (loginMode.value === 'code') {
      if (!validateCode()) {
        loggingIn.value = false
        return
      }
      await auth.login({
        phone: phone.value.trim(),
        code: code.value.trim(),
      })
    } else {
      if (!validatePassword()) {
        loggingIn.value = false
        return
      }
      await auth.loginByPassword({
        phone: phone.value.trim(),
        password: password.value,
      })
    }

    notifications.notify('登录成功，欢迎开启精彩演出！', 'success')
    const requestedRedirect = route.query.redirect
    const redirect =
      typeof requestedRedirect === 'string' &&
      requestedRedirect.startsWith('/') &&
      !requestedRedirect.startsWith('//')
        ? requestedRedirect
        : '/discover'
    await router.replace(redirect)
  } catch (error) {
    formError.value = getErrorMessage(error, '登录失败，请检查输入后重试')
  } finally {
    loggingIn.value = false
  }
}

onBeforeUnmount(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})
</script>

<template>
  <div class="login-page">
    <main class="login-panel">
      <RouterLink class="login-brand" to="/discover" aria-label="Ticket Center 首页">
        <span class="login-brand__icon" aria-hidden="true">
          <Ticket :size="22" />
        </span>
        <span>Ticket Center</span>
      </RouterLink>

      <el-card class="login-card" shadow="never">
        <header class="login-card__header">
          <h1>登录 Ticket Center</h1>
          <p>登录后查看电子票、关注动态与现场评价。</p>
        </header>

        <el-segmented
          v-model="loginMode"
          class="login-tabs"
          :options="loginModeOptions"
          aria-label="登录方式"
          @change="clearErrors"
        />

        <el-form class="login-form" label-position="top" @submit.prevent="handleSubmit">
          <el-form-item label="手机号码" :error="phoneError">
            <el-input
              id="login-phone"
              v-model="phone"
              size="large"
              type="tel"
              autocomplete="tel"
              maxlength="11"
              placeholder="请输入 11 位手机号码"
              clearable
              @input="clearErrors"
            >
              <template #prefix>
                <Phone :size="17" aria-hidden="true" />
              </template>
            </el-input>
          </el-form-item>

          <el-form-item v-if="loginMode === 'code'" label="短信验证码" :error="codeError">
            <el-input
              id="login-code"
              v-model="code"
              size="large"
              maxlength="6"
              placeholder="请输入 6 位验证码"
              @input="clearErrors"
            >
              <template #prefix>
                <MessageSquareCode :size="17" aria-hidden="true" />
              </template>
              <template #append>
                <el-button
                  :loading="sendingCode"
                  :disabled="sendingCode || countdown > 0"
                  @click="sendCode"
                >
                  {{ sendCodeLabel }}
                </el-button>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item v-else label="登录密码" :error="passwordError">
            <el-input
              id="login-password"
              v-model="password"
              size="large"
              type="password"
              autocomplete="current-password"
              placeholder="请输入至少 8 位登录密码"
              show-password
              @input="clearErrors"
            >
              <template #prefix>
                <Lock :size="17" aria-hidden="true" />
              </template>
            </el-input>
          </el-form-item>

          <el-alert
            v-if="formError"
            class="login-alert"
            :title="formError"
            type="error"
            show-icon
            :closable="false"
          />

          <el-button
            class="login-submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="loggingIn"
          >
            {{ loggingIn ? '正在登录' : '立即登录' }}
          </el-button>
        </el-form>

        <el-divider>或</el-divider>

        <RouterLink v-slot="{ navigate }" custom to="/discover">
          <el-button class="guest-button" size="large" @click="navigate">
            <Ticket :size="18" aria-hidden="true" />
            以游客身份浏览活动
            <ArrowRight :size="16" aria-hidden="true" />
          </el-button>
        </RouterLink>
      </el-card>

      <p class="login-footnote">首次使用无需注册，验证码登录后将自动创建账号。</p>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: var(--space-6) var(--space-4);
  background: var(--color-canvas);
}

.login-panel {
  width: min(100%, 28rem);
}

.login-brand {
  width: fit-content;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin: 0 auto var(--space-5);
  color: var(--color-ink);
  font-size: var(--text-subheading);
  font-weight: 700;
}

.login-brand__icon {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  color: white;
  background: var(--color-primary);
}

.login-card {
  border-radius: var(--radius-sm);
}

.login-card :deep(.el-card__body) {
  padding: var(--space-6);
}

.login-card__header {
  margin-bottom: var(--space-4);
}

.login-card__header h1 {
  font-size: var(--text-title);
  font-weight: 700;
  letter-spacing: 0;
}

.login-card__header p {
  margin-top: var(--space-2);
  color: var(--color-ink-soft);
  font-size: var(--text-secondary);
}

.login-tabs {
  width: 100%;
  margin-bottom: var(--space-4);
}

.login-tabs :deep(.el-segmented__group) {
  width: 100%;
}

.login-tabs :deep(.el-segmented__item) {
  flex: 1;
}

.login-form :deep(.el-form-item__label) {
  padding-bottom: var(--space-2);
  color: var(--color-ink-soft);
  font-weight: 600;
  line-height: 1.2;
}

.login-form :deep(.el-input-group__append) {
  padding: 0 var(--space-2);
}

.login-alert {
  margin-bottom: var(--space-4);
}

.login-submit,
.guest-button {
  width: 100%;
}

.login-footnote {
  margin-top: var(--space-4);
  color: var(--color-ink-muted);
  font-size: var(--text-caption);
  text-align: center;
}

@media (max-width: 30rem) {
  .login-page {
    align-items: start;
    padding-top: var(--space-8);
  }

  .login-card :deep(.el-card__body) {
    padding: var(--space-5);
  }
}
</style>
