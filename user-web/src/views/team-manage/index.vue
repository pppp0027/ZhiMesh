<script setup lang='ts'>
import type { DataTableColumns } from 'naive-ui'
import { h, reactive, ref, watch } from 'vue'
import { NBreadcrumb, NBreadcrumbItem, NButton, NDataTable, NInput, NTag, useDialog, useMessage } from 'naive-ui'
import { useAuthStore, useKbStore } from '@/store'
import { t } from '@/locales'
import api from '@/api'
import { openDeleteDialog } from '@/utils/dialog'
import TeamEditModal from './components/TeamEditModal.vue'
import MemberDrawer from './components/MemberDrawer.vue'

const ms = useMessage()
const dialog = useDialog()
const authStore = useAuthStore()
const kbStore = useKbStore()

const loading = ref(false)
const infoList = ref<Team.Info[]>([])
const searchValue = ref('')
const showEditModal = ref(false)
const editingTeam = ref<Team.Info | null>(null)
const memberDrawerTeam = ref<Team.Info | null>(null)
const showMemberDrawer = ref(false)
let searchGeneration = 0

const paginationReactive = reactive({
  page: 1,
  pageSize: 20,
  itemCount: 0,
})

const roleTagMap: Record<Team.TeamRole, { type: 'warning' | 'info' | 'default', label: () => string }> = {
  OWNER: { type: 'warning', label: () => t('team.roleOwner') },
  CONTRIBUTOR: { type: 'info', label: () => t('team.roleContributor') },
  READER: { type: 'default', label: () => t('team.roleReader') },
}

function roleTag(role: Team.TeamRole) {
  const meta = roleTagMap[role] || roleTagMap.READER
  return h(NTag, { size: 'small', type: meta.type }, { default: () => meta.label() })
}

const columns = (): DataTableColumns<Team.Info> => [
  {
    title: t('team.teamName'),
    key: 'name',
    width: 180,
  },
  {
    title: t('common.description'),
    key: 'remark',
    ellipsis: { tooltip: true },
  },
  {
    title: t('team.myRole'),
    key: 'myRole',
    width: 110,
    render: row => roleTag(row.myRole),
  },
  {
    title: t('team.memberCount'),
    key: 'memberCount',
    width: 90,
    align: 'center',
  },
  {
    title: t('knowledgeBase.createTime'),
    key: 'createTime',
    width: 170,
    render: row => (row.createTime || '').replace('T', ' ').slice(0, 16),
  },
  {
    title: t('common.action'),
    key: 'actions',
    width: 220,
    align: 'center',
    render(row) {
      const isOwner = row.myRole === 'OWNER'
      return h('div', { class: 'flex gap-1 justify-center' }, {
        default: () => [
          h(
            NButton,
            {
              tertiary: true,
              class: 'readable-accent-button',
              size: 'tiny',
              type: 'info',
              onClick: () => {
                memberDrawerTeam.value = row
                showMemberDrawer.value = true
              },
            },
            { default: () => t('team.memberManage') },
          ),
          isOwner
            ? h(
              NButton,
              {
                tertiary: true,
                class: 'readable-accent-button',
                size: 'tiny',
                type: 'info',
                onClick: () => {
                  editingTeam.value = row
                  showEditModal.value = true
                },
              },
              { default: () => t('common.edit') },
            )
            : null,
          isOwner
            ? h(
              NButton,
              {
                tertiary: true,
                size: 'tiny',
                type: 'error',
                onClick: () => deleteTeam(row),
              },
              { default: () => t('team.deleteTeam') },
            )
            : h(
              NButton,
              {
                tertiary: true,
                size: 'tiny',
                type: 'warning',
                onClick: () => leaveTeam(row),
              },
              { default: () => t('team.leaveTeam') },
            ),
        ],
      })
    },
  },
]

async function onHandlePageChange(currentPage: number) {
  search(currentPage)
}

async function onKeyUpSearch(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    search(1)
  }
}

async function search(currentPage: number) {
  const requestId = ++searchGeneration
  loading.value = true
  try {
    const resp = await api.teamSearchMine<Team.InfoListResp>(searchValue.value, currentPage, paginationReactive.pageSize)
    if (requestId !== searchGeneration)
      return
    infoList.value = resp.data.records
    paginationReactive.page = currentPage
    paginationReactive.itemCount = resp.data.total
  } catch (error: any) {
    if (requestId !== searchGeneration)
      return
    console.error('team search failed', error)
    ms.error(error?.message || t('common.wrong'))
  } finally {
    if (requestId === searchGeneration)
      loading.value = false
  }
}

function deleteTeam(row: Team.Info) {
  openDeleteDialog(dialog, {
    title: t('team.deleteTeam'),
    content: `${t('team.deleteTeamConfirm', { name: row.name })}${t('team.deleteTeamBlockedHint')}`,
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.teamDelete(row.uuid)
        ms.success(t('common.deleteSuccess'))
        // Team KBs may have disappeared; refresh the QA sider lists too.
        kbStore.setReloadKbInfosSignal(true)
        await search(paginationReactive.page)
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

function leaveTeam(row: Team.Info) {
  openDeleteDialog(dialog, {
    title: t('team.leaveTeam'),
    content: t('team.leaveTeamConfirm', { name: row.name }),
    positiveText: t('common.confirm'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.teamLeave(row.uuid)
        ms.success(t('common.succeed'))
        kbStore.setReloadKbInfosSignal(true)
        await search(1)
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

watch(
  () => authStore.token,
  (token) => {
    if (token)
      search(1)
    else
      infoList.value = []
  },
  { immediate: true },
)
</script>

<template>
  <div class="flex flex-col w-full p-4">
    <NBreadcrumb separator=">">
      <NBreadcrumbItem href="/">
        {{ t('common.home') }}
      </NBreadcrumbItem>
      <NBreadcrumbItem :href="`#/qa/${kbStore.activeKbUuid}`">
        {{ t('menu.knowledgeBase') }}
      </NBreadcrumbItem>
      <NBreadcrumbItem :clickable="false">
        {{ t('team.manage') }}
      </NBreadcrumbItem>
    </NBreadcrumb>
    <div class="flex gap-3 mb-2 mt-1 flex-row justify-between">
      <div class="flex items-center space-x-4">
        <NButton
          type="primary" size="small" @click="() => {
            editingTeam = null
            showEditModal = true
          }"
        >
          {{ t('team.createTeam') }}
        </NButton>
      </div>
      <div class="flex justify-between">
        <NInput v-model:value="searchValue" style="width: 100%" @keyup="onKeyUpSearch" />
        <NButton type="primary" ghost @click="search(1)">
          {{ t('common.search') }}
        </NButton>
      </div>
    </div>
    <NDataTable
      remote :loading="loading" :columns="columns()" :data="infoList" :pagination="paginationReactive"
      :single-line="false" :bordered="true" @update:page="onHandlePageChange"
    />
    <div v-if="!loading && infoList.length === 0" class="mt-4 text-center text-sm opacity-60">
      {{ t('team.emptyTeamHint') }}
    </div>

    <TeamEditModal v-model:show="showEditModal" :team="editingTeam" @saved="search(1)" />
    <MemberDrawer
      v-model:show="showMemberDrawer" :team="memberDrawerTeam"
      @changed="search(paginationReactive.page)"
    />
  </div>
</template>
