import assert from 'node:assert/strict'
import { test } from 'node:test'
import { effectScope, nextTick, ref } from 'vue'
import { usePolling } from '../src/composables/usePolling.ts'

async function flush() {
  await nextTick()
  await Promise.resolve()
}

function setup(context, request, shouldContinue = (value) => value.status === 0) {
  globalThis.document = { hidden: false }
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const scope = effectScope()
  context.after(() => scope.stop())
  const key = ref('first')
  const polling = scope.run(() => usePolling(() => key.value, request, shouldContinue))
  return { ...polling, key, scope }
}

test('processing polls until a terminal result then stops', async (context) => {
  let calls = 0
  const polling = setup(context, async () => ({ status: calls++ === 0 ? 0 : 1 }))
  await flush()
  assert.equal(polling.data.value.status, 0)
  context.mock.timers.tick(3000)
  await flush()
  assert.equal(polling.data.value.status, 1)
  context.mock.timers.tick(30_000)
  await flush()
  assert.equal(calls, 2)
})

test('failed queries preserve the last result and allow manual retry', async (context) => {
  let calls = 0
  const polling = setup(context, async () => {
    calls += 1
    if (calls === 2) throw new Error('offline')
    return { status: calls === 1 ? 0 : 2 }
  })
  await flush()
  context.mock.timers.tick(3000)
  await flush()
  assert.equal(polling.data.value.status, 0)
  assert.equal(polling.error.value.message, 'offline')
  context.mock.timers.tick(30_000)
  assert.equal(calls, 2)
  polling.refresh()
  await flush()
  assert.equal(polling.data.value.status, 2)
  assert.equal(polling.error.value, null)
})

test('source changes abort old queries and ignore late responses', async (context) => {
  const requests = []
  const polling = setup(context, (signal) => new Promise((resolve) => requests.push({ signal, resolve })))
  polling.key.value = 'second'
  await flush()
  assert.equal(requests[0].signal.aborted, true)
  requests[1].resolve({ status: 1, id: 'new' })
  await flush()
  requests[0].resolve({ status: 0, id: 'old' })
  await flush()
  assert.equal(polling.data.value.id, 'new')
})

test('unmount cancels timers and pending requests', async (context) => {
  let calls = 0
  let signal
  const polling = setup(context, async (value) => {
    signal = value
    calls += 1
    return { status: 0 }
  })
  await flush()
  polling.scope.stop()
  assert.equal(signal.aborted, true)
  context.mock.timers.tick(30_000)
  assert.equal(calls, 1)
})

test('hidden pages pause network requests and resume when visible', async (context) => {
  let calls = 0
  setup(context, async () => { calls += 1; return { status: 0 } })
  await flush()
  document.hidden = true
  context.mock.timers.tick(3000)
  await flush()
  assert.equal(calls, 1)
  document.hidden = false
  context.mock.timers.tick(3000)
  await flush()
  assert.equal(calls, 2)
})

test('logout clears private data and stops polling', async (context) => {
  let calls = 0
  const polling = setup(context, async () => { calls += 1; return { status: 0 } })
  await flush()
  polling.key.value = null
  await flush()
  assert.equal(polling.data.value, null)
  assert.equal(polling.loading.value, false)
  context.mock.timers.tick(30_000)
  assert.equal(calls, 1)
})

test('slow queries never overlap', async (context) => {
  let calls = 0
  const polling = setup(context, () => { calls += 1; return new Promise(() => {}) })
  await flush()
  context.mock.timers.tick(30_000)
  await flush()
  assert.equal(calls, 1)
  assert.equal(polling.refreshing.value, true)
})
