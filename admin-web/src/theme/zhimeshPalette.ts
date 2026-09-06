export const zhimeshWhite = '#ffffff'

function normalizeHex(color: string) {
  const value = color.replace('#', '')
  return /^[\da-f]{6}$/i.test(value) ? `#${value}` : '#234a7a'
}

function shadeHex(color: string, factor: number) {
  const normalized = normalizeHex(color)
  const channels = [1, 3, 5].map((index) =>
    Math.max(
      0,
      Math.min(255, Math.round(parseInt(normalized.slice(index, index + 2), 16) * factor))
    )
  )
  return `#${channels.map((channel) => channel.toString(16).padStart(2, '0')).join('')}`
}

function luminance(color: string) {
  const normalized = normalizeHex(color)
  const channels = [1, 3, 5].map((index) => {
    const value = parseInt(normalized.slice(index, index + 2), 16) / 255
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
}

function withWhiteAaContrast(color: string) {
  let candidate = normalizeHex(color)
  for (let index = 0; index < 8 && 1.05 / (luminance(candidate) + 0.05) < 4.5; index += 1) {
    candidate = shadeHex(candidate, 0.92)
  }
  return candidate
}

export function createZhiMeshPalette(appTheme: string, isDark: boolean) {
  const accessibleAppTheme = withWhiteAaContrast(appTheme)
  return {
    componentPrimary: isDark ? '#91bad7' : accessibleAppTheme,
    componentPrimaryHover: isDark ? '#b0cfe3' : shadeHex(accessibleAppTheme, 0.86),
    componentPrimaryPressed: isDark ? '#c7ddeb' : shadeHex(accessibleAppTheme, 0.74),
    controlPrimary: isDark ? '#315b86' : accessibleAppTheme,
    controlPrimaryHover: isDark ? '#3d6b98' : shadeHex(accessibleAppTheme, 0.86),
    controlPrimaryPressed: isDark ? '#274d75' : shadeHex(accessibleAppTheme, 0.74),
    infoSolid: isDark ? '#315b86' : '#234a7a',
    infoSolidHover: isDark ? '#3d6b98' : '#1b3c63',
    infoSolidPressed: isDark ? '#274d75' : '#142f4e',
    infoSurface: isDark ? '#223744' : '#e8f0f5',
    infoSurfaceHover: isDark ? '#294553' : '#dce8ef',
    infoText: isDark ? '#d4e7f4' : '#234a7a',
    infoContent: isDark ? '#c1d2dc' : '#294d67',
    infoBorder: isDark ? '#476574' : '#abc2d1',
    dangerText: isDark ? '#ffb3bf' : '#a61b36',
    dangerTextHover: isDark ? '#ffc7d0' : '#89132b',
    dangerTextPressed: isDark ? '#ffd8de' : '#6f0f23',
    dangerSolid: isDark ? '#9f2f46' : '#b4233f',
    dangerSolidHover: isDark ? '#b83a53' : '#941c34',
    dangerSolidPressed: isDark ? '#84253a' : '#741528',
  }
}

export function createZhiMeshDialogOverrides(isDark: boolean) {
  return {
    borderRadius: '14px',
    titleFontSize: '17px',
    titleFontWeight: '700',
    padding: '22px 24px 20px',
    iconSize: '22px',
    iconColorError: isDark ? '#ffb3bf' : '#a61b36',
    contentMargin: '10px 0 20px',
    actionSpace: '10px',
    closeSize: '28px',
    closeIconSize: '18px',
    closeMargin: '12px 14px 0 0',
  }
}
