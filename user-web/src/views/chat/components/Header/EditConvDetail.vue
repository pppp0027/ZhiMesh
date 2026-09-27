<script lang="ts" setup>
import { ref, watch } from 'vue'
import { NButton, NCheckbox, NCheckboxGroup, NFlex, NIcon, NInput, NModal, NRadio, NRadioGroup, NTag, NTooltip, useDialog, useMessage } from 'naive-ui'
import { QuestionCircle16Regular } from '@vicons/fluent'
import ConvKnowledgeSelector from '@/views/chat/ConvKnowledgeSelector.vue'
import { useChatStore, useMcpStore } from '@/store'
import { router } from '@/router'
import { emptyCharacter } from '@/utils/functions'
import api from '@/api'
import { t } from '@/locales'
import { openDeleteDialog } from '@/utils/dialog'
import { CHAT_MESSAGE_CONTENT_TYPE } from '@/utils/constant'
interface Props {
  character: Chat.Character
  compact?: boolean
}
interface Emit {
  (ev: 'submitted', show: boolean, character?: Chat.Character, created?: boolean): void
}
const props = withDefaults(defineProps<Props>(), {})
const emit = defineEmits<Emit>()
const chatStore = useChatStore()
const mcpStore = useMcpStore()
const dialog = useDialog()
// isAgentic 无 UI 开关：角色对话本身即 Agentic 能力（迁移 042 后默认开启），
// 表单始终随保存发送 true，防止编辑把已有角色意外翻回关闭
const tmpCharacter = ref<Chat.Character>({ ...emptyCharacter(), isAgentic: true })
const ms = useMessage()
const submitting = ref<boolean>(false)
const knowledgeModalShow = ref<boolean>(false)
const compact = props.compact === true

function initEditCharacter(item: Chat.Character) {
  Object.assign(tmpCharacter.value, item)
  // 语音服务已从后端下线，角色配置统一使用文本回复。
  tmpCharacter.value.answerContentType = CHAT_MESSAGE_CONTENT_TYPE.text
  tmpCharacter.value.isAutoplayAnswer = false
  // 无开关 UI：回显时恒为 true（角色对话即 Agentic），防止表单复用残留关闭态
  tmpCharacter.value.isAgentic = true
  tmpCharacter.value.kbIds = []
  tmpCharacter.value.characterKnowledgeList = []
  tmpCharacter.value.kbIds.push(...item.kbIds)
  tmpCharacter.value.characterKnowledgeList.push(...item.characterKnowledgeList)
  // tool_policy 用户端不可编辑（2026-09-23 产品决策）：接口回显数据经上方 Object.assign 会带回该键，
  // 此处剥离以保证保存载荷不再携带 toolPolicy（策略仅经预设实例化/管理端下发）
  delete (tmpCharacter.value as { toolPolicy?: string | null }).toolPolicy
}
async function handleEdit(event?: KeyboardEvent) {
  event?.stopPropagation()
  if (submitting.value) {
    ms.warning(t('chat.submitting'), {
      duration: 2000,
    })
    return
  }
  if (!tmpCharacter.value.title.trim()) {
    ms.error(t('chat.titleRequired'), {
      duration: 2000,
    })
    return
  }
  if (!tmpCharacter.value.aiSystemMessage?.trim()) {
    ms.error(t('chat.roleSettingRequired'), {
      duration: 2000,
    })
    return
  }
  try {
    submitting.value = true
    // 防止旧角色保存时把历史语音配置重新提交给已禁用的语音服务。
    tmpCharacter.value.answerContentType = CHAT_MESSAGE_CONTENT_TYPE.text
    tmpCharacter.value.isAutoplayAnswer = false
    if (!tmpCharacter.value.uuid) {
      const { data: newCharacter } = await api.characterAdd<Chat.Character>(tmpCharacter.value)
      chatStore.addCharacterAndActive(newCharacter)
      emit('submitted', false, newCharacter, true)
    } else {
      await api.characterEdit(tmpCharacter.value.uuid, tmpCharacter.value)
      chatStore.updateCharacter(tmpCharacter.value.uuid, tmpCharacter.value)
      emit('submitted', false, tmpCharacter.value, false)
    }
  } catch (error: any) {
    console.error('handleEdit error', error)
    if (error.message) {
      ms.error(error.message, {
        duration: 2000,
      })
    }
  } finally {
    submitting.value = false
  }
}

function handleRemoveKnowledge(knowledgeId: string) {
  const index = tmpCharacter.value.characterKnowledgeList.findIndex(kb => kb.id === knowledgeId)
  if (index !== -1) {
    if (tmpCharacter.value.characterKnowledgeList[index].isSystem)
      return
    tmpCharacter.value.characterKnowledgeList.splice(index, 1)
    const idIndex = tmpCharacter.value.kbIds.findIndex(id => id === knowledgeId)
    if (idIndex !== -1)
      tmpCharacter.value.kbIds.splice(idIndex, 1)
  }
}

function handleKnowledgeSelectedChanged(knowledgeIds: string[], knowledgeList: Chat.CharacterKnowledge[]) {
  const systemKnowledge = tmpCharacter.value.characterKnowledgeList.filter(item => item.isSystem)
  const systemIds = systemKnowledge.map(item => String(item.id))
  tmpCharacter.value.kbIds = Array.from(new Set([...knowledgeIds, ...systemIds]))
  tmpCharacter.value.characterKnowledgeList = [...systemKnowledge, ...knowledgeList]
}

function confirmDeleteCharacter() {
  openDeleteDialog(dialog, {
    title: t('common.delete'),
    content: t('chat.editCharacterDetail.confirmDeleteRole', { title: tmpCharacter.value.title }),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      await handleDelete(tmpCharacter.value.uuid)
    },
  })
}

async function handleDelete(uuid: string) {
  if (submitting.value)
    return
  submitting.value = true
  try {
    await api.characterDel(uuid)
    chatStore.deleteCharacter(uuid)
    emit('submitted', false)
  } catch (error: any) {
    console.error('delete character failed', error)
    ms.error(error?.message || t('common.deleteFailed'))
  } finally {
    submitting.value = false
  }
}

function gotoMcp() {
  router.push({ name: 'Mcp' })
  emit('submitted', false)
}

watch(() => props.character.uuid, (val) => {
  if (val)
    initEditCharacter(props.character)
}, { immediate: true })
</script>

<template>
  <div class="character-settings-form" :class="{ 'character-settings-form--compact': compact }">
    <div ref="scrollRef" class="settings-scroll">
      <div class="settings-body">
        <div class="settings-field">
          <div class="settings-label">
            {{ t('chat.editCharacterDetail.nameLabel') }}
          </div>
          <NInput v-model:value="tmpCharacter.title" type="text" size="large" :placeholder="t('chat.editCharacterDetail.namePlaceholder')" />
        </div>
        <div class="settings-field">
          <div class="settings-label">
            {{ t('chat.editCharacterDetail.remarkLabel') }}
          </div>
          <NInput
            v-model:value="tmpCharacter.remark" type="textarea" :placeholder="t('chat.editCharacterDetail.remarkPlaceholder')"
            :autosize="{ minRows: 1, maxRows: 10 }"
          />
          <div class="settings-field-hint">{{ t('chat.editCharacterDetail.remarkHint') }}</div>
        </div>
        <div class="settings-field">
          <div class="settings-label">
            {{ t('chat.editCharacterDetail.roleSettingLabel') }}
            <span class="required-mark">*</span>
          </div>
          <NInput
            v-model:value="tmpCharacter.aiSystemMessage" type="textarea" :placeholder="t('chat.editCharacterDetail.roleSettingPlaceholder')"
            :autosize="{ minRows: 1, maxRows: 10 }"
          />
          <div class="settings-field-hint">{{ t('chat.editCharacterDetail.roleSettingHint') }}</div>
        </div>
        <div class="settings-section">
          <div class="settings-section-title">
            {{ t('chat.editCharacterDetail.deepThinking') }}
            <NTooltip trigger="hover">
              <template #trigger>
                <NIcon style="margin-top: 0.2rem">
                  <QuestionCircle16Regular />
                </NIcon>
              </template>
              <span>{{ t('chat.editCharacterDetail.deepThinkingTip1') }}<br></span>
              <span>{{ t('chat.editCharacterDetail.deepThinkingTip2') }}</span>
            </NTooltip>
          </div>
          <NRadioGroup
            :value="tmpCharacter.isEnableThinking" name="isEnableThinkingRadio" class="settings-choice-row"
            size="small" @update:value="(checked) => tmpCharacter.isEnableThinking = checked"
          >
            <NRadio :value="false">
              {{ t('chat.editCharacterDetail.off') }}
            </NRadio>
            <NRadio :value="true">
              {{ t('chat.editCharacterDetail.on') }}
            </NRadio>
          </NRadioGroup>
        </div>
        <div class="settings-section">
          <div class="settings-section-title">
            <span>{{ t('chat.editCharacterDetail.knowledgeBase') }}</span>
            <NButton type="primary" size="tiny" text tag="a" @click="knowledgeModalShow = true">
              {{ t('chat.editCharacterDetail.addMoreKnowledge') }}
            </NButton>
          </div>
          <div>
            <div v-if="tmpCharacter.characterKnowledgeList.length === 0" class="pl-6">
              {{ t('common.noData') }}
            </div>
            <NTag
              v-for="characterKnowledge in tmpCharacter.characterKnowledgeList" :key="characterKnowledge.uuid" :closable="!characterKnowledge.isSystem" class="mr-2"
              @close="handleRemoveKnowledge(characterKnowledge.id)"
            >
              {{ characterKnowledge.title }}
            </NTag>
          </div>
          <NModal
            v-model:show="knowledgeModalShow" display-directive="show" style="width: min(860px, 92vw);"
            preset="card" :title="t('chat.configCharacterKnowledge')"
          >
            <ConvKnowledgeSelector
              :tmp-save="true" :character="tmpCharacter"
              @selected-changed="handleKnowledgeSelectedChanged"
            />
          </NModal>
        </div>
        <div class="settings-section">
          <div class="settings-section-title">
            {{ t('chat.editCharacterDetail.mcpServices') }}
            <NTooltip trigger="hover">
              <template #trigger>
                <NIcon style="margin-top: 0.2rem">
                  <QuestionCircle16Regular />
                </NIcon>
              </template>
              <span>{{ t('chat.editCharacterDetail.mcpTip') }}</span>
            </NTooltip>
            <NButton type="primary" size="tiny" text tag="a" @click="gotoMcp">
              {{ t('chat.editCharacterDetail.goEnableMoreTools') }}
            </NButton>
          </div>
          <div v-if="mcpStore.myUserMcpList.length === 0" class="settings-empty">
            {{ t('mcp.emptyMineHint') }}
          </div>
          <NCheckboxGroup v-else v-model:value="tmpCharacter.mcpIds" class="settings-tool-grid">
            <NCheckbox
              v-for="userMcp in mcpStore.myUserMcpList" :key="userMcp.uuid" :value="userMcp.mcpInfo.id"
              :label="userMcp.mcpInfo.title"
            />
          </NCheckboxGroup>
        </div>
      </div>
    </div>
    <NFlex :justify="tmpCharacter.uuid ? 'space-between' : 'flex-end'" class="settings-footer">
      <NButton
        v-if="tmpCharacter.uuid" type="error" text tag="a" :loading="submitting" :disabled="submitting"
        @click="confirmDeleteCharacter"
      >
        {{ t('common.delete') }}
      </NButton>
      <NButton type="primary" :loading="submitting" :disabled="submitting" @click="handleEdit()">
        {{ t('common.save') }}
      </NButton>
    </NFlex>
  </div>
</template>

<style scoped lang="less">
.settings-scroll {
  max-height: min(700px, calc(100vh - 210px));
  overflow-y: auto;
  padding: 2px 8px 8px 2px;
}

.settings-body {
  display: grid;
  gap: 12px;
}

.settings-field,
.settings-section {
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 15px;
  background: var(--zhimesh-glass-soft-alpha);
}

.settings-field {
  padding: 13px 14px 14px;
}

.character-settings-form--compact .settings-scroll {
  max-height: min(470px, calc(100vh - 300px));
}

.settings-section {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 11px;
}

.settings-label,
.settings-section-title {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 700;
}

.required-mark {
  color: var(--zhimesh-danger-text);
  font-size: 15px;
}

.settings-field-hint {
  margin-top: 7px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.settings-label {
  margin-bottom: 8px;
}

.settings-field :deep(.n-input) {
  background: var(--zhimesh-glass-soft-alpha);
}

.settings-choice-row {
  display: flex;
  flex-direction: row;
  gap: 18px;
}

.settings-tool-grid {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.settings-tool-grid :deep(.n-checkbox) {
  margin: 0;
  padding: 10px 11px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 11px;
  background: var(--zhimesh-glass-soft);
}

.settings-tool-grid :deep(.n-checkbox--checked) {
  border-color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.settings-empty {
  padding: 12px;
  border: 1px dashed var(--zhimesh-border);
  border-radius: 11px;
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
  font-size: 12px;
  line-height: 1.55;
}

.settings-footer {
  margin-top: 14px;
  padding-top: 13px;
  border-top: 1px solid var(--zhimesh-border-subtle);
}

:global(.dark) .settings-field,
:global(.dark) .settings-section {
  background: rgba(30, 41, 59, 0.3);
}

:global(.dark) .settings-field :deep(.n-input) {
  background: rgba(15, 23, 42, 0.38);
}
</style>
