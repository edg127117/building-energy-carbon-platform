import type { CarbonIntensityView } from '../api/carbon-intensity'
import sample from './carbon-intensity.fixture.json'

export function intensityView(overrides: Partial<CarbonIntensityView> = {}): CarbonIntensityView {
  return { ...structuredClone(sample), ...overrides } as CarbonIntensityView
}
