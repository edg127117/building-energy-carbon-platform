type DemoSystem = 'hvac' | 'elevator' | 'lighting'

// 仅供专业下钻展示的固定样例；不读取、覆盖或回写总览的正式指标。
// 样例停在 16:30，之后的小时留空，不随浏览器时钟伪造实时采集。
const profiles: Record<DemoSystem, { hours: number[]; power: number; month: number; change: number; weights: number[] }> = {
  hvac: { hours: [12, 10, 10, 10, 12, 18, 36, 75, 128, 146, 152, 148, 135, 144, 150, 142, 68], power: 136, month: 28640, change: -4.6, weights: [12, 14, 18, 18, 20, 14, 4] },
  elevator: { hours: [2, 1, 1, 1, 1, 2, 4, 12, 18, 10, 8, 9, 14, 12, 9, 8, 4], power: 8, month: 2450, change: -2.8, weights: [20, 16, 16, 16, 16, 16, 0] },
  lighting: { hours: [4, 3, 3, 3, 3, 4, 8, 15, 24, 25, 25, 24, 22, 24, 24, 23, 11], power: 22, month: 6850, change: -3.2, weights: [18, 16, 16, 16, 16, 16, 2] },
}
const floors = ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'Roof']
export const professionalDemoPeriods = Array.from({ length: 24 }, (_, hour) => `${String(hour).padStart(2, '0')}:00`)

export function professionalDemo(topic: string, floor: string) {
  if (topic !== 'hvac' && topic !== 'elevator' && topic !== 'lighting') return null
  const profile = profiles[topic]
  const index = floors.indexOf(floor)
  const weight = index < 0 ? 1 : profile.weights[index] / 100
  const hours = professionalDemoPeriods.map((_, hour) => hour < profile.hours.length ? Number((profile.hours[hour] * weight).toFixed(2)) : null)
  return {
    hours,
    today: hours.reduce<number>((total, value) => total + (value ?? 0), 0).toFixed(2),
    power: (profile.power * weight).toFixed(2),
    month: (profile.month * weight).toFixed(2),
    change: weight === 0 ? null : profile.change.toFixed(1),
    updatedAt: '16:30',
  }
}
