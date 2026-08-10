import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { getCurrentMenu } from '@/api/systemAdmin'
import { useMenuStore } from './menu'

vi.mock('@/api/systemAdmin', () => ({ getCurrentMenu: vi.fn() }))

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const leaf = (id: number, path: string) => ({
  id,
  parentId: 0,
  menuName: path,
  menuType: 'C' as const,
  path,
  component: null,
  perms: null,
  icon: null,
  visible: 1 as const,
  status: 1 as const,
  sortOrder: id,
  children: [],
})

describe('current menu store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('shares one pending ensure request', async () => {
    const pending = deferred<ReturnType<typeof leaf>[]>()
    vi.mocked(getCurrentMenu).mockReturnValue(pending.promise)
    const store = useMenuStore()

    const first = store.ensureLoaded()
    const second = store.ensureLoaded()
    expect(getCurrentMenu).toHaveBeenCalledTimes(1)
    pending.resolve([leaf(1, '/hvac-demo')])
    await Promise.all([first, second])
    expect(store.loaded).toBe(true)
  })

  it('reload generation rejects stale ownership', async () => {
    const oldRequest = deferred<ReturnType<typeof leaf>[]>()
    const newRequest = deferred<ReturnType<typeof leaf>[]>()
    vi.mocked(getCurrentMenu)
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise)
    const store = useMenuStore()

    const oldLoad = store.ensureLoaded()
    const newLoad = store.reload()
    newRequest.resolve([leaf(2, '/system/users')])
    await newLoad
    oldRequest.resolve([leaf(1, '/hvac-demo')])
    await oldLoad
    expect(store.tree[0]?.id).toBe(2)
  })

  it('retains the last successful tree on reload failure', async () => {
    vi.mocked(getCurrentMenu).mockResolvedValueOnce([leaf(1, '/hvac-demo')])
    const store = useMenuStore()
    await store.ensureLoaded()
    vi.mocked(getCurrentMenu).mockRejectedValueOnce(new Error('网络中断'))
    await expect(store.reload()).rejects.toThrow('网络中断')
    expect(store.tree[0]?.id).toBe(1)
    expect(store.error).toBe('网络中断')
  })

  it('clear removes state and pending ownership', async () => {
    const pending = deferred<ReturnType<typeof leaf>[]>()
    vi.mocked(getCurrentMenu).mockReturnValue(pending.promise)
    const store = useMenuStore()
    const load = store.ensureLoaded()
    store.clear()
    pending.resolve([leaf(1, '/hvac-demo')])
    await load
    expect(store.tree).toEqual([])
    expect(store.navigation).toEqual([])
    expect(store.loaded).toBe(false)
  })
})
