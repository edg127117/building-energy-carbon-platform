export const themes = ['office-light'] as const
export type ThemeName = typeof themes[number]
export function applyTheme(name: ThemeName) {
  if (!themes.includes(name)) throw new Error('Unsupported theme')
  document.documentElement.dataset.theme = name
}
