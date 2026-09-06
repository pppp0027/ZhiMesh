<script setup lang='ts'>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { NTabPane, NTabs, useMessage } from 'naive-ui'
import SubList from './SubList.vue'
import { useAuthStore, useKbStore } from '@/store'
import { t } from '@/locales'
import api from '@/api'

const route = useRoute()
const router = useRouter()
const currentPage = ref<number>(1)
const pageSize = 20
const kbStore = useKbStore()
const { activeKbUuid, myKbInfos, publicKbInfos, selectedKbType } = storeToRefs<any>(kbStore)
const authStore = useAuthStore()
const authStoreRef = ref<AuthState>(authStore)
const message = useMessage()
const privateListLoaded = ref(false)
const publicListLoaded = ref(false)
let listGeneration = 0
const { kbUuid: currKbUuid } = route.params as { kbUuid: string }

// F5 reload
if (currKbUuid !== 'default' && kbStore.activeKbUuid === 'default')
  kbStore.setActive(currKbUuid)

async function selectAvailableKb() {
  if (!privateListLoaded.value || !publicListLoaded.value)
    return

  const activeMine = myKbInfos.value.find((item: KnowledgeBase.Info) => item.uuid === activeKbUuid.value)
  const activePublic = publicKbInfos.value.find((item: KnowledgeBase.Info) => item.uuid === activeKbUuid.value)
  if (activeMine || activePublic) {
    kbStore.selectedKbType = activeMine ? 'mine' : 'public'
    return
  }

  const firstMine = myKbInfos.value[0]
  const firstPublic = publicKbInfos.value[0]
  const nextKb = firstMine || firstPublic
  kbStore.selectedKbType = firstMine ? 'mine' : 'public'
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
  if (!force && privateListLoaded.value && publicListLoaded.value) {
    await selectAvailableKb()
    return
  }

  const requestId = ++listGeneration
  kbStore.setLoadingKbList(true)
  const mineRequest = authStoreRef.value.token
    ? api.knowledgeBaseSearchMine<KnowledgeBase.InfoListResp>('', currentPage.value, pageSize)
    : Promise.resolve(null)
  const publicRequest = api.knowledgeBaseSearchPublic<KnowledgeBase.InfoListResp>('', currentPage.value, pageSize)

  try {
    const [mineResult, publicResult] = await Promise.allSettled([mineRequest, publicRequest])
    if (requestId !== listGeneration)
      return

    if (mineResult.status === 'fulfilled')
      kbStore.setMyKbInfos(mineResult.value?.data.records || [])
    else
      console.error('load private knowledge bases failed', mineResult.reason)
    privateListLoaded.value = true

    if (publicResult.status === 'fulfilled')
      kbStore.setPublicKbInfos(publicResult.value.data.records || [])
    else
      console.error('load public knowledge bases failed', publicResult.reason)
    publicListLoaded.value = true

    if (mineResult.status === 'rejected' || publicResult.status === 'rejected')
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
      privateListLoaded.value = false
      await Promise.all([refreshLists(true), initStarredList()])
    } else {
      kbStore.setMyKbInfos([])
      privateListLoaded.value = true
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
  if (!authStoreRef.value.token)
    privateListLoaded.value = true
  await Promise.all([
    refreshLists(),
    authStoreRef.value.token ? initStarredList() : Promise.resolve(),
  ])
})
</script>

<template>
  <NTabs v-model:value="selectedKbType" tab-class="h-10" pane-class="h-full" type="line" justify-content="space-evenly" class="kb-sider-tabs">
    <NTabPane name="mine" :tab="t('common.mine')" size="small">
      <SubList :list="myKbInfos" :active-kb-uuid="activeKbUuid" />
    </NTabPane>
    <NTabPane name="public" :tab="t('common.public')">
      <SubList :list="publicKbInfos" :active-kb-uuid="activeKbUuid" />
    </NTabPane>
  </NTabs>
</template>

<style scoped>
.kb-sider-tabs {
  display: flex;
  flex-direction: column;
  height: 100%;
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
