import * as echarts from 'echarts/core'
import {
  BarChart,
  LineChart,
  PieChart,
  SankeyChart,
  type BarSeriesOption,
  type LineSeriesOption,
  type PieSeriesOption,
  type SankeySeriesOption,
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
  SankeyChart,
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
  | SankeySeriesOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
  | AriaComponentOption
>

export type { EChartsCoreOption }
export { echarts }
