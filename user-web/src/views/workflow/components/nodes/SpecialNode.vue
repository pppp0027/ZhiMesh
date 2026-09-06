<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'

defineProps<NodeProps>()
</script>

<template>
  <div>
    <div>{{ data.label }}</div>
    <div style="position: relative; padding: 10px">
      <Handle type="source" :position="Position.Right" />
      <Handle type="target" :position="Position.Left" />
    </div>
  </div>
</template>
