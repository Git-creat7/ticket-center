import { ref, shallowRef, watch } from 'vue'

export function usePolling<T>(
  source: () => string | null,
  request: (signal: AbortSignal) => Promise<T>,
  shouldContinue: (value: T) => boolean,
) {
  const data = shallowRef<T | null>(null)
  const error = shallowRef<unknown>(null)
  const loading = ref(false)
  const refreshing = ref(false)
  const revision = ref(0)

  watch([source, revision], ([key], [previousKey], onCleanup) => {
    if (key !== previousKey) data.value = null
    error.value = null
    loading.value = key !== null && data.value === null
    refreshing.value = false
    if (key === null) return

    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    onCleanup(() => {
      controller.abort()
      clearTimeout(timer)
    })

    async function poll() {
      if (document.hidden) {
        timer = setTimeout(poll, 3000)
        return
      }
      refreshing.value = true
      try {
        const result = await request(controller.signal)
        if (controller.signal.aborted) return
        data.value = result
        error.value = null
        if (shouldContinue(result)) timer = setTimeout(poll, 3000)
      } catch (cause) {
        if (!controller.signal.aborted) error.value = cause
      } finally {
        if (!controller.signal.aborted) {
          loading.value = false
          refreshing.value = false
        }
      }
    }

    void poll()
  }, { immediate: true })

  function refresh() {
    revision.value += 1
  }

  return { data, error, loading, refreshing, refresh }
}
