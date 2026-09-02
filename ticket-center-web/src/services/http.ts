import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import JSONbig from 'json-bigint'

import type { ApiEnvelope } from '../types/api'

export const TOKEN_STORAGE_KEY = 'ticket-center-token'

type TokenProvider = () => string | null
type UnauthorizedHandler = () => void

let tokenProvider: TokenProvider = () => localStorage.getItem(TOKEN_STORAGE_KEY)
let unauthorizedHandler: UnauthorizedHandler | undefined

const json = JSONbig({ storeAsString: true })

/** A backend business error. HTTP 200 responses can still contain one. */
export class ApiError extends Error {
  readonly code: number
  readonly status?: number

  constructor(message: string, code: number, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
  transformResponse: [(value: unknown) => {
    if (typeof value !== 'string' || value.length === 0) return value

    try {
      return json.parse(value)
    } catch {
      return value
    }
  }],
})

/** Keeps this module independent from Pinia and makes auth easy to replace. */
export function setTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider
}

export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

http.interceptors.request.use((config) => {
  const token = tokenProvider()
  if (token) config.headers.set('authorization', token)
  return config
})

http.interceptors.response.use(
  (response) => {
    const envelope = response.data as Partial<ApiEnvelope<unknown>>

    // 后端所有接口都返回 { code, msg, data }。拿不到 code 说明这个 200
    // 压根不是后端给的——代理配错、网关错误页、dev server 的 SPA fallback
    // 都会回 200 + HTML。放过去的话 apiRequest 取出 undefined 当成功值往上传，
    // 界面显示空白且无任何报错，极难定位。
    if (typeof envelope?.code !== 'number') {
      return Promise.reject(
        new ApiError('响应格式异常，请确认接口地址与代理配置', -1, response.status),
      )
    }

    if (envelope.code !== 200) {
      return Promise.reject(new ApiError(envelope.msg || '请求失败', envelope.code, response.status))
    }
    return response
  },
  (error: AxiosError) => {
    if (error.response?.status === 401) unauthorizedHandler?.()

    // 非 2xx 也可能带 { code, msg } 体：拦截器鉴权失败走的就是这条路
    // （401 未登录、403 需要管理员权限）。直接抛 AxiosError 的话界面上
    // 显示的是 "Request failed with status code 403"，后端写的 msg 就白写了。
    const envelope = error.response?.data as Partial<ApiEnvelope<unknown>> | undefined
    if (typeof envelope?.code === 'number') {
      return Promise.reject(new ApiError(envelope.msg || '请求失败', envelope.code, error.response?.status))
    }
    return Promise.reject(error)
  },
)

/** Unwraps the common `{ code, msg, data }` response after validation. */
export async function apiRequest<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiEnvelope<T>>(config)
  return response.data.data
}
