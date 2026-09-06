<template>
  <n-tooltip trigger="hover">
    <template #trigger>
      <n-popover trigger="click" :width="230" class="toolbar-popover" placement="bottom-end">
        <template #trigger>
          <button
            type="button"
            class="table-toolbar-right-icon"
            :aria-label="t('table.columnSetting')"
          >
            <n-icon size="18">
              <SettingOutlined />
            </n-icon>
          </button>
        </template>
          <template #header>
            <div class="table-toolbar-inner-popover-title">
              <n-space>
                <n-checkbox v-model:checked="checkAll" @update:checked="onCheckAll">{{
                  t('table.columnDisplay')
                }}</n-checkbox>
                <n-checkbox v-model:checked="selection" @update:checked="onSelection">{{
                  t('table.columnSelection')
                }}</n-checkbox>
                <n-button text type="info" size="small" class="mt-1" @click="resetColumns">{{
                  t('common.reset')
                }}</n-button>
              </n-space>
            </div>
          </template>
          <div class="table-toolbar-inner">
            <n-checkbox-group v-model:value="checkList" @update:value="onChange">
              <Draggable
                v-model="columnsList"
                animation="300"
                item-key="key"
                filter=".no-draggable"
                :move="onMove"
                @end="draggableEnd"
              >
                <template #item="{ element }">
                  <div
                    class="table-toolbar-inner-checkbox"
                    :class="{
                      'table-toolbar-inner-checkbox-dark': getDarkTheme === true,
                      'no-draggable': element.draggable === false,
                    }"
                  >
                    <span
                      class="drag-icon"
                      :class="{ 'drag-icon-hidden': element.draggable === false }"
                    >
                      <n-icon size="18">
                        <DragOutlined />
                      </n-icon>
                    </span>
                    <n-checkbox :value="element.key" :label="element.title" />
                    <div class="fixed-item">
                      <n-tooltip trigger="hover" placement="bottom">
                        <template #trigger>
                          <button
                            type="button"
                            class="column-pin-button"
                            :class="{ 'is-active': element.fixed === 'left' }"
                            :aria-label="t('table.fixedLeft')"
                            @click="fixedColumn(element, 'left')"
                          >
                            <n-icon size="18"><VerticalRightOutlined /></n-icon>
                          </button>
                        </template>
                        <span>{{ t('table.fixedLeft') }}</span>
                      </n-tooltip>
                      <n-divider vertical />
                      <n-tooltip trigger="hover" placement="bottom">
                        <template #trigger>
                          <button
                            type="button"
                            class="column-pin-button"
                            :class="{ 'is-active': element.fixed === 'right' }"
                            :aria-label="t('table.fixedRight')"
                            @click="fixedColumn(element, 'right')"
                          >
                            <n-icon size="18"><VerticalLeftOutlined /></n-icon>
                          </button>
                        </template>
                        <span>{{ t('table.fixedRight') }}</span>
                      </n-tooltip>
                    </div>
                  </div>
                </template>
              </Draggable>
            </n-checkbox-group>
          </div>
      </n-popover>
    </template>
    <span>{{ t('table.columnSetting') }}</span>
  </n-tooltip>
</template>

<script lang="ts">
  import { ref, defineComponent, reactive, unref, toRaw, computed, toRefs, watchEffect } from 'vue'
  import { useTableContext } from '../../hooks/useTableContext'
  import { cloneDeep } from 'lodash-es'
  import {
    SettingOutlined,
    DragOutlined,
    VerticalRightOutlined,
    VerticalLeftOutlined,
  } from '@vicons/antd'
  import Draggable from 'vuedraggable'
  import { useDesignSetting } from '@/hooks/setting/useDesignSetting'
  import { t } from '@/locales'

  interface Options {
    title: string
    key: string
    fixed?: boolean | 'left' | 'right'
  }

  export default defineComponent({
    name: 'ColumnSetting',
    components: {
      SettingOutlined,
      DragOutlined,
      Draggable,
      VerticalRightOutlined,
      VerticalLeftOutlined,
    },
    setup() {
      const { getDarkTheme } = useDesignSetting()
      const table: any = useTableContext()
      const columnsList = ref<Options[]>([])
      const cacheColumnsList = ref<Options[]>([])

      const state = reactive({
        selection: false,
        checkAll: true,
        checkList: [],
        defaultCheckList: [],
      })

      const getSelection = computed(() => {
        return state.selection
      })

      watchEffect(() => {
        const columns = table.getColumns()
        if (columns.length) {
          init()
        }
      })

      //初始化
      function init() {
        const columns: any[] = getColumns()
        const checkList: any = columns.map((item) => item.key)
        state.checkList = checkList
        state.defaultCheckList = checkList
        const newColumns = columns.filter((item) => item.key != 'action')
        if (!columnsList.value.length) {
          columnsList.value = cloneDeep(newColumns)
          cacheColumnsList.value = cloneDeep(newColumns)
        }
      }

      //切换
      function onChange(checkList) {
        if (state.selection) {
          checkList.unshift('selection')
        }
        setColumns(checkList)
      }

      //设置
      function setColumns(columns) {
        table.setColumns(columns)
      }

      //获取
      function getColumns() {
        let newRet: any[] = []
        table.getColumns().forEach((item) => {
          newRet.push({ ...item })
        })
        return newRet
      }

      //重置
      function resetColumns() {
        state.checkList = [...state.defaultCheckList]
        state.checkAll = true
        let cacheColumnsKeys: any[] = table.getCacheColumns()
        let newColumns = cacheColumnsKeys.map((item) => {
          return {
            ...item,
            fixed: undefined,
          }
        })
        setColumns(newColumns)
        columnsList.value = newColumns
      }

      //全选
      function onCheckAll(e) {
        let checkList = table.getCacheColumns(true)
        if (e) {
          setColumns(checkList)
          state.checkList = checkList
        } else {
          setColumns([])
          state.checkList = []
        }
      }

      //拖拽排序
      function draggableEnd() {
        const newColumns = toRaw(unref(columnsList))
        columnsList.value = newColumns
        setColumns(newColumns)
      }

      //勾选列
      function onSelection(e) {
        let checkList = table.getCacheColumns()
        if (e) {
          checkList.unshift({ type: 'selection', key: 'selection' })
          setColumns(checkList)
        } else {
          checkList.splice(0, 1)
          setColumns(checkList)
        }
      }

      function onMove(e) {
        if (e.draggedContext.element.draggable === false) return false
        return true
      }

      //固定
      function fixedColumn(item, fixed) {
        if (!state.checkList.includes(item.key)) return
        let columns = getColumns()
        const isFixed = item.fixed === fixed ? undefined : fixed
        let index = columns.findIndex((res) => res.key === item.key)
        if (index !== -1) {
          columns[index].fixed = isFixed
        }
        table.setCacheColumnsField(item.key, { fixed: isFixed })
        columnsList.value[index].fixed = isFixed
        setColumns(columns)
      }

      return {
        ...toRefs(state),
        t,
        columnsList,
        getDarkTheme,
        onChange,
        onCheckAll,
        onSelection,
        onMove,
        resetColumns,
        fixedColumn,
        draggableEnd,
        getSelection,
      }
    },
  })
</script>

<style lang="less">
  .table-toolbar {
    &-inner-popover-title {
      padding: 3px 0;
    }

    &-right {
      &-icon {
        display: grid;
        width: 40px;
        height: 40px;
        margin: 0;
        padding: 0;
        place-items: center;
        border: 1px solid var(--zhimesh-border-subtle);
        border-radius: 7px;
        background: var(--zhimesh-glass);
        font-size: 16px;
        color: var(--zhimesh-muted);
        cursor: pointer;
        transition: color 0.16s ease, background-color 0.16s ease;

        &:hover {
          color: var(--zhimesh-primary);
          background: var(--zhimesh-table-cell-hover);
        }
      }
    }
  }

  .table-toolbar-inner {
    &-checkbox {
      display: flex;
      align-items: center;
      padding: 10px 14px;

      &:hover {
        background: var(--zhimesh-table-cell-hover);
      }

      .drag-icon {
        display: inline-flex;
        margin-right: 8px;
        cursor: move;
        &-hidden {
          visibility: hidden;
          cursor: default;
        }
      }

      .fixed-item {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        margin-left: auto;
      }

      .column-pin-button {
        display: grid;
        width: 30px;
        height: 30px;
        padding: 0;
        place-items: center;
        border: 0;
        border-radius: 6px;
        color: var(--zhimesh-muted);
        background: transparent;
        cursor: pointer;

        &:hover,
        &.is-active {
          color: var(--zhimesh-primary);
          background: var(--zhimesh-table-head);
        }
      }

      .ant-checkbox-wrapper {
        flex: 1;

        &:hover {
          color: var(--zhimesh-primary) !important;
        }
      }
    }

    &-checkbox-dark {
      &:hover {
        background: var(--zhimesh-table-cell-hover);
      }
    }
  }

  .toolbar-popover {
    .n-popover__content {
      padding: 0;
    }
  }
</style>
