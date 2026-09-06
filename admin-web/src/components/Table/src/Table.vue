<template>
  <div class="table-toolbar">
    <!--顶部左侧区域-->
    <div class="flex items-center table-toolbar-left">
      <template v-if="title">
        <div class="table-toolbar-left-title">
          {{ title }}
          <n-tooltip trigger="hover" v-if="titleTooltip">
            <template #trigger>
              <n-icon size="18" class="ml-1 text-gray-400 cursor-pointer">
                <QuestionCircleOutlined />
              </n-icon>
            </template>
            {{ titleTooltip }}
          </n-tooltip>
        </div>
      </template>
      <slot name="tableTitle"></slot>
    </div>

    <div class="flex items-center table-toolbar-right">
      <!--顶部右侧区域-->
      <slot name="toolbar"></slot>

      <!--斑马纹-->
      <n-tooltip trigger="hover">
        <template #trigger>
          <div class="mr-2 table-toolbar-right-icon table-toolbar-optional">
            <n-switch
              v-model:value="isStriped"
              :aria-label="t('table.stripe')"
              @update:value="setStriped"
            />
          </div>
        </template>
        <span>{{ t('table.stripe') }}</span>
      </n-tooltip>
      <n-divider vertical class="table-toolbar-optional" />

      <!--刷新-->
      <n-tooltip trigger="hover">
        <template #trigger>
          <button
            type="button"
            class="table-toolbar-right-icon"
            :aria-label="t('table.refresh')"
            @click="reload"
          >
            <n-icon size="18">
              <ReloadOutlined />
            </n-icon>
          </button>
        </template>
        <span>{{ t('table.refresh') }}</span>
      </n-tooltip>

      <!--密度-->
      <n-tooltip trigger="hover">
        <template #trigger>
          <n-dropdown
            @select="densitySelect"
            trigger="click"
            :options="densityOptions"
            v-model:value="tableSize"
          >
            <button
              type="button"
              class="table-toolbar-right-icon table-toolbar-optional"
              :aria-label="t('table.density')"
            >
              <n-icon size="18">
                <ColumnHeightOutlined />
              </n-icon>
            </button>
          </n-dropdown>
        </template>
        <span>{{ t('table.density') }}</span>
      </n-tooltip>

      <!--表格设置单独抽离成组件-->
      <ColumnSetting />
    </div>
  </div>
  <div class="s-table">
    <n-data-table
      ref="tableElRef"
      v-bind="getBindValues"
      :striped="isStriped"
      :pagination="pagination"
      @update:page="updatePage"
      @update:page-size="updatePageSize"
    >
      <template #[item]="data" v-for="item in Object.keys($slots)" :key="item">
        <slot :name="item" v-bind="data"></slot>
      </template>
    </n-data-table>
  </div>
</template>

<script lang="ts">
  import {
    ref,
    defineComponent,
    reactive,
    unref,
    toRaw,
    computed,
    toRefs,
    onMounted,
    nextTick,
  } from 'vue'
  import { ReloadOutlined, ColumnHeightOutlined, QuestionCircleOutlined } from '@vicons/antd'
  import { createTableContext } from './hooks/useTableContext'

  import ColumnSetting from './components/settings/ColumnSetting.vue'

  import { useLoading } from './hooks/useLoading'
  import { useColumns } from './hooks/useColumns'
  import { useDataSource } from './hooks/useDataSource'
  import { usePagination } from './hooks/usePagination'

  import { basicProps } from './props'

  import { BasicTableProps } from './types/table'

  import { getViewportOffset } from '@/utils/domUtils'
  import { useWindowSizeFn } from '@/hooks/event/useWindowSizeFn'
  import { isBoolean } from '@/utils/is'
  import { t } from '@/locales'

  const densityOptions = [
    {
      type: 'menu',
      label: t('table.densityCompact'),
      key: 'small',
    },
    {
      type: 'menu',
      label: t('table.densityDefault'),
      key: 'medium',
    },
    {
      type: 'menu',
      label: t('table.densityLoose'),
      key: 'large',
    },
  ]

  export default defineComponent({
    components: {
      ReloadOutlined,
      ColumnHeightOutlined,
      ColumnSetting,
      QuestionCircleOutlined,
    },
    props: {
      ...basicProps,
    },
    emits: [
      'fetch-success',
      'fetch-error',
      'update:checked-row-keys',
      'edit-end',
      'edit-cancel',
      'edit-row-end',
      'edit-change',
    ],
    setup(props, { emit }) {
      const deviceHeight = ref(150)
      const tableElRef = ref<ComponentRef>(null)
      const wrapRef = ref<Nullable<HTMLDivElement>>(null)
      let paginationEl: HTMLElement | null
      const isStriped = ref(false)
      const tableData = ref<Recordable[]>([])
      const innerPropsRef = ref<Partial<BasicTableProps>>()

      const getProps = computed(() => {
        return { ...props, ...unref(innerPropsRef) } as BasicTableProps
      })

      const { getLoading, setLoading } = useLoading(getProps)

      const { getPaginationInfo, setPagination } = usePagination(getProps)

      const { getDataSourceRef, getDataSource, getRowKey, reload } = useDataSource(
        getProps,
        {
          getPaginationInfo,
          setPagination,
          tableData,
          setLoading,
        },
        emit
      )

      const { getPageColumns, setColumns, getColumns, getCacheColumns, setCacheColumnsField } =
        useColumns(getProps)

      function resolveColumnWidth(column: any): number {
        if (Array.isArray(column.children) && column.children.length) {
          return column.children.reduce((total, child) => total + resolveColumnWidth(child), 0)
        }
        const candidate = column.width ?? column.minWidth
        if (typeof candidate === 'number') return candidate
        if (typeof candidate === 'string') {
          const parsed = Number.parseFloat(candidate)
          if (Number.isFinite(parsed)) return parsed
        }
        // Naive UI also gives unspecified columns a practical minimum width.
        return 120
      }

      const getResolvedScrollX = computed(() => {
        const configured = Number((unref(getProps) as any).scrollX)
        if (!Number.isFinite(configured) || configured <= 0) {
          return (unref(getProps) as any).scrollX
        }
        const contentWidth = unref(getPageColumns).reduce(
          (total, column) => total + resolveColumnWidth(column),
          0
        )
        // Keep a small buffer so the last column is not covered by a fixed column shadow.
        return Math.max(configured, contentWidth + 8)
      })

      const state = reactive({
        tableSize: unref(getProps as any).size || 'medium',
        isColumnSetting: false,
      })

      //页码切换
      function updatePage(page) {
        setPagination({ page: page })
        reload()
      }

      //分页数量切换
      function updatePageSize(size) {
        setPagination({ page: 1, pageSize: size })
        reload()
      }

      //密度切换
      function densitySelect(e) {
        state.tableSize = e
      }

      //选中行
      function updateCheckedRowKeys(rowKeys) {
        emit('update:checked-row-keys', rowKeys)
      }

      //获取表格大小
      const getTableSize = computed(() => state.tableSize)

      //组装表格信息
      const getBindValues = computed(() => {
        const tableData = unref(getDataSourceRef)
        const maxHeight = tableData.length ? `${unref(deviceHeight)}px` : 'auto'
        return {
          ...unref(getProps),
          loading: unref(getLoading),
          columns: toRaw(unref(getPageColumns)),
          rowKey: unref(getRowKey),
          data: tableData,
          size: unref(getTableSize),
          remote: true,
          scrollX: unref(getResolvedScrollX),
          'max-height': maxHeight,
        }
      })

      //获取分页信息
      const pagination = computed(() => toRaw(unref(getPaginationInfo)))

      function setProps(props: Partial<BasicTableProps>) {
        innerPropsRef.value = { ...unref(innerPropsRef), ...props }
      }

      const setStriped = (value: boolean) => (isStriped.value = value)

      const tableAction = {
        reload,
        setColumns,
        setLoading,
        setProps,
        getColumns,
        getPageColumns,
        getCacheColumns,
        setCacheColumnsField,
        emit,
      }

      const getCanResize = computed(() => {
        const { canResize } = unref(getProps)
        return canResize
      })

      async function computeTableHeight() {
        const table = unref(tableElRef)
        if (!table) return
        if (!unref(getCanResize)) return
        const tableEl: any = table?.$el
        const headEl = tableEl.querySelector('.n-data-table-thead ')
        const { bottomIncludeBody } = getViewportOffset(headEl)
        const headerH = 64
        let paginationH = 2
        let marginH = 24
        if (!isBoolean(unref(pagination))) {
          paginationEl = tableEl.querySelector('.n-data-table__pagination') as HTMLElement
          if (paginationEl) {
            const offsetHeight = paginationEl.offsetHeight
            paginationH += offsetHeight || 0
          } else {
            paginationH += 28
          }
        }
        let height =
          bottomIncludeBody - (headerH + paginationH + marginH + (props.resizeHeightOffset || 0))
        const maxHeight = props.maxHeight
        height = maxHeight && maxHeight < height ? maxHeight : height
        deviceHeight.value = height
      }

      useWindowSizeFn(computeTableHeight, 280)

      onMounted(() => {
        nextTick(() => {
          computeTableHeight()
        })
      })

      createTableContext({ ...tableAction, wrapRef, getBindValues })

      return {
        ...toRefs(state),
        t,
        tableElRef,
        getBindValues,
        getDataSource,
        densityOptions,
        reload,
        densitySelect,
        updatePage,
        updatePageSize,
        pagination,
        tableAction,
        setStriped,
        isStriped,
      }
    },
  })
</script>
<style lang="less" scoped>
  .table-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 2px 2px 18px;

    &-left {
      display: flex;
      align-items: center;
      justify-content: flex-start;
      min-width: 0;
      flex: 1;

      &-title {
        display: flex;
        align-items: center;
        justify-content: flex-start;
        font-size: 16px;
        font-weight: 600;
      }
    }

    &-right {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      flex: none;
      flex-wrap: wrap;
      gap: 6px;

      &-icon {
        display: grid;
        place-items: center;
        width: 40px;
        height: 40px;
        margin: 0;
        padding: 0;
        border: 1px solid var(--zhimesh-border-subtle);
        border-radius: 7px;
        background: var(--zhimesh-glass);
        font-size: 16px;
        font-family: inherit;
        cursor: pointer;
        color: var(--zhimesh-muted);
        transition: color 0.16s ease, background-color 0.16s ease;

        &:hover {
          color: var(--zhimesh-primary);
          background: var(--zhimesh-table-cell-hover);
        }
      }
    }
  }

  .table-toolbar-inner-popover-title {
    padding: 2px 0;
  }

  .s-table {
    overflow: hidden;
    border: 1px solid var(--zhimesh-border-subtle);
    border-radius: 10px;
    background: var(--zhimesh-glass);
  }

  :deep(.n-data-table__pagination) {
    margin: 0;
    padding: 16px 14px 14px;
    border-top: 1px solid var(--zhimesh-border-subtle);
  }

  :deep(.tableAction > div) {
    flex-wrap: nowrap;
    white-space: nowrap;
  }

  @media (max-width: 800px) {
    .table-toolbar {
      padding: 0 0 12px;
      align-items: stretch;
      flex-direction: column;

      &-left,
      &-right {
        width: 100%;
      }

      &-right {
        justify-content: space-between;
      }

      &-right-icon {
        width: 44px;
        height: 44px;
      }
    }

    .table-toolbar-optional {
      display: none !important;
    }

    .s-table {
      overflow: hidden;
      border-radius: 12px;
    }

    :deep(.n-data-table__pagination) {
      padding: 12px 8px 10px;
      justify-content: flex-start;
      overflow-x: auto;
      overscroll-behavior-x: contain;
    }

    :deep(.n-pagination) {
      min-width: max-content;
    }
  }
</style>
