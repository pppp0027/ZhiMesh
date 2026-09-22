<script setup lang='ts'>
import { ref, watch } from 'vue'
import { NAvatar, NButton, NDivider, NFlex, NIcon, NModal, NTag, NTooltip, useDialog, useMessage } from 'naive-ui'
import { Star24Filled, Star24Regular } from '@vicons/fluent'
import { Bookmarks, VectorBeizer2 } from '@vicons/tabler'
import { useKbStore } from '@/store'
import { knowledgeBaseEmptyInfo } from '@/utils/functions'
import api from '@/api'
import { t } from '@/locales'
import { getAvatarPoolUrl, getUserAvatarUrl } from '@/utils/avatar'
import { openDeleteDialog } from '@/utils/dialog'

interface Props {
  showModal: boolean
  knowledgeBase: KnowledgeBase.Info
}
interface Emit {
  (ev: 'showModal', show: boolean): void
}
const props = withDefaults(defineProps<Props>(), {
  showModal: false,
  knowledgeBase: () => knowledgeBaseEmptyInfo(),
})
const emit = defineEmits<Emit>()
const kbStore = useKbStore()
const dialog = useDialog()
const ms = useMessage()
const innerShow = ref<boolean>(props.showModal)

function handleClearHistory(kbInfo: KnowledgeBase.Info) {
  openDeleteDialog(dialog, {
    title: t('knowledgeBase.clearHistory'),
    content: t('knowledgeBase.clearKbHistoryConfirm'),
    positiveText: t('common.clear'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.knowledgeBaseQaRecordClear(kbInfo.uuid)
        kbStore.clearRecords(kbInfo.uuid)
        ms.success(t('common.success'))
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}
async function handleClickStar(kbInfo: KnowledgeBase.Info) {
  const starOrUnstarResp = await api.knowledgeBaseStar<boolean>(kbInfo.uuid)
  const starOrUnstar = starOrUnstarResp.data
  kbStore.insertOrUpdateStarInfo({ kbUuid: kbInfo.uuid, kbTitle: kbInfo.title, star: starOrUnstar })
  kbInfo.starCount = starOrUnstar ? kbInfo.starCount + 1 : kbInfo.starCount - 1
}

// 归属标签：可见性按归属推导，团队库带团队名、企业库按可见范围标注
function ownerTierLabel(kbInfo: KnowledgeBase.Info) {
  if (kbInfo.ownerType === 'TEAM')
    return kbInfo.teamName ? `${t('knowledgeBase.ownerTypeTeam')}·${kbInfo.teamName}` : t('knowledgeBase.ownerTypeTeam')
  if (kbInfo.ownerType === 'COMPANY')
    return kbInfo.companyScope === 'EXECUTIVE'
      ? `${t('knowledgeBase.ownerTypeCompany')}·${t('knowledgeBase.companyScopeExecutive')}`
      : `${t('knowledgeBase.ownerTypeCompany')}·${t('knowledgeBase.companyScopeStaff')}`
  return t('knowledgeBase.ownerTypePersonal')
}

watch(() => props.showModal, (val) => {
  innerShow.value = val
})
watch(() => innerShow.value, (val) => {
  if (!val)
    emit('showModal', false)
})
</script>

<template>
  <NModal v-model:show="innerShow" :title="knowledgeBase.title" style="width: 90%; max-width: 640px" preset="card">
    <NFlex vertical>
      <NFlex justify="space-between">
        <NTag size="medium" :bordered="false" round>
          {{ knowledgeBase.ownerName }}
          <template #avatar>
            <NAvatar
              :src="getUserAvatarUrl(knowledgeBase.ownerUuid)" size="large"
              :fallback-src="getAvatarPoolUrl(knowledgeBase.ownerUuid || knowledgeBase.ownerName)"
            />
          </template>
        </NTag>
        <NFlex>
          <NTooltip trigger="hover">
            <template #trigger>
              <NTag size="medium" :bordered="false" round>
                {{ knowledgeBase.itemCount }}
                <template #icon>
                  <NIcon :component="Bookmarks" depth="2" />
                </template>
              </NTag>
            </template>
            {{ t('knowledgeBase.knowledgePoint') }}
          </NTooltip>
          <NTooltip trigger="hover">
            <template #trigger>
              <NTag size="medium" :bordered="false" round>
                {{ knowledgeBase.embeddingCount }}
                <template #icon>
                  <NIcon :component="VectorBeizer2" depth="2" />
                </template>
              </NTag>
            </template>
            {{ t('knowledgeBase.vector') }}
          </NTooltip>
          <NTag
            size="medium" :bordered="false" round checkable
            @click="handleClickStar(knowledgeBase)"
          >
            {{ knowledgeBase.starCount }}
            <template #icon>
              <NIcon v-show="!kbStore.kbUuidToStarInfo.get(knowledgeBase.uuid)?.star" :component="Star24Regular" />
              <NIcon
                v-show="kbStore.kbUuidToStarInfo.get(knowledgeBase.uuid)?.star" :component="Star24Filled"
                color="#eac54f"
              />
            </template>
          </NTag>
        </NFlex>
      </NFlex>
      <NFlex>
        <NTooltip trigger="hover">
          <template #trigger>
            <NTag size="small" :bordered="false">
              {{ ownerTierLabel(knowledgeBase) }}
            </NTag>
          </template>
          {{ t('knowledgeBase.ownerTierDesc') }}
        </NTooltip>
        <NTooltip trigger="hover">
          <template #trigger>
            <NTag size="small" :bordered="false">
              {{ knowledgeBase.isStrict ? t('knowledgeBase.strictMode') : t('knowledgeBase.looseMode') }}
            </NTag>
          </template>
          {{ knowledgeBase.isStrict ? t('knowledgeBase.strictModeDescShort') : t('knowledgeBase.looseModeDescShort') }}
        </NTooltip>
        <NTooltip trigger="hover">
          <template #trigger>
            <NTag size="small" :bordered="false">
              {{ t('knowledgeBase.maxRecallCount') }}{{ knowledgeBase.retrieveMaxResults === 0 ? '-' : knowledgeBase.retrieveMaxResults }}
            </NTag>
          </template>
          {{ t('knowledgeBase.maxRecallCountTip') }}
        </NTooltip>
        <NTooltip trigger="hover">
          <template #trigger>
            <NTag size="small" :bordered="false">
              {{ t('knowledgeBase.minRecallScore') }}{{ knowledgeBase.retrieveMinScore === 0 ? '-' : knowledgeBase.retrieveMinScore }}
            </NTag>
          </template>
          {{ t('knowledgeBase.minRecallScoreTip') }}
        </NTooltip>
      </NFlex>
      <NDivider />
      <div>{{ knowledgeBase.remark }}</div>
    </NFlex>
    <template #footer>
      <NButton size="small" text type="primary" @click="handleClearHistory(knowledgeBase)">
        {{ t('knowledgeBase.clearHistory') }}
      </NButton>
    </template>
  </NModal>
</template>
