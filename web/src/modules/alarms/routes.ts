import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  { path: '/operations/alarms/liveAlarms', name: 'alarms-live', component: () => import('./pages/AlarmEventsPage.vue'), meta: { titleKey: 'alarms.title' } },
  { path: '/operations/alarms/historyAlarms', name: 'alarms-history', component: () => import('./pages/AlarmEventsPage.vue'), meta: { titleKey: 'alarms.historyTitle' } },
  { path: '/configuration/rules/alarmRules', name: 'alarm-rules', component: () => import('./pages/AlarmRulesPage.vue'), meta: { titleKey: 'alarms.ruleTitle', requiresRelationManager: true } },
]
