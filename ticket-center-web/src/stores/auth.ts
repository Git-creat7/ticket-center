import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { authApi, userApi } from '../services/api'
import {
  setTokenProvider,
  setUnauthorizedHandler,
  TOKEN_STORAGE_KEY,
} from '../services/http'
import type { LoginRequest, User } from '../types/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_STORAGE_KEY))
  const user = ref<User | null>(null)
  const isAuthenticated = computed(() => token.value !== null)

  function saveToken(value: string | null): void {
    token.value = value
    if (value) localStorage.setItem(TOKEN_STORAGE_KEY, value)
    else localStorage.removeItem(TOKEN_STORAGE_KEY)
  }

  function clearSession(): void {
    saveToken(null)
    user.value = null
  }

  async function fetchCurrentUser(): Promise<User | null> {
    if (!token.value) return null
    user.value = await userApi.me()
    return user.value
  }

  /**
   * 取回用户信息，失败则清掉刚存下的 token。
   *
   * 否则会卡在中间态：token 有了（isAuthenticated 为 true）但 user 是空的，
   * 界面既不是登录态也不是登出态，登录页还同时弹着"登录失败"。
   */
  async function loadUserAfterLogin(): Promise<User> {
    try {
      const currentUser = await fetchCurrentUser()
      if (!currentUser) throw new Error('登录成功但未能获取用户信息')
      return currentUser
    } catch (error) {
      clearSession()
      throw error
    }
  }

  async function login(payload: LoginRequest): Promise<User> {
    saveToken(await authApi.login(payload))
    return loadUserAfterLogin()
  }

  async function loginByPassword(payload: { phone: string; password: string }): Promise<User> {
    saveToken(await authApi.loginByPassword(payload))
    return loadUserAfterLogin()
  }

  async function logout(): Promise<void> {
    try {
      if (token.value) await authApi.logout()
    } finally {
      clearSession()
    }
  }

  // HTTP does not import this store, which avoids a circular dependency.
  setTokenProvider(() => token.value)
  setUnauthorizedHandler(clearSession)

  return {
    token,
    user,
    isAuthenticated,
    login,
    loginByPassword,
    logout,
    clearSession,
    fetchCurrentUser,
  }
})
