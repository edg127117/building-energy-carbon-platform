import type { InjectionKey, Ref } from 'vue'

/** 大屏页面调用已有外壳能力；画布缩放、全屏和工作区导航仍由应用层负责。 */
export interface ScreenShell {
  openNavigation(): void
  toggleFullscreen(): Promise<void>
  exit(): void
  fullscreen: Readonly<Ref<boolean>>
}
export const screenShellKey: InjectionKey<ScreenShell> = Symbol('screen-shell')
