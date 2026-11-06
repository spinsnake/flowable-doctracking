import test from 'node:test'
import assert from 'node:assert/strict'
import { commandKey, errorMessage } from '../app/utils/commands.ts'
test('uncertain retry keeps a key, changed actor/payload creates a distinct command', () => {
  const memory = new Map<string, string>(); let count = 0; const uuid = () => `key-${++count}`
  const first = commandKey('alice:reserve:doc', { expected_version: 2 }, memory, uuid)
  assert.equal(commandKey('alice:reserve:doc', { expected_version: 2 }, memory, uuid).key, first.key)
  assert.notEqual(commandKey('bob:reserve:doc', { expected_version: 2 }, memory, uuid).key, first.key)
  assert.notEqual(commandKey('alice:reserve:doc', { expected_version: 3 }, memory, uuid).key, first.key)
  memory.delete(first.fingerprint)
  assert.notEqual(commandKey('alice:reserve:doc', { expected_version: 2 }, memory, uuid).key, first.key)
})
test('session and conflict failures have actionable Thai recovery instructions', () => { assert.match(errorMessage(401), /เข้าสู่ระบบ/); assert.match(errorMessage(409), /โหลดข้อมูลล่าสุด/); assert.match(errorMessage(0), /รหัสคำสั่งเดิม/) })
