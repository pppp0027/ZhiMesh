<script setup lang='ts'>
import { reactive, ref, watch } from 'vue'
import { NButton, NInput, NModal, useMessage } from 'naive-ui'
import { t } from '@/locales'
import api from '@/api'

interface Props {
  show: boolean
  team?: Team.Info | null
}
interface Emit {
  (ev: 'update:show', value: boolean): void
  (ev: 'saved'): void
}
const props = withDefaults(defineProps<Props>(), {
  show: false,
  team: null,
})
const emit = defineEmits<Emit>()
const ms = useMessage()

const innerShow = ref(false)
const submitting = ref(false)
const form = reactive<Team.EditReq>({ name: '', remark: '' })

watch(() => props.show, (val) => {
  innerShow.value = val
  if (val) {
    form.id = props.team?.id
    form.uuid = props.team?.uuid
    form.name = props.team?.name || ''
    form.remark = props.team?.remark || ''
  }
})

watch(() => innerShow.value, (val) => {
  if (!val)
    emit('update:show', false)
})

async function submit() {
  if (!form.name.trim()) {
    ms.warning(t('team.nameRequired'))
    return
  }
  try {
    submitting.value = true
    await api.teamSaveOrUpdate<Team.Info>({ ...form, name: form.name.trim() })
    ms.success(t('common.saveSuccessTip'))
    innerShow.value = false
    emit('saved')
  } catch (error: any) {
    console.error('save team failed', error)
    ms.error(error?.message || t('common.wrong'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <NModal
    v-model:show="innerShow" :title="form.id ? t('team.editTeam') : t('team.createTeam')"
    style="width: 90%; max-width: 480px;" preset="card"
  >
    <div class="flex flex-col space-y-3">
      <div class="space-y-1">
        <div>{{ t('team.teamName') }}<span class="text-red-400"> *</span></div>
        <NInput v-model:value="form.name" maxlength="100" show-count />
      </div>
      <div class="space-y-1">
        <div>{{ t('team.teamRemark') }}</div>
        <NInput
          v-model:value="form.remark" type="textarea" maxlength="500" show-count
          :autosize="{ minRows: 2, maxRows: 6 }"
        />
      </div>
    </div>
    <template #footer>
      <div class="flex space-x-2 justify-end">
        <NButton type="primary" size="small" :disabled="submitting" @click="submit">
          {{ t('common.confirm') }}
        </NButton>
        <NButton size="small" :disabled="submitting" @click="innerShow = false">
          {{ t('common.cancel') }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>
