import { h } from 'vue'
import { NAvatar } from 'naive-ui'
import { BasicColumn } from '@/components/Table'
import { useI18n } from '@/locales'
import { getAvatarPoolUrl, resolveUserAvatarUrl } from '@/utils/avatar'

export interface UserData {
  id: string
  uuid: string
  name: string
  email: string
  avatar: string
  userStatus: string
  activeTime: string
  createTime: string
  updateTime: string
  isAdmin: boolean
}

export function getColumns(): BasicColumn<UserData>[] {
  const { t } = useI18n()
  return [
    {
      title: 'id',
      key: 'id',
      width: 50,
    },
    {
      title: 'uuid',
      key: 'uuid',
      width: 120,
    },
    {
      title: t('columns.name'),
      key: 'name',
      width: 100,
    },
    {
      title: t('columns.email'),
      key: 'email',
      width: 150,
    },
    {
      title: t('columns.avatar'),
      key: 'avatar',
      width: 70,
      render(row) {
        const seed = row.uuid || row.name
        return h(NAvatar, {
          size: 48,
          round: true,
          objectFit: 'cover',
          src: resolveUserAvatarUrl(row.avatar, seed),
          fallbackSrc: getAvatarPoolUrl(seed),
          'aria-label': `${row.name || '用户'}的头像`,
        })
      },
    },
    {
      title: t('columns.userStatus'),
      key: 'userStatus',
      width: 100,
      render(row) {
        if (row.userStatus === 'NORMAL') {
          return t('columns.statusNormal')
        } else if (row.userStatus === 'WAIT_CONFIRM') {
          return t('columns.statusPending')
        }
        return t('columns.statusDisabled')
      },
    },
    {
      title: t('columns.activeTime'),
      key: 'activeTime',
      width: 180,
    },
    {
      title: t('columns.createTime'),
      key: 'createTime',
      width: 180,
    },
    {
      title: t('columns.updateTime'),
      key: 'updateTime',
      width: 180,
    },
    {
      title: t('columns.isAdmin'),
      key: 'isAmdin',
      width: 100,
      render(row) {
        return row.isAdmin ? t('common.yes') : t('common.no')
      },
    },
  ]
}
