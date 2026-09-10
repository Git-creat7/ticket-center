import assert from 'node:assert/strict'
import { beforeEach, test } from 'node:test'
import {
  clearReservationRequest,
  readReservationRequest,
  reservationStatus,
  saveReservationRequest,
} from '../src/utils/reservations.ts'

beforeEach(() => {
  const storage = new Map()
  globalThis.sessionStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key),
  }
})

test('retry keeps the request id and credits option', () => {
  const request = { requestId: 'same-request', useCredits: true }
  saveReservationRequest('100', '200', request)
  assert.deepEqual(readReservationRequest('100', '200'), request)
  assert.deepEqual(readReservationRequest(100, 200), request)
})

test('requests are isolated by user and ticket', () => {
  saveReservationRequest(100, 200, { requestId: 'first', useCredits: false })
  assert.equal(readReservationRequest(101, 200), null)
  assert.equal(readReservationRequest(100, 201), null)
})

test('waitlist retries keep their operation type across refreshes', () => {
  const request = { requestId: 'waitlist-request', useCredits: true, kind: 'waitlist' }
  saveReservationRequest(100, 200, request)
  assert.deepEqual(readReservationRequest(100, 200), request)
  sessionStorage.setItem('ticket-center-reservation:100:200', JSON.stringify({ ...request, kind: 'unknown' }))
  assert.equal(readReservationRequest(100, 200), null)
})

test('accepted requests are removed without removing other requests', () => {
  saveReservationRequest(100, 200, { requestId: 'first', useCredits: false })
  saveReservationRequest(100, 201, { requestId: 'second', useCredits: true })
  clearReservationRequest(100, 200)
  assert.equal(readReservationRequest(100, 200), null)
  assert.equal(readReservationRequest(100, 201).requestId, 'second')
})

test('damaged local data does not prevent a new request', () => {
  sessionStorage.setItem('ticket-center-reservation:100:200', '{')
  assert.equal(readReservationRequest(100, 200), null)
  sessionStorage.setItem('ticket-center-reservation:100:200', '{"requestId":123}')
  assert.equal(readReservationRequest(100, 200), null)
})

test('reservation and order statuses have distinct labels', () => {
  assert.equal(reservationStatus({ status: 0 }).label, '处理中')
  assert.equal(reservationStatus({ status: 1, orderStatus: 0 }).label, '待支付')
  assert.equal(reservationStatus({ status: 1, orderStatus: 0, paymentDeadline: '2000-01-01 00:00:00' }).label, '已超时')
  assert.equal(reservationStatus({ status: 1, orderStatus: 1 }).label, '已出票')
  assert.equal(reservationStatus({ status: 2 }).label, '预约失败')
  assert.equal(reservationStatus({ status: 3, orderStatus: 2 }).label, '已取消')
})
