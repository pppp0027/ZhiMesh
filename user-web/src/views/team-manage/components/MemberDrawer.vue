<script setup lang='ts'>
import type { DataTableColumns } from 'naive-ui'
import { computed, h, ref, watch } from 'vue'
import { NButton, NDataTable, NDrawer, NDrawerContent, NInput, NSelect, NTag, useDialog, useMessage } from 'naive-ui'
import { useUserStore } from '@/store'
import { t } from '@/locales'
import api from '@/api'
import { openDeleteDialog } from '@/utils/dialog'

interface Props {
  show: boolean
  team: Team.Info | null
}
interface Emit {
  (ev: 'update:show', value: boolean): void
  (ev: 'changed'): void
}
const props = withDefaults(defineProps<Props>(), {
  show: false,
  team: null,
})
const emit = defineEmits<Emit>()
const ms = useMessage()
const dialog = useDialog()
const userStore = useUserStore()

const innerShow = ref(false)
const loading = ref(false)
const inviteEmail = ref('')
const inviteRole = ref<Team.TeamRole>('CONTRIBUTOR')
const inviteSubmitting = ref(false)
const members = ref<Team.MemberInfo[]>([])

const isOwner = computed(() => props.team?.myRole === 'OWNER')
// The member roster carries user uuids; the session profile only has uuid.
const myUserUuid = computed(() => userStore.userInfo?.uuid || '')

const roleOptions = computed(() => [
  { label: t('team.roleOwner'), value: 'OWNER' },
  { label: t('team.roleContributor'), value: 'CONTRIBUTOR' },
  { label: t('team.roleReader'), value: 'READER' },
])

watch(() => props.show, (val) => {
  innerShow.value = val
  if (val)
    loadMembers()
})

watch(() => innerShow.value, (val) => {
  if (!val)
    emit('update:show', false)
})

async function loadMembers() {
  if (!props.team)
    return
  loading.value = true
  try {
    const resp = await api.teamMemberList<Team.MemberInfo[]>(props.team.uuid)
    members.value = resp.data || []
  } catch (error: any) {
    console.error('load team members failed', error)
    ms.error(error?.message || t('common.wrong'))
  } finally {
    loading.value = false
  }
}

async function invite() {
  if (!props.team)
    return
  const email = inviteEmail.value.trim()
  if (!email) {
    ms.warning(t('team.inviteEmailPlaceholder'))
    return
  }
  try {
    inviteSubmitting.value = true
    await api.teamMemberAdd<Team.MemberInfo>({
      teamUuid: props.team.uuid,
      email,
      role: inviteRole.value,
    })
    ms.success(t('common.succeed'))
    inviteEmail.value = ''
    await loadMembers()
    emit('changed')
  } catch (error: any) {
    ms.error(error?.message || t('common.wrong'))
  } finally {
    inviteSubmitting.value = false
  }
}

function onRoleChange(member: Team.MemberInfo, role: Team.TeamRole) {
  if (!props.team)
    return
  api.teamMemberUpdateRole({
    teamUuid: props.team.uuid,
    userId: member.userId,
    role,
  }).then(() => {
    member.role = role
    ms.success(t('common.saveSuccessTip'))
    emit('changed')
  }).catch((error: any) => {
    console.error('update member role failed', error)
    ms.error(error?.message || t('common.wrong'))
  })
}

function confirmRemove(member: Team.MemberInfo) {
  if (!props.team)
    return
  openDeleteDialog(dialog, {
    title: t('team.removeMember'),
    content: t('team.removeMemberConfirm', { name: member.name || member.email }),
    positiveText: t('common.confirm'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.teamMemberRemove(props.team!.uuid, member.userId)
        ms.success(t('common.deleteSuccess'))
        await loadMembers()
        emit('changed')
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

function confirmLeave() {
  if (!props.team)
    return
  openDeleteDialog(dialog, {
    title: t('team.leaveTeam'),
    content: t('team.leaveTeamConfirm', { name: props.team.name }),
    positiveText: t('common.confirm'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.teamLeave(props.team!.uuid)
        ms.success(t('common.succeed'))
        innerShow.value = false
        emit('changed')
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

const columns = computed<DataTableColumns<Team.MemberInfo>>(() => [
  {
    title: t('common.name'),
    key: 'name',
    width: 110,
    render: row => row.name || row.email?.split('@')[0] || '-',
  },
  {
    title: t('team.memberEmail'),
    key: 'email',
    ellipsis: { tooltip: true },
  },
  {
    title: t('team.memberRole'),
    key: 'role',
    width: 130,
    render: row => isOwner.value
      ? h(NSelect, {
          size: 'small',
          value: row.role,
          options: roleOptions.value,
          onUpdateValue: (val: Team.TeamRole) => onRoleChange(row, val),
        })
      : h(NTag, { size: 'small', type: row.role === 'OWNER' ? 'warning' : row.role === 'CONTRIBUTOR' ? 'info' : 'default' }, { default: () => roleLabel(row.role) }),
  },
  {
    title: t('common.action'),
    key: 'actions',
    width: 90,
    align: 'center',
    render: row => row.uuid === myUserUuid.value
      ? h(NButton, { size: 'tiny', tertiary: true, type: 'warning', onClick: confirmLeave }, { default: () => t('team.leaveTeam') })
      : isOwner.value
        ? h(NButton, { size: 'tiny', tertiary: true, type: 'error', onClick: () => confirmRemove(row) }, { default: () => t('team.removeMember') })
        : '-',
  },
])

function roleLabel(role: Team.TeamRole) {
  return roleOptions.value.find(item => item.value === role)?.label || role
}
</script>

<template>
  <NDrawer v-model:show="innerShow" :width="480" placement="right">
    <NDrawerContent :title="`${t('team.memberManage')} · ${team?.name || ''}`" closable>
      <div class="flex flex-col gap-3">
        <div v-if="isOwner" class="member-invite-row">
          <div class="text-sm font-medium">
            {{ t('team.inviteMember') }}
          </div>
          <div class="flex gap-2">
            <NInput
              v-model:value="inviteEmail" size="small" :placeholder="t('team.inviteEmailPlaceholder')"
              @keyup.enter="invite"
            />
            <NSelect v-model:value="inviteRole" size="small" :options="roleOptions.filter(item => item.value !== 'OWNER')" style="width: 130px" />
            <NButton size="small" type="primary" :loading="inviteSubmitting" :disabled="inviteSubmitting" @click="invite">
              {{ t('team.invite') }}
            </NButton>
          </div>
          <div class="text-xs opacity-60">
            {{ t('team.inviteRoleHint') }}
          </div>
        </div>
        <NDataTable
          size="small" :loading="loading" :columns="columns" :data="members" :bordered="true"
          :single-line="false" :max-height="undefined"
        />
      </div>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.member-invite-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--zhimesh-border-subtle, #efeff5);
  border-radius: 8px;
}
</style>
