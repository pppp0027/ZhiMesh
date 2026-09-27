<script setup lang='ts'>
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NDivider, NList, NListItem, NModal, NScrollbar, NSpin, NTabPane, NTabs, NTag, NThing, useMessage } from 'naive-ui'
import { useAuthStore, useChatStore } from '@/store'
import { emptyCharacter } from '@/utils/functions'
import EditConvDetail from '@/views/chat/components/Header/EditConvDetail.vue'
import api from '@/api'
import { t } from '@/locales'

const emit = defineEmits<{
  (event: 'characterCreated', character: Chat.Character): void
}>()

const typeOrder = [
  'technology', 'creative', 'education', 'business', 'professional',
  'design', 'marketing', 'service', 'administration', 'utility', 'other',
]

const typeLabelMap = computed<Record<string, string>>(() => ({
  technology: t('chat.presetTypeTechnology'),
  creative: t('chat.presetTypeCreative'),
  education: t('chat.presetTypeEducation'),
  business: t('chat.presetTypeBusiness'),
  professional: t('chat.presetTypeProfessional'),
  design: t('chat.presetTypeDesign'),
  marketing: t('chat.presetTypeMarketing'),
  service: t('chat.presetTypeService'),
  administration: t('chat.presetTypeAdministration'),
  utility: t('chat.presetTypeUtility'),
  other: t('chat.presetTypeOther'),
}))

const authStore = useAuthStore()
const authStoreRef = ref<AuthState>(authStore)
const savingUuids = ref<Set<string>>(new Set())
const expandedUuids = ref<Set<string>>(new Set())
const loadingPresetCharacters = ref<boolean>(false)
const loadingRels = ref<boolean>(false)
const tmpCharacter = ref<Chat.Character>(emptyCharacter())
const showModal = ref<boolean>(false)
const chatStore = useChatStore()
const ms = useMessage()
const activeTab = ref<'presetCharacter' | 'newCharacter'>('presetCharacter')
const creatorVersion = ref(0)

const groupedPresets = computed(() => {
  const groups: Record<string, Chat.CharacterPreset[]> = {}
  for (const preset of chatStore.presetCharacters) {
    const type = preset.type || 'other'
    if (!groups[type])
      groups[type] = []
    groups[type].push(preset)
  }
  const ordered: [string, Chat.CharacterPreset[]][] = []
  for (const type of typeOrder) {
    if (groups[type])
      ordered.push([type, groups[type]])
  }
  return ordered
})

async function searchPresetCharacters() {
  if (loadingPresetCharacters.value)
    return

  loadingPresetCharacters.value = true
  try {
    const { success, data: characters } = await api.searchPresetCharacters<PageResponse>()
    if (success)
      chatStore.setPresetCharacters(characters.records)
  } finally {
    loadingPresetCharacters.value = false
  }
}

async function searchPresetCharacterRel() {
  if (loadingRels.value)
    return

  loadingRels.value = true
  try {
    const { success, data: rels } = await api.listCharacterPresetRels<Chat.CharacterToPresetRel[]>()
    if (success)
      chatStore.setUsedPresetCharacter(rels || [])
  } finally {
    loadingRels.value = false
  }
}

function handleSubmitted(_show: boolean, character?: Chat.Character, created = false) {
  showModal.value = false
  if (created && character)
    emit('characterCreated', character)
}

async function handleUsePresetCharacter(presetCharacter: Chat.CharacterPreset) {
  if (savingUuids.value.has(presetCharacter.uuid))
    return

  savingUuids.value.add(presetCharacter.uuid)
  try {
    const { data: newCharacter } = await api.characterAddByPreset<Chat.Character>({ presetCharacterUuid: presetCharacter.uuid })
    chatStore.addCharacterAndActive(newCharacter)
    chatStore.markPresetCharacterUsed(presetCharacter.uuid)

    ms.success(t('chat.copySuccess'), { duration: 2000 })

    showModal.value = false
    emit('characterCreated', newCharacter)
  } catch (error: any) {
    console.error('addCharacter error', error)
    if (error.message) {
      ms.error(error.message, {
        duration: 2000,
      })
    }
  } finally {
    savingUuids.value.delete(presetCharacter.uuid)
  }

  await searchPresetCharacterRel()
}

watch(
  () => authStoreRef.value.token,
  async (newVal) => {
    if (newVal) {
      await searchPresetCharacters()
      await searchPresetCharacterRel()
    }
  },
)

onMounted(async () => {
  if (authStoreRef.value.token) {
    await searchPresetCharacters()
    await searchPresetCharacterRel()
  }
})

function openModal(tab: 'presetCharacter' | 'newCharacter' = 'presetCharacter') {
  activeTab.value = tab
  creatorVersion.value += 1
  showModal.value = true
}

function toggleExpand(uuid: string) {
  if (expandedUuids.value.has(uuid))
    expandedUuids.value.delete(uuid)
  else
    expandedUuids.value.add(uuid)
}

function presetMcpCount(preset: Chat.CharacterPreset) {
  return String(preset.mcpIds || '').split(',').filter(Boolean).length
}
function hasPresetSystemKnowledge(preset: Chat.CharacterPreset) {
  return Boolean(preset.systemKnowledgeEnabled)
}
defineExpose({ openModal })
</script>

<template>
  <NModal v-model:show="showModal" class="character-creator-modal" preset="card">
    <div class="creator-heading">
      <div class="creator-kicker">{{ t('chat.characterCreatorKicker') }}</div>
      <h2>{{ t('chat.characterCreatorTitle') }}</h2>
    </div>
    <NTabs v-model:value="activeTab" type="line" justify-content="space-evenly" animated>
      <NTabPane class="creator-tab-pane" name="presetCharacter" :tab="t('chat.presetRole')">
        <div class="creator-tab-hint">{{ t('chat.presetRoleHint') }}</div>
        <NScrollbar class="creator-preset-scroll">
          <div v-if="loadingPresetCharacters" class="creator-preset-status">
            <NSpin size="medium" />
          </div>
          <div v-else-if="!groupedPresets.length" class="creator-preset-status is-empty">
            {{ t('common.noData') }}
          </div>
          <template v-for="[type, presets] in groupedPresets" :key="type">
            <NDivider title-placement="left" style="margin: 8px 0 4px;">
              {{ typeLabelMap[type] || type }}
            </NDivider>
            <NList hoverable bordered>
              <NListItem v-for="presetCharacter in presets" :key="presetCharacter.id" style="cursor: pointer;" @click="toggleExpand(presetCharacter.uuid)">
                <NThing content-style="margin-top: 6px;">
                  <template #header>
                    {{ presetCharacter.title }}
                    <NTag v-if="presetCharacter.isSystem" size="tiny" type="success" style="margin-left: 6px; font-size: 11px;">
                      {{ t('chat.systemAgent') }}
                    </NTag>
                    <NTag v-if="presetCharacter.used" size="tiny" type="success" style="margin-left: 6px; font-size: 11px;">
                      {{ t('chat.used') }}
                    </NTag>
                    <NTag v-if="presetMcpCount(presetCharacter)" size="tiny" type="warning" style="margin-left: 6px; font-size: 11px;">
                      MCP {{ presetMcpCount(presetCharacter) }}
                    </NTag>
                    <NTag v-if="hasPresetSystemKnowledge(presetCharacter)" size="tiny" type="success" style="margin-left: 6px; font-size: 11px;">
                      {{ t('chat.systemKnowledgeConnected') }}
                    </NTag>
                  </template>
                  {{ presetCharacter.remark }}
                  <div v-if="expandedUuids.has(presetCharacter.uuid)" @click.stop style="cursor: default;">
                    <NDivider title-placement="left" style="margin: 8px 0; font-size: 12px;">
                      {{ t('chat.roleSetting') }}
                    </NDivider>
                    <div style="max-height: 200px; overflow-y: auto; white-space: pre-wrap; font-size: 13px;">
                      {{ presetCharacter.aiSystemMessage }}
                    </div>
                    <div class="agent-capability-summary">
                      <div>
                        <strong>{{ t('chat.agentKnowledge') }}</strong>
                        <span>{{ hasPresetSystemKnowledge(presetCharacter) ? t('chat.systemKnowledgeConnected') : t('chat.notConfigured') }}</span>
                      </div>
                      <div>
                        <strong>{{ t('chat.agentTools') }}</strong>
                        <span>{{ presetMcpCount(presetCharacter) ? `MCP ${presetMcpCount(presetCharacter)}` : t('chat.notConfigured') }}</span>
                      </div>
                    </div>
                  </div>
                </NThing>
                <template #suffix>
                  <NButton size="small" :loading="savingUuids.has(presetCharacter.uuid)" :disabled="savingUuids.has(presetCharacter.uuid)" @click.stop="handleUsePresetCharacter(presetCharacter)">
                    {{ savingUuids.has(presetCharacter.uuid) ? t('chat.copying') : t('chat.use') }}
                  </NButton>
                </template>
              </NListItem>
            </NList>
          </template>
        </NScrollbar>
      </NTabPane>
      <NTabPane class="creator-tab-pane" name="newCharacter" :tab="t('chat.newCharacter')">
        <div class="creator-tab-hint">{{ t('chat.customRoleHint') }}</div>
        <EditConvDetail :key="creatorVersion" :character="tmpCharacter" compact @submitted="handleSubmitted" />
      </NTabPane>
    </NTabs>
  </NModal>
</template>

<style scoped lang="less">
.creator-preset-status {
  display: grid;
  min-height: 220px;
  place-items: center;
}

.creator-preset-status.is-empty {
  color: var(--zhimesh-text-muted);
  font-size: 13px;
}

.agent-capability-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;
}

.agent-capability-summary > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 10px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 11px;
  background: var(--zhimesh-glass-soft);
  font-size: 12px;
}

.agent-capability-summary span {
  color: var(--zhimesh-text-muted);
}

:global(.character-creator-modal) {
  width: min(600px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
}

:global(.character-creator-modal .n-card__content) {
  overflow: hidden;
}

/* Desktop: bound the preset list so NScrollbar scrolls instead of being clipped by the card. */
:global(.character-creator-modal .creator-preset-scroll) {
  max-height: min(calc(100vh - 320px), 600px);
}

@media (max-width: 767px) {
  :global(.character-creator-modal) {
    width: min(92vw, 420px) !important;
    height: clamp(520px, 78dvh, 660px);
    max-width: calc(100vw - 24px) !important;
    max-height: calc(100dvh - 32px) !important;
    margin: 16px auto !important;
  }

  :global(.character-creator-modal .n-card__content) {
    display: flex;
    min-height: 0;
    flex: 1;
    flex-direction: column;
    max-height: none;
    overflow: hidden;
  }

  :global(.character-creator-modal .n-tabs) {
    display: flex;
    min-height: 0;
    flex: 1;
    flex-direction: column;
  }

  :global(.character-creator-modal .n-tabs-nav) {
    flex: none;
  }

  :global(.character-creator-modal .n-tabs-pane-wrapper) {
    min-height: 0;
    flex: 1;
  }

  :global(.character-creator-modal .n-tab-pane),
  :global(.character-creator-modal .creator-tab-pane) {
    display: flex;
    height: 100%;
    min-height: 0;
    flex-direction: column;
  }

  :global(.character-creator-modal .creator-tab-hint) {
    flex: none;
  }

  :global(.character-creator-modal .creator-preset-scroll) {
    min-height: 0;
    flex: 1;
    max-height: none !important;
  }

  :global(.character-creator-modal .character-settings-form) {
    display: flex;
    min-height: 0;
    flex: 1;
    flex-direction: column;
  }

  :global(.character-creator-modal .character-settings-form .settings-scroll) {
    min-height: 0;
    flex: 1;
    max-height: none;
  }

  :global(.character-creator-modal .character-settings-form .settings-footer) {
    flex: none;
  }
}
</style>
