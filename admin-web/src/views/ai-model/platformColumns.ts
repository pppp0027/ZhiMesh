import { BasicColumn } from '@/components/Table'
import { useI18n } from '@/locales'
import { h } from 'vue'

export interface AiPlatformData {
  id: string
  name: string
  title: string
  baseUrl: string
  apiKey: string
  isProxyEnable: boolean
  isOpenaiApiCompatible: boolean
  createTime: string
  updateTime: string
}

export function getColumns(): BasicColumn<AiPlatformData>[] {
  const { t } = useI18n()
  return [
    {
      title: t('model.platformColumn'),
      key: 'platform',
      width: 190,
      render(row) {
        return h('div', { class: 'model-cell' }, [
          h('strong', row.title || row.name),
          h('small', row.name),
        ])
      },
    },
    {
      title: t('columns.baseUrl'),
      key: 'baseUrl',
    },
    {
      title: t('model.credentialStatusColumn'),
      key: 'credentialStatus',
      width: 120,
      render(row) {
        return row.apiKey ? t('constants.configured') : t('constants.notConfigured')
      },
    },
    {
      title: t('model.protocolColumn'),
      key: 'protocol',
      width: 130,
      render(row) {
        return row.isOpenaiApiCompatible ? t('model.openaiProtocol') : t('model.nativeProtocol')
      },
    },
    { title: t('columns.updateTime'), key: 'updateTime', width: 150 },
  ]
}
