<script setup lang="ts">
import { computed } from 'vue'
import { NSpin, NTabPane, NTabs } from 'naive-ui'
import { t } from '@/locales'

interface Props {
  userConfig: User.Config
  loading?: boolean
}

const props = defineProps<Props>()

interface QuotaMetric {
  label: string
  value: number
  limit?: number
}

const paidTextMetrics = computed<QuotaMetric[]>(() => [
  { label: t('setting.todayRequestCount'), value: props.userConfig.quotaCost.paidRequestTimes.todayRequestTimes, limit: props.userConfig.userQuota.requestTimesByDay },
  { label: t('setting.todayTokenCost'), value: props.userConfig.quotaCost.paidTokenCost.todayTokenCost, limit: props.userConfig.userQuota.tokenByDay },
  { label: t('setting.monthRequestCount'), value: props.userConfig.quotaCost.paidRequestTimes.monthRequestTimes, limit: props.userConfig.userQuota.requestTimesByMonth },
  { label: t('setting.monthTokenCost'), value: props.userConfig.quotaCost.paidTokenCost.monthTokenCost, limit: props.userConfig.userQuota.tokenByMonth },
])

const paidImageMetrics = computed<QuotaMetric[]>(() => [
  { label: t('setting.todayDrawCount'), value: props.userConfig.quotaCost.paidDrawTimes.todayDrawTimes, limit: props.userConfig.userQuota.drawByDay },
  { label: t('setting.monthDrawCount'), value: props.userConfig.quotaCost.paidDrawTimes.monthDrawTimes, limit: props.userConfig.userQuota.drawByMonth },
])

const freeTextMetrics = computed<QuotaMetric[]>(() => [
  { label: t('setting.todayRequestCount'), value: props.userConfig.quotaCost.freeRequestTimes.todayRequestTimes },
  { label: t('setting.todayTokenCost'), value: props.userConfig.quotaCost.freeTokenCost.todayTokenCost },
  { label: t('setting.monthRequestCount'), value: props.userConfig.quotaCost.freeRequestTimes.monthRequestTimes },
  { label: t('setting.monthTokenCost'), value: props.userConfig.quotaCost.freeTokenCost.monthTokenCost },
])

const freeImageMetrics = computed<QuotaMetric[]>(() => [
  { label: t('setting.todayDrawCount'), value: props.userConfig.quotaCost.freeDrawTimes.todayDrawTimes },
  { label: t('setting.monthDrawCount'), value: props.userConfig.quotaCost.freeDrawTimes.monthDrawTimes },
])

function formatNumber(value: number) {
  return new Intl.NumberFormat().format(value ?? 0)
}
</script>

<template>
  <div class="quota-settings">
    <NSpin :show="loading">
      <NTabs type="segment" animated class="quota-tabs">
        <NTabPane name="Paid" :tab="t('setting.paidModel')">
          <div class="quota-sections">
            <section class="quota-section" aria-labelledby="paid-text-title">
              <h4 id="paid-text-title">
                {{ t('setting.textChat') }}
              </h4>
              <dl class="quota-metrics">
                <div v-for="metric in paidTextMetrics" :key="metric.label" class="quota-metric">
                  <dt>{{ metric.label }}</dt>
                  <dd>
                    <strong>{{ formatNumber(metric.value) }}</strong>
                    <span>/ {{ formatNumber(metric.limit ?? 0) }}</span>
                  </dd>
                </div>
              </dl>
            </section>

            <section class="quota-section" aria-labelledby="paid-image-title">
              <h4 id="paid-image-title">
                {{ t('setting.imageGenerate') }}
              </h4>
              <dl class="quota-metrics quota-metrics--compact">
                <div v-for="metric in paidImageMetrics" :key="metric.label" class="quota-metric">
                  <dt>{{ metric.label }}</dt>
                  <dd>
                    <strong>{{ formatNumber(metric.value) }}</strong>
                    <span>/ {{ formatNumber(metric.limit ?? 0) }}</span>
                  </dd>
                </div>
              </dl>
            </section>
          </div>
        </NTabPane>

        <NTabPane name="Free" :tab="t('setting.freeModel')">
          <div class="quota-sections">
            <section class="quota-section" aria-labelledby="free-text-title">
              <h4 id="free-text-title">
                {{ t('setting.textChat') }}
              </h4>
              <dl class="quota-metrics">
                <div v-for="metric in freeTextMetrics" :key="metric.label" class="quota-metric">
                  <dt>{{ metric.label }}</dt>
                  <dd><strong>{{ formatNumber(metric.value) }}</strong></dd>
                </div>
              </dl>
            </section>

            <section class="quota-section" aria-labelledby="free-image-title">
              <h4 id="free-image-title">
                {{ t('setting.imageGenerate') }}
              </h4>
              <dl class="quota-metrics quota-metrics--compact">
                <div v-for="metric in freeImageMetrics" :key="metric.label" class="quota-metric">
                  <dt>{{ metric.label }}</dt>
                  <dd><strong>{{ formatNumber(metric.value) }}</strong></dd>
                </div>
              </dl>
            </section>
          </div>
        </NTabPane>
      </NTabs>
    </NSpin>
  </div>
</template>

<style scoped>
.quota-settings {
  padding: 16px 2px 2px;
}

.quota-tabs :deep(.n-tabs-rail) {
  padding: 4px;
  border: 1px solid var(--zhimesh-border-subtle);
}

.quota-tabs :deep(.n-tabs-tab) {
  min-height: 36px;
  font-weight: 650;
}

.quota-sections {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-top: 16px;
}

.quota-section {
  min-width: 0;
}

.quota-section > h4 {
  margin: 0 0 8px;
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 680;
}

.quota-metrics {
  display: grid;
  overflow: hidden;
  margin: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 8px;
}

.quota-metric {
  min-width: 0;
  padding: 11px 12px;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
}

.quota-metric:nth-child(odd) {
  border-right: 1px solid var(--zhimesh-border-subtle);
}

.quota-metric:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.quota-metrics--compact .quota-metric {
  border-bottom: 0;
}

.quota-metric dt {
  overflow-wrap: anywhere;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.quota-metric dd {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 5px;
  margin: 4px 0 0;
  color: var(--zhimesh-text-muted);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.quota-metric strong {
  overflow: hidden;
  color: var(--zhimesh-text);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.015em;
  text-overflow: ellipsis;
}

.quota-metric dd span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 520px) {
  .quota-metrics {
    grid-template-columns: 1fr;
  }

  .quota-metric,
  .quota-metric:nth-child(odd),
  .quota-metric:nth-last-child(-n + 2),
  .quota-metrics--compact .quota-metric {
    border-right: 0;
    border-bottom: 1px solid var(--zhimesh-border-subtle);
  }

  .quota-metric:last-child,
  .quota-metrics--compact .quota-metric:last-child {
    border-bottom: 0;
  }
}
</style>
