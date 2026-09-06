const AVATAR_POOL_SIZE = 13
const AVATAR_API_VERSION = 'avatar-pool-2'

function getStableAvatarIndex(seed = ''): number {
  let hash = 0
  for (const character of seed || 'zhimesh')
    hash = (hash * 31 + character.charCodeAt(0)) | 0

  return ((hash % AVATAR_POOL_SIZE) + AVATAR_POOL_SIZE) % AVATAR_POOL_SIZE + 1
}

export function getAvatarPoolUrl(seed = ''): string {
  const index = getStableAvatarIndex(seed)
  return `/avatars/users/avatar-${String(index).padStart(2, '0')}.png`
}

function withAvatarVersion(url: string): string {
  if (url.includes('avatarVersion='))
    return url.replace(/avatarVersion=[^&]*/, `avatarVersion=${AVATAR_API_VERSION}`)

  return `${url}${url.includes('?') ? '&' : '?'}avatarVersion=${AVATAR_API_VERSION}`
}

/**
 * Prefer the path persisted in the database. Legacy API URLs are retained only
 * as a compatibility bridge and versioned so old generated-cat responses can
 * never be reused from the browser cache.
 */
export function resolveUserAvatarUrl(avatar?: string, uuid = '', fallbackSeed = ''): string {
  const persistedAvatar = avatar?.trim() || ''
  if (persistedAvatar && !persistedAvatar.includes('/api/user/avatar/'))
    return persistedAvatar

  if (uuid.trim())
    return `/api/user/avatar/${encodeURIComponent(uuid.trim())}?avatarVersion=${AVATAR_API_VERSION}`

  if (persistedAvatar)
    return withAvatarVersion(persistedAvatar)

  return getAvatarPoolUrl(fallbackSeed || uuid)
}

export function getUserAvatarUrl(uuid = ''): string {
  return resolveUserAvatarUrl(undefined, uuid, uuid)
}
