import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'

export type NotificationType = 'success' | 'error' | 'info' | 'warning'

export const useNotificationStore = defineStore('notifications', () => {
  function notify(message: string, type: NotificationType = 'info'): void {
    ElMessage({
      message,
      type,
      duration: 3000,
      showClose: true,
    })
  }

  return { notify }
})
