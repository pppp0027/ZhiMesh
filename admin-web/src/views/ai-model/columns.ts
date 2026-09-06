import { BasicColumn } from '@/components/Table'
import { useI18n } from '@/locales'
import { AiModelData } from '/#/aiModel'
import { h } from 'vue'

export function getColumns(): BasicColumn<AiModelData>[] {
  const { t } = useI18n()
  return [
    {
      title: t('model.modelColumn'),
      key: 'model',
      width: 210,
      render(row) {
        return h('div', { class: 'model-cell' }, [
          h('strong', row.title || row.name),
          h('small', row.name),
        ])
      },
    },
    {
      title: t('model.typePlatformColumn'),
      key: 'typePlatform',
      width: 150,
      render(row) {
        return h('div', { class: 'model-cell' }, [h('strong', row.type), h('small', row.platform)])
      },
    },
    {
      title: t('model.capabilityColumn'),
      key: 'capabilities',
      width: 280,
      render(row) {
        const labels: string[] = []
        if (row.inputTypes?.split(',').includes('image')) labels.push(t('model.imageUnderstanding'))
        if (row.isReasoner) labels.push(t('model.deepThinkingCapability'))
        if (row.isSupportWebSearch) labels.push(t('model.webSearchCapability'))
        if (row.responseFormatTypes?.split(',').includes('json_object')) labels.push('JSON')
        if (row.maxInputTokens) labels.push(`${t('model.inputLimit')} ${row.maxInputTokens}`)
        if (!labels.length) labels.push(t('model.textConversation'))
        return h(
          'div',
          { class: 'capability-tags' },
          labels.map((label) => h('span', { class: 'capability-tag' }, label))
        )
      },
    },
    {
      title: t('model.runtimeColumn'),
      key: 'runtime',
      width: 160,
      render(row) {
        if (row.platform !== 'OpenRouter') {
          return row.isEnable ? t('common.enable') : t('common.disable')
        }
        const state = row.openRouterState
        const status = state?.lifecycleStatus || 'PENDING'
        const statusLabels: Record<string, string> = {
          ENABLED: t('model.openRouterSyncEnabled'),
          DISABLED: t('model.openRouterSyncDisabled'),
          PROTECTED: t('model.openRouterSyncProtected'),
          DISCOVERED: t('model.openRouterSyncDiscovered'),
          PENDING: t('model.openRouterSyncPending'),
        }
        const failed = status === 'DISABLED'
        const reason = state?.disableReason || state?.lastErrorMessage
        const latency = state?.actualTtftMs
        return h(
          'div',
          {
            class: 'runtime-state',
            title: reason || statusLabels[status] || status,
          },
          [
            h(
              'strong',
              {
                style: {
                  color: failed
                    ? 'var(--zhimesh-danger-text)'
                    : status === 'PENDING'
                    ? 'var(--zhimesh-muted)'
                    : 'var(--zhimesh-accent-text)',
                },
              },
              statusLabels[status] || status
            ),
            latency != null
              ? h('small', `${t('model.openRouterTtft')} ${latency} ms`)
              : reason
              ? h('small', reason)
              : null,
          ]
        )
      },
    },
    {
      title: t('model.billingPolicyColumn'),
      key: 'billing',
      width: 110,
      render(row) {
        return row.isFree ? t('model.freeModel') : t('model.paidModel')
      },
    },
    {
      title: t('columns.updateTime'),
      key: 'updateTime',
      width: 150,
    },
  ]
}
