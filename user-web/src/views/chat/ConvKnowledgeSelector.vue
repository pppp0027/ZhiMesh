<script setup lang='ts'>
import { computed, h, reactive, ref, watch } from 'vue'
import { NButton, NDataTable, NInput, NTag, useMessage } from 'naive-ui'
import type { DataTableColumns, DataTableRowData, DataTableRowKey } from 'naive-ui'
import { getDefaultCharacter } from '@/store/modules/chat/helper'
import { useChatStore, useUserStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
interface Props {
  character: Chat.Character
  tmpSave: boolean // 是否临时保存(即不保存到远程)
}
const props = withDefaults(defineProps<Props>(), {
  character: () => getDefaultCharacter(),
})
const emit = defineEmits<Emit>()
interface Emit {
  (e: 'selectedChanged', knowledgeIds: string[], knowledgeList: Chat.CharacterKnowledge[]): void
  (e: 'submitted', knowledgeIds: string[], knowledgeList: Chat.CharacterKnowledge[]): void
}
const ms = useMessage()
const userStore = useUserStore()
const chatStore = useChatStore()
const loading = ref(false)
const knowledgeList = ref<KnowledgeBase.Info[]>([])
const paginationReactive = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
})
const tmpCharacterKnowledgeList = ref<Chat.CharacterKnowledge[]>([])
const tmpKnowledgeIds = ref<string[]>([])
const cacheRows = ref<KnowledgeBase.Info[]>([])
const searchValue = ref<string>('')
const checkedRowKeysRef = ref<DataTableRowKey[]>([])

const knowledgeBaseLimit = computed(() => Math.max(0, props.character.knowledgeBaseLimit ?? 8))
const systemKnowledgeCount = computed(() => Math.max(0, props.character.systemKnowledgeCount ?? 0))
const userKnowledgeLimit = computed(() => Math.max(0, knowledgeBaseLimit.value - systemKnowledgeCount.value))
const remainingKnowledgeSlots = computed(() => Math.max(0, userKnowledgeLimit.value - tmpKnowledgeIds.value.length))
const isKnowledgeLimitReached = computed(() => remainingKnowledgeSlots.value === 0)

function isSelected(knowledgeId: string | number) {
  return tmpKnowledgeIds.value.some(id => String(id) === String(knowledgeId))
}

function isSelectionDisabled(row: KnowledgeBase.Info) {
  return isKnowledgeLimitReached.value && !isSelected(row.id)
}

// table相关
const createColumns = (): DataTableColumns<KnowledgeBase.Info> => {
  return [
    {
      type: 'selection',
      disabled: isSelectionDisabled,
    },
    {
      title: t('chat.characterKnowledgeTitle'),
      key: 'title',
      width: 200,
    },
    {
      title: t('chat.characterKnowledgeDescription'),
      key: 'remark',
    },
    {
      title: t('chat.characterKnowledgeAttribute'),
      key: 'remark',
      render(row) {
        return h('div', { class: 'flex items-center space-x-1' }, {
          default: () => [h(
            NTag,
            {
              tertiary: true,
              size: 'small',
            },
            { default: () => row.ownerName === userStore.userInfo?.name ? t('common.mine') : row.ownerName },
          ),
          h(
            NTag,
            {
              tertiary: true,
              size: 'small',
            },
            { default: () => row.isPublic ? t('common.public') : t('common.private') },
          ),
          ],
        })
      },
    },
  ]
}

const columns = createColumns()

function handleClose(knowledgeId: string) {
  const index = tmpCharacterKnowledgeList.value.findIndex(kb => kb.id === knowledgeId)
  if (index !== -1) {
    tmpCharacterKnowledgeList.value.splice(index, 1)
    const idIndex = tmpKnowledgeIds.value.findIndex(id => id === knowledgeId)
    if (idIndex !== -1)
      tmpKnowledgeIds.value.splice(idIndex, 1)
  }
  emit('selectedChanged', tmpKnowledgeIds.value, tmpCharacterKnowledgeList.value)
}

async function onHandlePageChange(page: number) {
  search(page)
}

async function onKeyUpSearch(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    search(1)
  }
}

async function search(currentPage: number) {
  if (loading.value) {
    ms.warning(t('common.loadingPleaseWait'), {
      duration: 2000,
    })
    return
  }
  loading.value = true
  try {
    const resp = await api.knowledgeBaseSearchMine<KnowledgeBase.InfoListResp>(searchValue.value, currentPage, paginationReactive.pageSize, true)
    knowledgeList.value = resp.data.records
    paginationReactive.page = currentPage
    paginationReactive.itemCount = resp.data.total
  } finally {
    loading.value = false
  }
}

function onHandleCheck(rowKeys: DataTableRowKey[], rows: DataTableRowData[]) {
  const normalizedKeys = Array.from(new Set(rowKeys.map(key => String(key))))
  const nextKeys = normalizedKeys.slice(0, userKnowledgeLimit.value)
  if (nextKeys.length < normalizedKeys.length) {
    ms.warning(t('chat.characterKnowledgeLimitReached', { limit: knowledgeBaseLimit.value }), {
      duration: 2500,
    })
  }
  checkedRowKeysRef.value = nextKeys
  tmpKnowledgeIds.value = nextKeys
  const map = new Map()
  const selectedRows = (rows as KnowledgeBase.Info[]).filter(item => !!item)
  cacheRows.value = cacheRows.value.concat(selectedRows).filter(item => !map.has(item.id) && map.set(item.id, 0))
  tmpCharacterKnowledgeList.value = cacheRows.value.filter((item: KnowledgeBase.Info) => isSelected(item.id)).map(knowledge => ({
    id: knowledge.id,
    uuid: knowledge.uuid,
    title: knowledge.title,
    isMine: knowledge.ownerUuid === userStore.userInfo.uuid,
    isPublic: knowledge.isPublic,
    kbInfo: knowledge,
    isEnable: true,
  }))
  emit('selectedChanged', tmpKnowledgeIds.value, tmpCharacterKnowledgeList.value)
}

async function handleSubmit() {
  try {
    await api.characterEdit(props.character.uuid, {
      kbIds: tmpKnowledgeIds.value,
    })
    chatStore.updateCharacter(props.character.uuid, { kbIds: tmpKnowledgeIds.value, characterKnowledgeList: tmpCharacterKnowledgeList.value })
    ms.success(t('chat.characterKnowledgeSaved'), {
      duration: 3000,
    })
    emit('submitted', tmpKnowledgeIds.value, tmpCharacterKnowledgeList.value)
  } catch (error) {
    console.error('handleSaveKnowledge error', error)
    ms.error(t('chat.characterKnowledgeSaveFailed'), {
      duration: 3000,
    })
  }
}

watch(() => props.character.kbIds, (newVal) => {
  console.log('watch newVal', newVal)
  tmpCharacterKnowledgeList.value = props.character.characterKnowledgeList || []
  tmpKnowledgeIds.value = (newVal || []).map(String).slice(0, userKnowledgeLimit.value)
  checkedRowKeysRef.value = tmpKnowledgeIds.value

  if (knowledgeList.value.length === 0)
    search(1)
}, { immediate: true, deep: true })
</script>

<template>
  <div class="flex flex-col space-y-2">
    <div class="mb-2">
      <NTag
        v-for="characterKnowledge in tmpCharacterKnowledgeList" :key="characterKnowledge.uuid" closable class="mr-2"
        @close="handleClose(characterKnowledge.id)"
      >
        {{ characterKnowledge.title }}
      </NTag>
    </div>
    <div class="knowledge-limit-hint" role="status">
      {{ t('chat.characterKnowledgeLimitHint', {
        limit: knowledgeBaseLimit,
        system: systemKnowledgeCount,
        remaining: remainingKnowledgeSlots,
      }) }}
    </div>
    <div>
      <div class="flex justify-between">
        <NInput v-model:value="searchValue" class="mr-2" :placeholder="t('chat.searchTitlePlaceholder')" clearable @keyup="onKeyUpSearch" />
        <NButton type="primary" ghost @click="search(1)">
          {{ t('common.search') }}
        </NButton>
      </div>
      <NDataTable
        v-model:checked-row-keys="tmpKnowledgeIds" remote striped :loading="loading" :columns="columns"
        :data="knowledgeList" :pagination="paginationReactive" :single-line="false" :row-key="(row) => row.id"
        :bordered="true" @update:page="onHandlePageChange" @update:checked-row-keys="onHandleCheck"
      />
      <div v-if="!tmpSave" class="flex justify-end mt-4">
        <NButton type="primary" @click="handleSubmit">
          {{ t('common.save') }}
        </NButton>
      </div>
    </div>
  </div>
</template>

<style scoped lang="less">
.knowledge-limit-hint {
  margin-bottom: 4px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  line-height: 1.45;
}
</style>
