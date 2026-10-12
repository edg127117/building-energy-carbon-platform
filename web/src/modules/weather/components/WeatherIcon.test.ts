import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WeatherIcon from './WeatherIcon.vue'

describe('预报天气图标', () => {
  it.each([
    ['晴', 'sun'], ['多云', 'cloud-sun'], ['阴', 'cloud'], ['小雨', 'cloud-drizzle'],
    ['中雨', 'cloud-rain'], ['大雨', 'cloud-rain-wind'], ['小雪', 'cloud-snow'],
    ['雷阵雨', 'cloud-lightning'], ['雾', 'cloud-fog'], ['未知', 'circle-help'],
    [null, 'circle-help'],
  ])('%s 使用对应图形', (weather, icon) => {
    expect(mount(WeatherIcon, { props: { weather } }).find(`.lucide-${icon}`).exists()).toBe(true)
  })
  it('小雨转阴按顺序显示雨、箭头、云', () => {
    const wrapper = mount(WeatherIcon, { props: { weather: '小雨转阴' } })
    expect(wrapper.findAll('svg').map(s => s.classes().find(c => c.startsWith('lucide-') && !c.endsWith('-icon'))))
      .toEqual(['lucide-cloud-drizzle', 'lucide-arrow-right', 'lucide-cloud'])
  })
})
