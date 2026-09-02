import axios from 'axios'
import { ApiError } from '../services/http'

export function getErrorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  if (error instanceof ApiError) return error.message
  if (axios.isAxiosError(error)) {
    if (error.code === 'ECONNABORTED') return '请求超时，请检查网络后重试'
    if (!error.response) return '无法连接服务，请确认后端已经启动'
    const status = error.response.status
    if (status === 401) return '登录状态已失效，请重新登录'
    if (status === 403) return '没有权限执行该操作'
    if (status === 404) return '请求的内容不存在或已被删除'
    if (status >= 500) return '服务暂时不可用，请稍后重试'
    // 兜底成中文：再往下走会命中 error.message，
    // 那是 axios 的英文文案（"Request failed with status code 400"），不该给用户看
    return fallback
  }
  if (error instanceof Error && error.message) return error.message
  return fallback
}
