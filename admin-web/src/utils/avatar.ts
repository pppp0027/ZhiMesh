export function getAvatarPoolUrl(seed = ''): string {
  let hash = 0
  for (const character of seed || 'zhimesh')
    hash = (hash * 31 + character.charCodeAt(0)) | 0
  const index = ((hash % 13) + 13) % 13 + 1
  return `/avatars/users/avatar-${String(index).padStart(2, '0')}.png`
}

export function resolveUserAvatarUrl(avatar = '', seed = ''): string {
  const persistedAvatar = avatar.trim()
  if (!persistedAvatar)
    return getAvatarPoolUrl(seed)

  if (!persistedAvatar.includes('/api/user/avatar/'))
    return persistedAvatar

  const separator = persistedAvatar.includes('?') ? '&' : '?'
  return persistedAvatar.includes('avatarVersion=')
    ? persistedAvatar.replace(/avatarVersion=[^&]*/, 'avatarVersion=avatar-pool-2')
    : `${persistedAvatar}${separator}avatarVersion=avatar-pool-2`
}
