/** 简单类型安全事件发射器 */
export type Listener<T> = (payload: T) => void

export class Emitter<Events extends Record<string, unknown>> {
  private listeners: { [K in keyof Events]?: Listener<Events[K]>[] } = {}

  on<K extends keyof Events>(event: K, cb: Listener<Events[K]>): () => void {
    ;(this.listeners[event] ||= []).push(cb)
    return () => this.off(event, cb)
  }

  off<K extends keyof Events>(event: K, cb: Listener<Events[K]>) {
    const arr = this.listeners[event]
    if (!arr) return
    const i = arr.indexOf(cb)
    if (i >= 0) arr.splice(i, 1)
  }

  emit<K extends keyof Events>(event: K, payload: Events[K]) {
    const arr = this.listeners[event]
    if (!arr) return
    for (const cb of [...arr]) {
      try {
        cb(payload)
      } catch (e) {
        console.error('[emitter]', event, e)
      }
    }
  }
}