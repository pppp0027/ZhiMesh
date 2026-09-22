<script setup lang='ts'>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { NTabPane, NTabs, useMessage } from 'naive-ui'
import SubList from './SubList.vue'
import LoginTip from '@/views/user/LoginTip.vue'
import { useAuthStore, useKbStore } from '@/store'
import { t } from '@/locales'
import api from '@/api'

const route = useRoute()
const router = useRouter()
const currentPage = ref<number>(1)
const pageSize = 20
const kbStore = useKbStore()
const { activeKbUuid, myKbInfos, teamKbInfos, companyKbInfos, selectedKbType } = storeToRefs<any>(kbStore)
const authStore = useAuthStore()
const authStoreRef = ref<AuthState>(authStore)
const message = useMessage()
const kbListLoaded = ref(false)
let listGeneration = 0
const { kbUuid: currKbUuid } = route.params as { kbUuid: string }

// F5 reload
if (currKbUuid !== 'default' && kbStore.activeKbUuid === 'default')
  kbStore.setActive(currKbUuid)

async function selectAvailableKb() {
  if (!kbListLoaded.value)
    return

  const activeMine = myKbInfos.value.find((item: KnowledgeBase.Info) => item.uuid === activeKbUuid.value)
  if (activeMine) {
    kbStore.selectedKbType = 'mine'
    return
  }
  const activeTeam = teamKbInfos.value.find((item: KnowledgeBase.Info) => item.uuid === activeKbUuid.value)
  if (activeTeam) {
    kbStore.selectedKbType = 'team'
    return
  }
  const activeCompany = companyKbInfos.value.find((item: KnowledgeBase.Info) => item.uuid === activeKbUuid.value)
  if (activeCompany) {
    kbStore.selectedKbType = 'company'
    return
  }

  // Fallback chain: personal first, then team, then company.
  const nextKb = myKbInfos.value[0] || teamKbInfos.value[0] || companyKbInfos.value[0]
  kbStore.selectedKbType = myKbInfos.value[0]
    ? 'mine'
    : teamKbInfos.value[0] ? 'team' : 'company'
  kbStore.setActive(nextKb?.uuid || 'default')

  const nextUuid = nextKb?.uuid || 'default'
  if (route.params.kbUuid !== nextUuid)
    await router.replace({ name: 'QADetail', params: { kbUuid: nextUuid } })
}

async function initStarredList() {
  try {
    const starListResp = await api.knowledgeBaseStarListMine<KnowledgeBase.KbStarListResp>(1, 100)
    kbStore.appStarInfos(starListResp.data.records || [])
  } catch (error) {
    console.error('load starred knowledge bases failed', error)
  }
}

async function refreshLists(force = false) {
  if (!force && kbListLoaded.value) {
    await selectAvailableKb()
    return
  }

  const requestId = ++listGeneration
  kbStore.setLoadingKbList(true)
  // All three sections derive visibility from the caller's identity, so
  // nothing is fetchable without a signed-in session; the empty state with
  // the login hint is all an anonymous visitor gets.
  const loggedIn = !!authStoreRef.value.token
  const mineRequest = loggedIn
    ? api.knowledgeBaseSearchMine<KnowledgeBase.InfoListResp>('', currentPage.value, pageSize)
    : Promise.resolve(null)
  const teamRequest = loggedIn
    ? api.knowledgeBaseSearchTeam<KnowledgeBase.InfoListResp>('', currentPage.value, pageSize)
    : Promise.resolve(null)
  const companyRequest = loggedIn
    ? api.knowledgeBaseSearchCompany<KnowledgeBase.InfoListResp>('', currentPage.value, pageSize)
    : Promise.resolve(null)

  try {
    const [mineResult, teamResult, companyResult] = await Promise.allSettled(
      [mineRequest, teamRequest, companyRequest])
    if (requestId !== listGeneration)
      return

    if (mineResult.status === 'fulfilled')
      kbStore.setMyKbInfos(mineResult.value?.data.records || [])
    else
      console.error('load my knowledge bases failed', mineResult.reason)
    kbListLoaded.value = true
    kbStore.setKbListLoaded(true)

    if (teamResult.status === 'fulfilled')
      kbStore.setTeamKbInfos(teamResult.value?.data.records || [])
    else
      console.error('load team knowledge bases failed', teamResult.reason)

    if (companyResult.status === 'fulfilled')
      kbStore.setCompanyKbInfos(companyResult.value?.data.records || [])
    else
      console.error('load company knowledge bases failed', companyResult.reason)

    if (mineResult.status === 'rejected' || teamResult.status === 'rejected' || companyResult.status === 'rejected')
      message.error(t('common.wrong'))

    await selectAvailableKb()
  } finally {
    if (requestId === listGeneration)
      kbStore.setLoadingKbList(false)
  }
}

watch(
  () => authStoreRef.value.token,
  async (newVal) => {
    if (newVal) {
      kbListLoaded.value = false
      kbStore.setKbListLoaded(false)
      await Promise.all([refreshLists(true), initStarredList()])
    } else {
      kbStore.setMyKbInfos([])
      kbStore.setTeamKbInfos([])
      kbStore.setCompanyKbInfos([])
      kbListLoaded.value = true
      kbStore.setKbListLoaded(true)
      await refreshLists(true)
    }
  },
)

watch(
  () => kbStore.reloadKbInfosSignal,
  async (newVal) => {
    if (newVal) {
      try {
        await Promise.all([refreshLists(true), initStarredList()])
      } finally {
        kbStore.setReloadKbInfosSignal(false)
      }
    }
  },
)

onMounted(async () => {
  if (!authStoreRef.value.token) {
    kbListLoaded.value = true
    kbStore.setKbListLoaded(true)
  }
  await Promise.all([
    refreshLists(),
    authStoreRef.value.token ? initStarredList() : Promise.resolve(),
  ])
})
</script>

<template>
  <!-- 未登录：三组分区均依赖登录身份，整体替换为登录引导，不渲染空 Tab 壳 -->
  <div v-if="!authStoreRef.token" class="kb-sider-login">
    <LoginTip />
  </div>
  <NTabs v-else v-model:value="selectedKbType" tab-class="h-10" pane-class="h-full" type="line" justify-content="space-evenly" class="kb-sider-tabs">
    <NTabPane name="mine" :tab="t('common.mine')">
      <SubList :list="myKbInfos" :active-kb-uuid="activeKbUuid" />
    </NTabPane>
    <NTabPane name="team" :tab="t('common.team')">
      <SubList :list="teamKbInfos" :active-kb-uuid="activeKbUuid" />
    </NTabPane>
    <NTabPane name="company" :tab="t('common.company')">
      <SubList :list="companyKbInfos" :active-kb-uuid="activeKbUuid" />
    </NTabPane>
  </NTabs>
</template>

<style scoped>
.kb-sider-tabs {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.kb-sider-login {
  padding: 12px 12px 0;
}
</style>

<style>
.kb-sider-tabs .n-tabs-pane-wrapper {
  flex: 1 !important;
  min-height: 0 !important;
  overflow: hidden !important;
}
.kb-sider-tabs .n-tab-pane {
  height: 100% !important;
  overflow: hidden !important;
}
</style>
