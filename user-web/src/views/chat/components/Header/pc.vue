<script lang="ts" setup>
import { SvgIcon } from '@/components/common'
import { t } from '@/locales'
import { emptyCharacter } from '@/utils/functions'

interface Props {
  character: Chat.Character
}
const props = withDefaults(defineProps<Props>(), {
  character: () => emptyCharacter(),
})
</script>

<template>
  <header class="chat-title-header">
    <div class="chat-title-header-inner">
      <span class="chat-title-mark"><SvgIcon icon="ri:robot-2-line" /></span>
      <div class="chat-title-copy">
        <h1>{{ props.character?.title || t('chat.newCharacter') }}</h1>
      </div>
    </div>
  </header>
</template>

<style scoped lang="less">
.chat-title-header {
  position: sticky;
  z-index: 20;
  top: 0;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-strong);
}

.chat-title-header-inner {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: center;
  gap: 9px;
}

.chat-title-mark {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: var(--zhimesh-control-primary);
  font-size: 16px;
  box-shadow: none;
}

.chat-title-copy {
  display: flex;
  align-items: center;
}

.chat-title-copy h1 {
  margin: 0;
  color: var(--zhimesh-text);
  font-size: 18px;
  font-weight: 760;
  letter-spacing: -0.025em;
}
</style>
