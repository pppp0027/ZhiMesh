import type { DataTableColumn, DataTableColumns } from 'naive-ui'
import { h } from 'vue'
import { NButton, NEllipsis, NTag, NTooltip } from 'naive-ui'
import { t } from '@/locales'

type ItemIndexType = 'embedding' | 'graphical' | 'fulltext'

export const createColumns = (showEmbeddingListFn: Function, showGraphFn: Function, showFileContentFn: Function, changeItemShowModalFn: Function, deleteKbItemFn: Function, retryIndexFn: ((row: KnowledgeBase.Item, indexType: ItemIndexType) => void) | null = null, canWriteFn: () => boolean = () => true): DataTableColumns<KnowledgeBase.Item> => {
  const writable = canWriteFn()
  return [
    // 批量勾选仅服务索引/写操作，只读知识库不展示
    ...(writable
      ? [{
          type: 'selection' as const,
        }]
      : []),
    {
      title: t('knowledgeBase.itemTitle'),
      key: 'title',
      width: 200,
    },
    {
      title: t('knowledgeBase.brief'),
      key: 'brief',
      render(row) {
        return row.brief.substring(0, 50)
      },
    },
    createIndexStatusColumn({
      title: t('knowledgeBase.vectorize'),
      key: 'embeddingStatus',
      doneLabel: t('knowledgeBase.statusVectorized'),
      getStatus: row => row.embeddingStatus,
      getTime: row => row.embeddingStatusChangeTime,
      // 处理中/已完成均可查看已生成的嵌入
      canView: row => row.embeddingStatus === 'DOING' || row.embeddingStatus === 'DONE',
      onView: row => showEmbeddingListFn(row),
      retryHandler: retryIndexFn && writable ? (row => retryIndexFn!(row, 'embedding')) : null,
    }),
    createIndexStatusColumn({
      title: t('knowledgeBase.graphLabel'),
      key: 'graphicalStatus',
      doneLabel: t('knowledgeBase.statusGraphitized'),
      getStatus: row => row.graphicalStatus,
      getTime: row => row.graphicalStatusChangeTime,
      canView: row => row.graphicalStatus === 'DOING' || row.graphicalStatus === 'DONE',
      onView: row => showGraphFn(row),
      retryHandler: retryIndexFn && writable ? (row => retryIndexFn!(row, 'graphical')) : null,
    }),
    createIndexStatusColumn({
      title: t('knowledgeBase.fulltextStatus'),
      key: 'fulltextStatus',
      doneLabel: t('knowledgeBase.statusFulltextIndexed'),
      getStatus: row => row.fulltextStatus || 'NONE',
      getTime: row => row.fulltextStatusChangeTime,
      canView: () => false,
      onView: () => {
      },
      retryHandler: retryIndexFn && writable ? (row => retryIndexFn!(row, 'fulltext')) : null,
    }),
    {
      title: t('knowledgeBase.attachment'),
      key: 'sourceFileName',
      width: 150,
      render(row) {
        const soureFile = !!row.sourceFileUuid
        if (soureFile) {
          return h('div', {
            class: 'flex flex-col',
            onClick: () => showFileContentFn(row),
          },
          {
            default: () => [h(
              NEllipsis,
              {
                lineClamp: 3,
                style: 'color:var(--zhimesh-primary);cursor:pointer',
              },
              { default: () => row.sourceFileName || row.title },
            ),
            ],
          })
        } else {
          return t('common.none')
        }
      },
    },
    {
      // 创建/更新时间信息重复，仅保留更新时间
      title: t('knowledgeBase.updateTime'),
      key: 'updateTime',
      width: 180,
    },
    {
      title: t('common.action'),
      key: 'actions',
      width: 110,
      align: 'center',
      render(row) {
        // 只读访问级别（READER / 非成员公开库 / 企业库）不提供条目写操作
        if (!canWriteFn())
          return '-'
        return h('div', { class: 'flex items-center justify-center gap-1' }, {
          default: () => [
            h(
              NButton,
              {
                tertiary: true,
                size: 'tiny',
                type: 'info',
                onClick: () => changeItemShowModalFn(row),
              },
              { default: () => t('common.edit') },
            ),
            h(
              NButton,
              {
                tertiary: true,
                size: 'tiny',
                type: 'error',
                onClick: () => deleteKbItemFn(row),
              },
              { default: () => t('common.delete') },
            ),
          ],
        })
      },
    },
  ]
}

/**
 * 索引状态列：单行 NTag（待处理 default / 处理中 info / 成功 success / 失败 error，
 * 对齐 admin-web statusTagType 映射），时间戳移入 tooltip；
 * 失败行内提供“重试”入口（携带该列默认索引类型）。
 */
function createIndexStatusColumn(options: {
  title: string
  key: string
  doneLabel: string
  getStatus: (row: KnowledgeBase.Item) => string
  getTime: (row: KnowledgeBase.Item) => string
  canView: (row: KnowledgeBase.Item) => boolean
  onView: (row: KnowledgeBase.Item) => void
  retryHandler: ((row: KnowledgeBase.Item) => void) | null
}): DataTableColumn<KnowledgeBase.Item> {
  return {
    title: options.title,
    key: options.key,
    width: 150,
    render(row) {
      const status = options.getStatus(row)
      let tagType: 'default' | 'info' | 'success' | 'error' = 'default'
      let label = t('knowledgeBase.statusPending')
      if (status === 'DOING') {
        tagType = 'info'
        label = t('knowledgeBase.statusProcessing')
      } else if (status === 'DONE') {
        tagType = 'success'
        label = options.doneLabel
      } else if (status === 'FAIL') {
        tagType = 'error'
        label = t('knowledgeBase.statusFailed')
      }
      const children = [
        h(NTag, { size: 'small', bordered: false, type: tagType }, { default: () => label }),
      ]
      if (options.canView(row)) {
        children.push(createInlineActionButton(t('common.view'), () => options.onView(row)))
      }
      if (status === 'FAIL' && options.retryHandler) {
        children.push(createInlineActionButton(t('common.retry'), () => options.retryHandler!(row)))
      }
      const cell = h('div', { class: 'flex items-center gap-1' }, { default: () => children })
      const time = options.getTime(row)
      if (!time)
        return cell
      return h(NTooltip, { trigger: 'hover' }, {
        trigger: () => cell,
        default: () => time,
      })
    },
  }
}

function createInlineActionButton(label: string, onClick: () => void) {
  return h(
    NButton,
    {
      text: true,
      size: 'small',
      type: 'info',
      onClick,
    },
    { default: () => label },
  )
}
