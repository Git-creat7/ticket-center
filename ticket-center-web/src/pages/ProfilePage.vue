<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CalendarCheck, Check, ChevronRight, Coins, LogOut, MapPin, SquarePen, UserRound } from 'lucide-vue-next'
import { useRouter } from 'vue-router'

import { userApi } from '../services/api'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import type { SignStatus, UserInfo } from '../types/api'
import { getErrorMessage } from '../utils/errors'
import { avatarOrFallback } from '../utils/images'
import CreditsDrawer from '../components/profile/CreditsDrawer.vue'
import EditProfileDialog from '../components/profile/EditProfileDialog.vue'
import FollowListDrawer from '../components/profile/FollowListDrawer.vue'
import type { FollowListTab } from '../types/api'

const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

const info = ref<UserInfo | null>(null)
const signStatus = ref<SignStatus | null>(null)
const creditsDrawerVisible = ref(false)
const followDrawerVisible = ref(false)
const followDrawerTab = ref<FollowListTab>('followees')
const editProfileVisible = ref(false)
const loading = ref(true)
const loadError = ref('')
const actionError = ref('')
const signing = ref(false)
const loggingOut = ref(false)

const genderLabel = computed(() => {
  if (info.value?.gender == null) return ''
  return info.value.gender ? '男' : '女'
})

function openFollowList(tab: FollowListTab) {
  followDrawerTab.value = tab
  followDrawerVisible.value = true
}

async function loadProfile(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const user = await auth.fetchCurrentUser()
    if (!user) throw new Error('未获取到当前用户信息')
    const [profileInfo, currentSignStatus] = await Promise.all([
      userApi.getInfo(user.id),
      userApi.signStatus().catch(() => null),
    ])
    info.value = profileInfo || null
    signStatus.value = currentSignStatus
  } catch (error) {
    loadError.value = getErrorMessage(error, '个人资料加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function signIn(): Promise<void> {
  if (signing.value || signStatus.value?.isTodaySigned) return
  signing.value = true
  actionError.value = ''
  try {
    await userApi.sign()
    const updatedStatus = await userApi.signStatus()
    signStatus.value = updatedStatus
    notifications.notify(
      `签到成功，已连续签到 ${updatedStatus.continuousDays} 天`,
      'success'
    )
    // 刷新用户信息以更新积分
    if (auth.user) {
      info.value = await userApi.getInfo(auth.user.id)
    }
  } catch (error) {
    actionError.value = getErrorMessage(error, '签到失败，请稍后重试')
  } finally {
    signing.value = false
  }
}

async function logout(): Promise<void> {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await auth.logout()
  } catch {
    // The auth store clears the local session even when the server request fails.
  } finally {
    notifications.notify('已退出登录', 'success')
    await router.replace('/discover')
    loggingOut.value = false
  }
}

onMounted(loadProfile)
</script>

<template>
  <div class="page-container page-stack profile-page">
    <header class="page-header">
      <div>
        <h1 class="page-heading">个人中心</h1>
        <p class="page-lead">查看账号资料与签到记录。</p>
      </div>
    </header>

    <el-card v-if="loading" class="profile-card" shadow="never" aria-busy="true">
      <el-skeleton animated :rows="3" />
    </el-card>

    <div v-else-if="loadError" class="profile-error">
      <el-alert :title="loadError" type="error" show-icon :closable="false" />
      <el-button type="primary" plain @click="loadProfile">重新加载</el-button>
    </div>

    <template v-else-if="auth.user">
      <el-card class="profile-card" shadow="never">
        <section class="profile-identity" aria-labelledby="profile-name">
          <div class="profile-identity__main">
            <el-avatar :size="88" :src="avatarOrFallback(auth.user.icon, auth.user.id)">
              <UserRound :size="34" aria-hidden="true" />
            </el-avatar>

            <div class="profile-identity__copy">
              <h2 id="profile-name" class="profile-name">{{ auth.user.nickName }}</h2>
              <div v-if="info" class="profile-meta">
                <span v-if="info.city"><MapPin :size="15" aria-hidden="true" />{{ info.city }}</span>
                <span v-if="genderLabel">{{ genderLabel }}</span>
                <span v-if="info.birthday">生日 {{ info.birthday }}</span>
              </div>
              <p class="profile-bio">{{ info?.introduce || '还没有填写个人介绍' }}</p>
            </div>
          </div>

          <div class="profile-identity__actions">
            <el-button
              type="default"
              plain
              @click="router.push(`/people/${auth.user.id}`)"
            >
              <UserRound :size="15" aria-hidden="true" />
              <span>我的主页</span>
            </el-button>
            <el-button
              type="default"
              plain
              class="edit-profile-btn"
              @click="editProfileVisible = true"
            >
              <SquarePen :size="15" aria-hidden="true" />
              <span>编辑资料</span>
            </el-button>
          </div>
        </section>
      </el-card>

      <!-- 编辑资料弹窗 -->
      <EditProfileDialog
        v-model:visible="editProfileVisible"
        :user="auth.user"
        :info="info"
        @saved="loadProfile"
      />

      <el-card v-if="info" class="profile-card profile-stats" shadow="never" aria-label="账号数据">
        <button
          type="button"
          class="profile-stat-button"
          aria-label="查看关注列表"
          @click="openFollowList('followees')"
        >
          <el-statistic title="关注" :value="info.followee ?? 0" />
        </button>
        <button
          type="button"
          class="profile-stat-button"
          aria-label="查看粉丝列表"
          @click="openFollowList('fans')"
        >
          <el-statistic title="粉丝" :value="info.fans ?? 0" />
        </button>
        <button
          type="button"
          class="credits-stat-box"
          aria-label="查看积分明细"
          @click="creditsDrawerVisible = true"
        >
          <el-statistic title="积分 (明细)" :value="info.credits ?? 0" />
        </button>
      </el-card>

      <FollowListDrawer
        v-model:visible="followDrawerVisible"
        v-model:tab="followDrawerTab"
        :user-id="auth.user.id"
      />

      <!-- 积分明细抽屉 -->
      <CreditsDrawer
        v-model:visible="creditsDrawerVisible"
        :total-credits="info?.credits ?? 0"
      />

      <!-- 每日签到与打卡日历区域 (Linear / Apple 现代极简卡片) -->
      <el-card class="profile-card sign-card" shadow="never">
        <section class="sign-section" aria-labelledby="sign-title">
          <!-- 头部：打卡状态与操作按钮 -->
          <div class="sign-header">
            <div class="sign-header__info">
              <div class="sign-title-row">
                <div class="sign-badge-icon" aria-hidden="true">
                  <CalendarCheck :size="18" />
                </div>
                <h2 id="sign-title" class="sign-title">每日打卡</h2>
                <span
                  v-if="signStatus && signStatus.continuousDays > 0"
                  class="streak-pill"
                >
                  已连签 {{ signStatus.continuousDays }} 天
                </span>
              </div>
              <p class="sign-desc">
                <template v-if="signStatus?.isTodaySigned">
                  今日已完成打卡，本月累计打卡 <strong>{{ signStatus?.monthlyTotalDays ?? 0 }}</strong> 天
                </template>
                <template v-else>
                  今日尚未打卡，打卡可领取 <strong>10</strong> 积分
                </template>
              </p>
            </div>

            <el-button
              :type="signStatus?.isTodaySigned ? 'info' : 'primary'"
              :plain="signStatus?.isTodaySigned"
              :disabled="signStatus?.isTodaySigned"
              :loading="signing"
              class="sign-action-btn"
              :class="{ 'sign-action-btn--signed': signStatus?.isTodaySigned }"
              @click="signIn"
            >
              <Check v-if="signStatus?.isTodaySigned" :size="15" aria-hidden="true" />
              <span>{{ signStatus?.isTodaySigned ? '今日已打卡' : (signing ? '打卡中...' : '立即打卡 +10') }}</span>
            </el-button>
          </div>

          <!-- 7 日连签轨道胶囊 -->
          <div v-if="signStatus?.weekDays?.length" class="capsule-track" aria-label="本周打卡记录">
            <div
              v-for="day in signStatus.weekDays"
              :key="day.date"
              class="capsule-item"
              :class="{
                'capsule-item--signed': day.isSigned,
                'capsule-item--today': day.isToday,
                'capsule-item--future': day.isFuture,
              }"
            >
              <span v-if="day.isToday" class="today-marker">今日</span>
              <div class="capsule-header">
                <span class="capsule-day-name">{{ day.dayName }}</span>
                <span class="capsule-date">{{ day.dayOfMonth }}日</span>
              </div>

              <div class="capsule-node">
                <Check v-if="day.isSigned" :size="13" class="node-icon-check" />
                <span v-else-if="day.isToday" class="node-dot-today" />
                <span v-else class="node-dot-empty" />
              </div>

              <div class="capsule-reward">
                <span class="reward-text" :class="{ 'reward-text--earned': day.isSigned }">
                  +10
                </span>
              </div>
            </div>
          </div>

          <!-- 底部权益与账单入口 -->
          <div class="sign-footer-banner">
            <div class="banner-left">
              <Coins :size="15" class="banner-coin-icon" aria-hidden="true" />
              <span>100 积分可抵扣 1 元现金，下单结算时可直接立减</span>
            </div>
            <button
              type="button"
              class="banner-link-btn"
              @click="creditsDrawerVisible = true"
            >
              <span>查看积分明细</span>
              <ChevronRight :size="14" aria-hidden="true" />
            </button>
          </div>
        </section>
      </el-card>

      <el-alert
        v-if="actionError"
        :title="actionError"
        type="error"
        show-icon
        :closable="false"
      />

      <el-card class="profile-card" shadow="never">
        <section class="account-section" aria-labelledby="account-title">
          <div>
            <h2 id="account-title" class="section-heading">账号管理</h2>
            <p class="section-copy">退出后，仍可浏览公开活动。</p>
          </div>
          <el-button type="danger" plain :loading="loggingOut" @click="logout">
            <LogOut v-if="!loggingOut" :size="18" aria-hidden="true" />
            {{ loggingOut ? '正在退出' : '退出登录' }}
          </el-button>
        </section>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.profile-page {
  max-width: 52rem;
  gap: var(--space-5);
}

.profile-card {
  border-radius: var(--radius-sm);
}

.profile-card :deep(.el-card__body) {
  padding: var(--space-6);
}

.profile-error {
  display: grid;
  justify-items: start;
  gap: var(--space-4);
}

.profile-identity {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-6);
  flex-wrap: wrap;
}

.profile-identity__main {
  display: flex;
  align-items: center;
  gap: var(--space-6);
  min-width: 0;
  flex: 1;
}

.profile-identity__actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.edit-profile-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  border-radius: var(--radius-sm);
  font-weight: 500;
  transition: all 0.15s ease;
}

.edit-profile-btn:hover {
  color: var(--color-brand);
  border-color: var(--color-brand);
  background-color: var(--color-surface-hover);
}

.profile-identity__copy {
  min-width: 0;
}

.profile-name {
  overflow-wrap: anywhere;
  font-size: var(--text-page);
  font-weight: 700;
  letter-spacing: 0;
}

.profile-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  margin-top: var(--space-2);
  color: var(--color-ink-soft);
  font-size: var(--text-secondary);
}

.profile-meta span {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.profile-bio {
  max-width: 52ch;
  margin-top: var(--space-3);
  color: var(--color-ink-soft);
}

.profile-stats :deep(.el-card__body) {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  padding-block: var(--space-4);
}

.profile-stats :deep(.el-statistic) {
  min-width: 0;
  text-align: center;
}

.profile-stat-button,
.credits-stat-box {
  min-width: 0;
  padding: var(--space-2);
  border: 0;
  background: transparent;
  cursor: pointer;
  transition: background-color 120ms ease;
}

.profile-stat-button:hover,
.credits-stat-box:hover {
  background: var(--color-surface-hover);
}

.profile-stats :deep(.el-card__body) > * + * {
  border-left: 1px solid var(--color-border);
}

.profile-stats :deep(.el-statistic__head) {
  margin-bottom: var(--space-1);
  color: var(--color-ink-muted);
}

.profile-stats :deep(.el-statistic__number) {
  color: var(--color-ink);
  font-size: var(--text-section);
  font-weight: 700;
}

.sign-section {
  display: grid;
  gap: var(--space-5);
}

.sign-section__header,
.account-section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-6);
}

.sign-section__copy {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.sign-subtitle {
  margin-top: var(--space-1);
  color: var(--color-ink-soft);
  font-size: var(--text-secondary);
}

/* ================= 现代极简签到卡片 (Linear / Apple 质感) ================= */
.sign-card {
  overflow: hidden;
}

.sign-section {
  display: grid;
  gap: var(--space-4);
}

.sign-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-4);
}

.sign-header__info {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.sign-title-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.sign-badge-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-soft, rgba(37, 99, 235, 0.08));
  color: var(--color-primary);
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.sign-title {
  font-size: var(--text-heading);
  font-weight: 700;
  color: var(--color-ink);
  margin: 0;
}

.streak-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--color-primary-soft, rgba(37, 99, 235, 0.1));
  color: var(--color-primary);
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1.4;
}

.sign-desc {
  font-size: 0.8125rem;
  color: var(--color-ink-soft);
  margin: 0;
}

.sign-desc strong {
  color: var(--color-ink);
  font-weight: 600;
}

.sign-action-btn {
  min-width: 124px;
  height: 36px;
  font-size: 0.875rem;
  font-weight: 600;
  border-radius: var(--radius-sm);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.sign-action-btn--signed {
  background: var(--color-surface-muted) !important;
  border-color: var(--color-border) !important;
  color: var(--color-ink-soft) !important;
}

/* 7 日胶囊连签轨道 */
.capsule-track {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 8px;
}

.capsule-item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  padding: 12px 6px 10px;
  background: var(--color-surface-muted, #f8fafc);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  transition: all 180ms ease;
  min-height: 96px;
}

.capsule-item:hover {
  border-color: var(--color-border-hover, #cbd5e1);
  transform: translateY(-1px);
}

.today-marker {
  position: absolute;
  top: -8px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--color-primary);
  color: #fff;
  font-size: 0.625rem;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 999px;
  line-height: 1.2;
  white-space: nowrap;
  letter-spacing: 0.5px;
}

.capsule-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.capsule-day-name {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--color-ink-muted);
}

.capsule-date {
  font-size: 0.6875rem;
  color: var(--color-ink-soft);
  font-variant-numeric: tabular-nums;
}

.capsule-node {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  display: grid;
  place-items: center;
  margin-block: 4px;
}

.node-dot-today {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-primary);
}

.node-dot-empty {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-border);
}

.node-icon-check {
  color: var(--color-success);
}

.capsule-reward {
  display: flex;
  align-items: center;
  justify-content: center;
}

.reward-text {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--color-ink-muted);
  font-variant-numeric: tabular-nums;
}

.reward-text--earned {
  color: var(--color-success);
}

/* 已打卡状态胶囊 */
.capsule-item--signed {
  background: rgba(16, 185, 129, 0.04);
  border-color: rgba(16, 185, 129, 0.28);
}

.capsule-item--signed .capsule-day-name {
  color: var(--color-ink);
  font-weight: 600;
}

.capsule-item--signed .capsule-node {
  background: rgba(16, 185, 129, 0.12);
  border-color: rgba(16, 185, 129, 0.35);
}

/* 今日状态胶囊 */
.capsule-item--today {
  border-color: var(--color-primary);
  background: var(--color-surface);
  box-shadow: 0 0 0 1px var(--color-primary);
}

.capsule-item--today .capsule-day-name {
  color: var(--color-primary);
  font-weight: 700;
}

/* 未来状态胶囊 */
.capsule-item--future {
  opacity: 0.55;
  background: transparent;
}

/* 底部权益与账单横幅 */
.sign-footer-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--color-bg);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  font-size: 0.8125rem;
}

.banner-left {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-ink-soft);
}

.banner-coin-icon {
  color: var(--color-primary);
  flex-shrink: 0;
}

.banner-link-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: none;
  border: none;
  color: var(--color-primary);
  font-size: 0.8125rem;
  font-weight: 600;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 4px;
  transition: all 120ms ease;
  white-space: nowrap;
}

.banner-link-btn:hover {
  background: var(--color-primary-soft, rgba(37, 99, 235, 0.08));
}

@media (max-width: 36rem) {
  .profile-card :deep(.el-card__body) {
    padding: var(--space-5);
  }

  .profile-identity {
    align-items: flex-start;
    gap: var(--space-4);
  }

  .profile-identity :deep(.el-avatar) {
    width: 72px !important;
    height: 72px !important;
  }

  .sign-header,
  .account-section {
    align-items: stretch;
    flex-direction: column;
  }

  .sign-action-btn,
  .account-section > .el-button {
    width: 100%;
  }

  .capsule-track {
    gap: 4px;
  }

  .capsule-item {
    padding: 8px 2px 6px;
    min-height: 80px;
  }

  .capsule-node {
    width: 22px;
    height: 22px;
  }

  .sign-footer-banner {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }
}
</style>
