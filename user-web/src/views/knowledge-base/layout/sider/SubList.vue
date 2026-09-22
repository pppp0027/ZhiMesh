<script setup lang='ts'>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NIcon, NSpin } from 'naive-ui'
import { Building24Regular, PeopleTeam24Regular, Person24Regular } from '@vicons/fluent'
import { useKbStore } from '@/store'
import { SvgIcon } from '@/components/common'
import { useScroll } from '@/views/chat/hooks/useScroll'
import { knowledgeBaseEmptyInfo } from '@/utils/functions'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import KbInfo from '@/views/knowledge-base/Header/KbInfo.vue'
import { t } from '@/locales'

const props = defineProps<Props>()
const router = useRouter()
const kbStore = useKbStore()
const { scrollRef } = useScroll()
const { isMobile } = useBasicLayout()
const mouseEnterKbUuid = ref<string>('')
const showModal = ref<boolean>(false)
const tmpKb = ref<KnowledgeBase.Info>(knowledgeBaseEmptyInfo())

interface Props {
  list: KnowledgeBase.Info[]
  activeKbUuid: string
}
async function handleSelect({ uuid }: KnowledgeBase.Info) {
  if (props.activeKbUuid === uuid)
    return
  kbStore.setActive(uuid)
  router.replace({ name: 'QADetail', params: { kbUuid: uuid } })
}
function handleMouseEnter({ uuid }: KnowledgeBase.Info) {
  mouseEnterKbUuid.value = uuid
}
function handleMouseLeave() {
  mouseEnterKbUuid.value = ''
}
function showKb(item: KnowledgeBase.Info) {
  showModal.value = true
  tmpKb.value = item
}
</script>

<template>
  <div ref="scrollRef" class="px-4 h-full overflow-y-auto">
    <!-- 拉取期间显示加载态，不闪"暂无数据" -->
    <div v-if="kbStore.loaddingKbList && !list.length" class="flex justify-center py-8">
      <NSpin size="small" />
    </div>
    <template v-else-if="!list.length">
      <div class="resource-list-empty flex flex-col items-center mt-4 text-center">
        <SvgIcon icon="ri:inbox-line" class="mb-2 text-3xl" />
        <span>{{ t('common.noData') }}</span>
      </div>
    </template>
    <template v-else>
      <div class="flex flex-col gap-2 text-sm">
        <button
          v-for="item of list" :key="item.uuid"
          type="button"
          class="resource-list-item relative flex items-center gap-3 px-3 py-3 break-all border rounded-md cursor-pointer group"
          :class="{ 'resource-list-item--active': item.uuid === activeKbUuid, 'pr-14': item.uuid === activeKbUuid }"
          :aria-current="item.uuid === activeKbUuid ? 'page' : undefined"
          @click="handleSelect(item)" @mouseenter="handleMouseEnter(item)" @mouseleave="handleMouseLeave"
        >
          <span>
            <NIcon v-if="item.ownerType === 'COMPANY'" :component="Building24Regular" />
            <NIcon v-else-if="item.ownerType === 'TEAM'" :component="PeopleTeam24Regular" />
            <NIcon v-else :component="Person24Regular" />
          </span>
          <div class="relative flex-1 overflow-hidden break-all text-ellipsis whitespace-nowrap">
            <span>{{ item.title }}</span>
          </div>
          <div class="absolute z-10 flex visible right-2">
            <NButton
              v-show="mouseEnterKbUuid === item.uuid || isMobile" text size="small"
              :aria-label="t('common.view')" :title="t('common.view')"
              @click.stop="showKb(item)"
            >
              <SvgIcon icon="carbon:information" />
            </NButton>
          </div>
        </button>
      </div>
    </template>
  </div>

  <KbInfo :show-modal="showModal" :knowledge-base="tmpKb" @show-modal="showModal = $event" />
</template>

<style scoped>
.resource-list-item {
  width: 100%;
  color: inherit;
  background: transparent;
  font: inherit;
  text-align: left;
}
</style>
