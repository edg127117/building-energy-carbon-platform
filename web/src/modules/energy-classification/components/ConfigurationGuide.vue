<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElEmpty, ElSkeleton, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { requestErrorMessage } from '@/shared/utils/request-error'
import {
  configurationGuideApi,
  type ConfigurationGuide,
  type ConfigurationGuideDomain,
  type ConfigurationGuideState,
} from '../api/configuration-guide'

const props = defineProps<{ buildingId: string; versionId: string; revision: number; dirty: boolean }>()
const emit = defineEmits<{ navigate: [domain: ConfigurationGuideDomain] }>()

const guide = ref<ConfigurationGuide | null>(null)
const loading = ref(false)
const error = ref('')
const emptyDescription = computed(() => {
  if (props.dirty) return t('energyClassification.configurationGuide.dirty')
  if (!props.buildingId || !props.versionId) return t('energyClassification.configurationGuide.noContext')
  return t('energyClassification.configurationGuide.empty')
})
let generation = 0
let activeRequest: AbortController | null = null

// 组件只展示与当前建筑、版本和修订匹配的服务端引导；切换时中止旧请求并使迟到结果失效。
function resetGuide(): void {
  generation++
  activeRequest?.abort()
  activeRequest = null
  loading.value = false
  error.value = ''
  guide.value = null
}

watch(() => [props.buildingId, props.versionId, props.revision, props.dirty], resetGuide)
onBeforeUnmount(resetGuide)

function stateTagType(state: ConfigurationGuideState): 'success' | 'warning' | 'danger' | 'info' {
  if (state === 'CONFIGURED') return 'success'
  if (state === 'MISSING' || state === 'BLOCKED') return 'danger'
  if (state === 'REVIEW_REQUIRED' || state === 'ACTIVATION_REQUIRED') return 'warning'
  return 'info'
}

function canNavigate(domain: ConfigurationGuideDomain): boolean {
  return domain !== 'FACTORS'
}

async function loadGuide(): Promise<void> {
  if (props.dirty || !props.buildingId || !props.versionId || loading.value) return

  resetGuide()
  const owner = generation
  const buildingId = props.buildingId
  const versionId = props.versionId
  const revision = props.revision
  const controller = new AbortController()
  activeRequest = controller
  loading.value = true

  try {
    const response = await configurationGuideApi.get(buildingId, versionId, controller.signal)
    if (owner !== generation || props.dirty || buildingId !== props.buildingId || versionId !== props.versionId || revision !== props.revision) return
    if (response.buildingId !== buildingId || response.versionId !== versionId || response.revision !== revision) {
      error.value = t('energyClassification.configurationGuide.stale')
      return
    }
    guide.value = response
  } catch (reason) {
    if (owner === generation) error.value = requestErrorMessage(reason)
  } finally {
    if (owner === generation) {
      loading.value = false
      activeRequest = null
    }
  }
}
</script>

<template>
  <ElCard shadow="never" class="configuration-guide">
    <template #header>
      <div class="guide-heading">
        <div>
          <h3>{{ t('energyClassification.configurationGuide.title') }}</h3>
          <p>{{ t('energyClassification.configurationGuide.description') }}</p>
        </div>
        <ElButton
          type="primary"
          data-test="guide-load"
          :disabled="dirty || !buildingId || !versionId || loading"
          :loading="loading"
          @click="loadGuide"
        >
          {{ t('energyClassification.configurationGuide.load') }}
        </ElButton>
      </div>
    </template>

    <ElAlert type="info" :title="t('energyClassification.configurationGuide.readOnly')" :closable="false" show-icon />
    <ElAlert v-if="dirty" class="guide-alert" type="warning" :title="t('energyClassification.configurationGuide.dirty')" :closable="false" show-icon />
    <ElAlert v-if="error" class="guide-alert" type="error" :title="error" :closable="false" show-icon />
    <ElSkeleton v-if="loading" class="guide-loading" animated :rows="4" />
    <ElEmpty v-else-if="!guide" :description="emptyDescription" :image-size="48" />

    <template v-else>
      <details class="guide-technical-details" data-test="guide-technical-details">
        <summary>{{ t('energyClassification.configurationGuide.technicalDetails') }}</summary>
        <ElDescriptions :column="4" border class="guide-details">
          <ElDescriptionsItem :label="t('energyClassification.configurationGuide.buildingId')">{{ guide.buildingId }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('energyClassification.configurationGuide.versionId')">{{ guide.versionId }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('energyClassification.configurationGuide.revision')">{{ guide.revision }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('energyClassification.configurationGuide.calculationStatus')">
            <ElTag type="info">{{ t('energyClassification.configurationGuide.calculationStatuses.' + guide.calculationStatus) }}</ElTag>
          </ElDescriptionsItem>
        </ElDescriptions>
      </details>

      <section class="guide-steps" :aria-label="t('energyClassification.configurationGuide.steps')">
        <h4>{{ t('energyClassification.configurationGuide.steps') }}</h4>
        <ol>
          <li v-for="step in guide.steps" :key="step.domain" class="guide-step">
            <article>
              <div class="step-heading">
                <ElTag>{{ t('energyClassification.configurationGuide.domains.' + step.domain) }}</ElTag>
                <ElTag :type="stateTagType(step.state)">{{ t('energyClassification.configurationGuide.states.' + step.state) }}</ElTag>
              </div>
              <div class="step-flow">
                <div>
                  <span>{{ t('energyClassification.configurationGuide.responsibility') }}</span>
                  <strong>{{ t('energyClassification.configurationGuide.responsibilities.' + step.responsibility) }}</strong>
                </div>
                <div>
                  <span>{{ t('energyClassification.configurationGuide.action') }}</span>
                  <strong>{{ t('energyClassification.configurationGuide.actions.' + step.action) }}</strong>
                </div>
              </div>
              <ElButton
                v-if="canNavigate(step.domain)"
                link
                type="primary"
                :data-test="'guide-navigate-' + step.domain"
                @click="emit('navigate', step.domain)"
              >
                {{ t('energyClassification.configurationGuide.navigate') }}
              </ElButton>
              <ul v-if="step.issues.length" class="step-issues" :aria-label="t('energyClassification.configurationGuide.issues')">
                <li v-for="(issue, index) in step.issues" :key="issue.code + ':' + (issue.reference ?? '') + ':' + index">
                  <span :data-test="'guide-issue-message-' + step.domain + '-' + index">{{ issue.message }}</span>
                </li>
              </ul>
              <details
                v-if="step.reference !== null || step.revision !== null || step.issues.length"
                class="step-technical-details"
                :data-test="'guide-step-technical-' + step.domain"
              >
                <summary>{{ t('energyClassification.configurationGuide.technicalDetails') }}</summary>
                <dl class="step-technical-list">
                  <div v-if="step.reference !== null">
                    <dt>{{ t('energyClassification.configurationGuide.reference') }}</dt>
                    <dd><code :data-test="'guide-reference-' + step.domain">{{ step.reference }}</code></dd>
                  </div>
                  <div v-if="step.revision !== null">
                    <dt>{{ t('energyClassification.configurationGuide.referenceRevision') }}</dt>
                    <dd>{{ step.revision }}</dd>
                  </div>
                  <div v-if="step.issues.length">
                    <dt>{{ t('energyClassification.configurationGuide.issueCode') }}</dt>
                    <dd>
                      <ul class="issue-identifiers">
                        <li v-for="(issue, index) in step.issues" :key="issue.code + ':' + (issue.reference ?? '') + ':' + index">
                          <code :data-test="'guide-issue-code-' + step.domain + '-' + index">{{ issue.code }}</code>
                          <span v-if="issue.reference !== null">{{ t('energyClassification.configurationGuide.issueReference') }}</span>
                          <code v-if="issue.reference !== null">{{ issue.reference }}</code>
                        </li>
                      </ul>
                    </dd>
                  </div>
                </dl>
              </details>
            </article>
          </li>
        </ol>
      </section>
    </template>
  </ElCard>
</template>

<style scoped>
.configuration-guide { margin-top: var(--bec-space-section); }
.guide-heading { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: var(--bec-space-group); }
.guide-heading h3, .guide-heading p, .guide-steps h4 { margin: 0; }
.guide-heading h3, .guide-steps h4 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.guide-heading p { max-width: var(--bec-text-measure); color: var(--bec-color-text-secondary); }
.guide-alert, .guide-loading, .guide-details, .guide-steps { margin-top: var(--bec-space-section); }
.guide-technical-details, .step-technical-details { margin-top: var(--bec-space-section); }
.guide-technical-details > summary, .step-technical-details > summary { color: var(--bec-color-text-secondary); cursor: pointer; }
.guide-steps { display: grid; gap: var(--bec-space-group); }
.guide-steps ol { display: grid; gap: var(--bec-space-group); padding: 0; margin: 0; list-style: none; }
.guide-step { padding: var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); background: var(--bec-color-surface-secondary); }
.step-heading, .step-flow { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.step-flow { margin: var(--bec-space-group) 0; }
.step-flow > div { display: grid; gap: var(--bec-space-tight); min-width: min(calc(var(--bec-ref-space-64) * 2), 100%); }
.step-flow span, .step-technical-list dt { color: var(--bec-color-text-secondary); }
.step-technical-list { display: grid; gap: var(--bec-space-group); margin: var(--bec-space-group) 0 0; }
.step-technical-list > div { display: grid; gap: var(--bec-space-tight); }
.step-technical-list dd { margin: 0; overflow-wrap: anywhere; }
.step-issues { display: grid; gap: var(--bec-space-tight); padding-left: var(--bec-space-section); margin: var(--bec-space-group) 0 0; }
.step-issues li { overflow-wrap: anywhere; }
.issue-identifiers { display: grid; gap: var(--bec-space-tight); padding-left: var(--bec-space-section); margin: 0; }
.issue-identifiers span, .issue-identifiers code + span { margin-inline-start: var(--bec-space-tight); }
</style>
