import { BasicColumn } from '@/components/Table'
import { useI18n } from '@/locales'
import { wrapTableTitle } from '@/utils'

export interface KbInfoData {
  id: string
  uuid: string
  title: string
  remark: string
  ownerUuid: string
  ownerName: string
  isPublic: boolean
  isSystem: boolean
  isEnabled: boolean
  isStrict: boolean
  ingestMaxOverlap: number
  ingestSplitStrategy: string
  ingestMaxSegmentSize: number
  ingestCustomSeparator: string
  ingestModelId: number
  ingestModelName: string
  ingestTokenEstimator: string
  retrieveMaxResults: number
  retrieveMinScore: number
  graphHopDepth: number
  rerankModelId: number
  rerankTopN: number
  queryLlmTemperature: number
  querySystemMessage: string
  starCount: number
  itemCount: number
  embeddingCount: number
  createTime: string
  updateTime: string
}

export function getColumns(): BasicColumn<KbInfoData>[] {
  const { t } = useI18n()
  return [
    {
      title: 'id',
      key: 'id',
      width: 50,
    },
    {
      title: t('columns.name'),
      key: 'title',
      width: 150,
    },
    {
      title: t('columns.ownerName'),
      key: 'ownerName',
      width: 150,
    },
    {
      title: t('columns.itemCount'),
      key: 'itemCount',
      width: 80,
    },
    {
      title: t('columns.embeddingCount'),
      key: 'embeddingCount',
      width: 80,
    },
    {
      title: t('columns.starCount'),
      key: 'starCount',
      width: 80,
    },
    {
      title: t('columns.isPublic'),
      key: 'isPublic',
      width: 80,
      render(row) {
        return row.isPublic ? t('common.yes') : t('common.no')
      },
    },
    {
      title: t('knowledgeBase.isSystem'),
      key: 'isSystem',
      width: 110,
      render(row) {
        return row.isSystem ? t('common.yes') : t('common.no')
      },
    },
    {
      title: t('knowledgeBase.isEnabled'),
      key: 'isEnabled',
      width: 90,
      render(row) {
        return row.isEnabled === false ? t('common.no') : t('common.yes')
      },
    },
    {
      key: 'ingestMaxOverlap',
      width: 100,
      title() {
        return wrapTableTitle(t('columns.ingestMaxOverlap'))
      },
    },
    {
      key: 'ingestSplitStrategy',
      width: 100,
      title() {
        return wrapTableTitle(t('columns.ingestSplitStrategy'))
      },
    },
    {
      key: 'ingestMaxSegmentSize',
      width: 100,
      title() {
        return wrapTableTitle(t('columns.ingestMaxSegmentSize'))
      },
    },
    {
      key: 'ingestModelName',
      width: 160,
      title() {
        return wrapTableTitle(t('columns.ingestModelName'))
      },
    },
    {
      key: 'retrieveMaxResults',
      width: 100,
      title() {
        return wrapTableTitle(t('columns.retrieveMaxResults'))
      },
    },
    {
      key: 'retrieveMinScore',
      width: 100,
      title() {
        return wrapTableTitle(t('columns.retrieveMinScore'))
      },
    },
    {
      key: 'queryLlmTemperature',
      width: 120,
      title() {
        return wrapTableTitle(t('columns.queryLlmTemperature'))
      },
    },
    {
      title: t('columns.querySystemMessage'),
      key: 'querySystemMessage',
      width: 150,
    },
    {
      title: t('columns.createTime'),
      key: 'createTime',
      width: 180,
    },
    {
      title: t('columns.updateTime'),
      key: 'updateTime',
      width: 180,
    },
  ]
}
