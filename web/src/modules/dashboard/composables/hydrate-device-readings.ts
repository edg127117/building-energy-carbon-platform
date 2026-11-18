/** 每批最多同时补全两台设备，避免后台读数占满同源连接并阻塞页面导航。 */
export async function hydrateDeviceReadings<T>(items: T[], signal: AbortSignal, read: (item: T) => Promise<void>) {
  const devices = items.values()
  async function worker() {
    while (!signal.aborted) {
      const next = devices.next()
      if (next.done) return
      await read(next.value)
    }
  }
  await Promise.all([worker(), worker()])
}
