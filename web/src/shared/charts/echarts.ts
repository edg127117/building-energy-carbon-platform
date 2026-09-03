import * as echarts from 'echarts/core'
import {
  BarChart,
  LineChart,
  PieChart,
  type BarSeriesOption,
  type LineSeriesOption,
  type PieSeriesOption,
} from 'echarts/charts'
import {
  AriaComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  type AriaComponentOption,
  type GridComponentOption,
  type LegendComponentOption,
  type TooltipComponentOption,
} from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { ComposeOption, EChartsCoreOption } from 'echarts/core'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  AriaComponent,
  SVGRenderer,
])

export type ChartOption = ComposeOption<
  | LineSeriesOption
  | BarSeriesOption
  | PieSeriesOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
  | AriaComponentOption
>

export type { EChartsCoreOption }
export { echarts }
