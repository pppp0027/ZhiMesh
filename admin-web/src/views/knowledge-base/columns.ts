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
  /** 归属层级 PERSONAL/TEAM/COMPANY；旧行未回填时按 PERSONAL 处理。 */
  ownerType?: 'PERSONAL' | 'TEAM' | 'COMPANY'
  /** TEAM 归属的团队 id。 */
  teamId?: string
  /** TEAM 归属的团队名称（后端批量填充）。 */
  teamName?: string
  /** 企业库可见范围 STAFF/EXECUTIVE；非 COMPANY 归属恒为 STAFF。 */
  companyScope?: 'STAFF' | 'EXECUTIVE'
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
      title: t('knowledgeBase.ownerType'),
      key: 'ownerType',
      width: 120,
      render(row) {
        if (row.ownerType === 'TEAM')
          return row.teamName
            ? `${t('knowledgeBase.ownerTypeTeam')}·${row.teamName}`
            : t('knowledgeBase.ownerTypeTeam')
        if (row.ownerType === 'COMPANY')
          return t('knowledgeBase.ownerTypeCompany')
        return t('knowledgeBase.ownerTypePersonal')
      },
    },
    {
      title: t('knowledgeBase.companyScope'),
      key: 'companyScope',
      width: 110,
      render(row) {
        // 仅企业库有分级语义；其余归属恒为 STAFF，不展示避免噪音
        if (row.ownerType !== 'COMPANY')
          return '-'
        return row.companyScope === 'EXECUTIVE'
          ? t('knowledgeBase.companyScopeExecutive')
          : t('knowledgeBase.companyScopeStaff')
      },
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
