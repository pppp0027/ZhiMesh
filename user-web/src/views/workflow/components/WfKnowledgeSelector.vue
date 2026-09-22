<script setup lang='ts'>
import { h, nextTick, ref, watch } from 'vue'
import { NIcon, NSelect } from 'naive-ui'
import { Building24Regular, PeopleTeam32Regular, Person32Regular } from '@vicons/fluent'
import type { SelectGroupOption, SelectInst, SelectOption } from 'naive-ui'
import type { VNodeChild } from 'vue'
import { debounce } from '@/utils/functions/debounce'
import { useAuthStore, useUserStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
interface Props {
  knowledgeBaseUuid: string
}
interface Emit {
  (e: 'selected', knowledge_base_uuid: string, knowledge_base_name: string): void
}
const props = withDefaults(defineProps<Props>(), {
  knowledgeBaseUuid: '',
})
const emit = defineEmits<Emit>()
const selectInstRef = ref<SelectInst | null>(null)
const authStore = useAuthStore()
const userStore = useUserStore()
const selectedKnowledgeUuid = ref<string>(props.knowledgeBaseUuid)
const currentPage = ref<number>(1)
const pageSize = 10
const mineGroup = ref<SelectGroupOption>({
  type: 'group',
  label: t('common.mine'),
  key: 'g_mine',
  children: [] as Array<{ label: string; value: string; ownerType: string }>,
})
const teamGroup = ref<SelectGroupOption>({
  type: 'group',
  label: t('common.team'),
  key: 'g_team',
  children: [] as Array<{ label: string; value: string; ownerType: string }>,
})
const companyGroup = ref<SelectGroupOption>({
  type: 'group',
  label: t('common.company'),
  key: 'g_company',
  children: [] as Array<{ label: string; value: string; ownerType: string }>,
})
const options: Array<SelectOption | SelectGroupOption> = [mineGroup.value, teamGroup.value, companyGroup.value]

function ownerTierIcon(ownerType: unknown) {
  if (ownerType === 'COMPANY')
    return Building24Regular
  if (ownerType === 'TEAM')
    return PeopleTeam32Regular
  return Person32Regular
}

function renderLabel(option: SelectOption): VNodeChild {
  if (option.type === 'group')
    return option.label as string
  return [
    h('div', { class: 'flex items-center' }, {
      default: () => [
        h(
          NIcon,
          {
            style: {
              verticalAlign: '-0.15em',
              marginRight: '4px',
            },
          },
          {
            default: () => h(ownerTierIcon(option.ownerType)),
          },
        ),
        h(
          'div',
          {
            class: 'ml-1.5',
          },
          { default: () => option.label as string },
        ),
      ],
    }),
  ]
}

function handleSelect(knowledgeBaseUuid: string) {
  let kbName = ''
  const groups = [mineGroup.value, teamGroup.value, companyGroup.value]
  const hit = groups.flatMap(group => group.children ?? []).find(child => child.value === knowledgeBaseUuid)

  if (hit)
    kbName = hit.label as string

  emit('selected', knowledgeBaseUuid, kbName)
}

const handleSearch = debounce(search, 300)
async function search(query: string) {
  try {
    await searchMine(query)
    // 团队/企业分组按成员/全员可见性推导，需要登录态
    if (authStore.token) {
      await searchTeam(query)
      await searchCompany(query)
    }
  } catch (e) {
    console.log(e)
  }
}

async function searchMine(query: string) {
  const { data } = await api.knowledgeBaseSearchMine<KnowledgeBase.InfoListResp>(query, currentPage.value, pageSize)
  mineGroup.value.children = []
  data.records.forEach((item) => {
    mineGroup.value.children?.push({
      value: item.uuid,
      label: item.title,
      ownerType: item.ownerType || 'PERSONAL',
    })
  })
}

async function searchTeam(query: string) {
  const { data } = await api.knowledgeBaseSearchTeam<KnowledgeBase.InfoListResp>(query, currentPage.value, pageSize)
  teamGroup.value.children = []
  data.records.forEach((item) => {
    teamGroup.value.children?.push({
      value: item.uuid,
      label: item.teamName ? `${item.teamName}·${item.title}` : item.title,
      ownerType: item.ownerType || 'TEAM',
    })
  })
}

async function searchCompany(query: string) {
  const { data } = await api.knowledgeBaseSearchCompany<KnowledgeBase.InfoListResp>(query, currentPage.value, pageSize)
  companyGroup.value.children = []
  data.records.forEach((item) => {
    companyGroup.value.children?.push({
      value: item.uuid,
      label: item.title,
      ownerType: item.ownerType || 'COMPANY',
    })
  })
}

/**
 * 1. 初始化选中项不存在，按 个人 → 团队 → 企业 顺序取第一个可选项
 * 2. 初始化选中项存在，检查选中项是否在下拉列表中，没有则请求一次该选中的知识库详情，按归属层级追回到对应分组；
 *    他人个人库不再有"公开"分组可回退，直接忽略（后端执行期 resolver 也会拒绝）
 */
async function checkAndGetSelected() {
  if (!selectedKnowledgeUuid.value) {
    const firstAvailable = [mineGroup.value, teamGroup.value, companyGroup.value]
      .flatMap(group => group.children ?? [])
      .find(child => !!child.value)

    if (firstAvailable)
      selectedKnowledgeUuid.value = firstAvailable.value as string

    if (selectedKnowledgeUuid.value)
      handleSelect(selectedKnowledgeUuid.value)

    return
  }
  const hit = [mineGroup.value, teamGroup.value, companyGroup.value]
    .flatMap(group => group.children ?? [])
    .find(child => child.value === selectedKnowledgeUuid.value)

  if (!hit) {
    const resp = await api.knowledgeBaseInfo<KnowledgeBase.Info>(selectedKnowledgeUuid.value)
    const kb = resp.data
    const group = kb.ownerType === 'TEAM'
      ? teamGroup.value
      : kb.ownerType === 'COMPANY'
        ? companyGroup.value
        : kb.ownerUuid === userStore.userInfo?.uuid ? mineGroup.value : null
    group?.children?.push({
      value: kb.uuid,
      label: kb.title,
      ownerType: kb.ownerType || 'PERSONAL',
    })
  }
}

watch(
  () => authStore.token,
  () => {
    if (authStore.token) {
      nextTick(async () => {
        await search('')
        await checkAndGetSelected()
      })
    }
  },
  { immediate: true },
)
</script>

<template>
  <NSelect
    ref="selectInstRef" v-model:value="selectedKnowledgeUuid" filterable :placeholder="t('workflow.searchKnowledgeBase')" :options="options"
    clearable remote :render-label="renderLabel" @update:value="handleSelect" @search="handleSearch"
  />
</template>
