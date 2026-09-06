<script setup lang="ts">
import { computed, ref, watch } from 'vue'

interface Props {
  name?: string
  label?: string
  host?: string
  iconUrl?: string
  size?: number
}

const props = withDefaults(defineProps<Props>(), {
  name: '',
  label: '',
  host: '',
  iconUrl: '',
  size: 22,
})

const imageFailed = ref(false)

watch(() => [props.iconUrl, props.name, props.label, props.host], () => {
  imageFailed.value = false
})

const identity = computed(() => props.label || props.name || props.host || 'AI')
const accessibleLabel = computed(() => `${identity.value} 平台`)
const initials = computed(() => {
  const words = identity.value.trim().split(/[^\p{L}\p{N}]+/u).filter(Boolean)
  if (words.length > 1)
    return `${words[0][0]}${words[1][0]}`.toUpperCase()
  return (words[0] || 'AI').slice(0, 2).toUpperCase()
})

const palettes = [
  { background: '#e7f0f7', border: '#b7cddd', foreground: '#234a67' },
  { background: '#eeeaf7', border: '#cbc0df', foreground: '#4d3d71' },
  { background: '#e6f2ed', border: '#b6d5c8', foreground: '#285846' },
  { background: '#f5ece4', border: '#dec7b5', foreground: '#6b4429' },
  { background: '#f3e9ee', border: '#d9bdca', foreground: '#703c52' },
  { background: '#ecefed', border: '#c6ceca', foreground: '#3f5148' },
]

const palette = computed(() => {
  const hash = [...identity.value].reduce((value, character) => ((value * 31) + character.codePointAt(0)!) >>> 0, 0)
  return palettes[hash % palettes.length]
})

const avatarStyle = computed(() => ({
  '--platform-avatar-size': `${props.size}px`,
  '--platform-avatar-bg': palette.value.background,
  '--platform-avatar-border': palette.value.border,
  '--platform-avatar-fg': palette.value.foreground,
}))
</script>

<template>
  <span
    class="platform-avatar"
    :style="avatarStyle"
    role="img"
    :aria-label="accessibleLabel"
    :title="host || label || name"
  >
    <img
      v-if="iconUrl && !imageFailed"
      :src="iconUrl"
      alt=""
      loading="lazy"
      @error="imageFailed = true"
    >
    <span v-else aria-hidden="true">{{ initials }}</span>
  </span>
</template>

<style scoped>
.platform-avatar {
  display: inline-flex;
  width: var(--platform-avatar-size);
  height: var(--platform-avatar-size);
  flex: 0 0 var(--platform-avatar-size);
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid var(--platform-avatar-border);
  border-radius: 6px;
  background: var(--platform-avatar-bg);
  color: var(--platform-avatar-fg);
  font-size: calc(var(--platform-avatar-size) * 0.43);
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.02em;
}

.platform-avatar img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  padding: 2px;
  background: #fff;
}
</style>
