import type { GlobalThemeOverrides } from 'naive-ui'
import { computed, watch } from 'vue'
import { darkTheme, useOsTheme } from 'naive-ui'
import { useAppStore } from '@/store'

export function useTheme() {
  const appStore = useAppStore()

  const OsTheme = useOsTheme()

  const isDark = computed(() => {
    if (appStore.theme === 'auto')
      return OsTheme.value === 'dark'
    else
      return appStore.theme === 'dark'
  })

  const theme = computed(() => {
    return isDark.value ? darkTheme : undefined
  })

  const themeOverrides = computed<GlobalThemeOverrides>(() => {
    const dark = isDark.value
    const primary = dark ? '#78a6c9' : '#234a7a'
    const primaryHover = dark ? '#91bad7' : '#1b3c63'
    const primaryPressed = dark ? '#a8c9df' : '#142f4e'
    const controlPrimary = dark ? '#315b86' : '#234a7a'
    const controlPrimaryHover = dark ? '#3d6b98' : '#1b3c63'
    const controlPrimaryPressed = dark ? '#274d75' : '#142f4e'
    const onPrimary = '#ffffff'
    const text = dark ? '#e6edf1' : '#172532'
    const textMuted = dark ? '#a7b6c0' : '#5d6c78'
    const infoText = dark ? '#b8d4e6' : '#1f4c71'
    const infoSurface = dark ? '#243d4d' : '#e6eef4'
    const infoSurfaceHover = dark ? '#2b4859' : '#dae6ee'
    const infoBorder = dark ? '#496273' : '#b9cbd8'
    const dangerText = dark ? '#ffb3bf' : '#a61b36'
    const dangerTextHover = dark ? '#ffc7d0' : '#89132b'
    const dangerTextPressed = dark ? '#ffd8de' : '#6f0f23'
    const dangerSolid = dark ? '#9f2f46' : '#b4233f'
    const dangerSolidHover = dark ? '#b83a53' : '#941c34'
    const dangerSolidPressed = dark ? '#84253a' : '#741528'
    return {
      common: {
        primaryColor: primary,
        primaryColorHover: primaryHover,
        primaryColorPressed: primaryPressed,
        primaryColorSuppl: primary,
        infoColor: infoText,
        infoColorHover: dark ? '#d0e2ed' : '#173d5d',
        infoColorPressed: dark ? '#e0edf4' : '#102f49',
        infoColorSuppl: infoText,
        errorColor: dangerText,
        errorColorHover: dangerTextHover,
        errorColorPressed: dangerTextPressed,
        errorColorSuppl: dangerText,
        textColor1: text,
        textColor2: text,
        textColor3: textMuted,
        placeholderColor: textMuted,
        iconColor: textMuted,
        borderRadius: '8px',
        borderColor: dark ? '#314653' : '#d7e0e5',
        cardColor: dark ? '#1d2d39' : '#ffffff',
        modalColor: dark ? '#1d2d39' : '#ffffff',
        popoverColor: dark ? '#223440' : '#ffffff',
      },
      Button: {
        borderRadiusMedium: '8px',
        borderRadiusLarge: '8px',
        colorPrimary: controlPrimary,
        colorHoverPrimary: controlPrimaryHover,
        colorPressedPrimary: controlPrimaryPressed,
        colorFocusPrimary: controlPrimaryHover,
        colorDisabledPrimary: controlPrimary,
        textColorPrimary: onPrimary,
        textColorHoverPrimary: onPrimary,
        textColorPressedPrimary: onPrimary,
        textColorFocusPrimary: onPrimary,
        textColorDisabledPrimary: onPrimary,
        textColorTextPrimary: primary,
        textColorTextHoverPrimary: primaryHover,
        textColorTextPressedPrimary: primaryPressed,
        textColorTextFocusPrimary: primaryHover,
        textColorGhostPrimary: primary,
        textColorGhostHoverPrimary: primaryHover,
        textColorGhostPressedPrimary: primaryPressed,
        textColorGhostFocusPrimary: primaryHover,
        colorInfo: controlPrimary,
        colorHoverInfo: controlPrimaryHover,
        colorPressedInfo: controlPrimaryPressed,
        colorFocusInfo: controlPrimaryHover,
        colorDisabledInfo: controlPrimary,
        textColorInfo: onPrimary,
        textColorHoverInfo: onPrimary,
        textColorPressedInfo: onPrimary,
        textColorFocusInfo: onPrimary,
        textColorDisabledInfo: onPrimary,
        textColorTextInfo: infoText,
        textColorTextHoverInfo: dark ? '#d0e2ed' : '#173d5d',
        textColorTextPressedInfo: dark ? '#e0edf4' : '#102f49',
        textColorGhostInfo: infoText,
        textColorGhostHoverInfo: dark ? '#d0e2ed' : '#173d5d',
        textColorGhostPressedInfo: dark ? '#e0edf4' : '#102f49',
        colorError: dangerSolid,
        colorHoverError: dangerSolidHover,
        colorPressedError: dangerSolidPressed,
        colorFocusError: dangerSolidHover,
        colorDisabledError: dangerSolid,
        textColorError: onPrimary,
        textColorHoverError: onPrimary,
        textColorPressedError: onPrimary,
        textColorFocusError: onPrimary,
        textColorDisabledError: onPrimary,
        textColorTextError: dangerText,
        textColorTextHoverError: dangerTextHover,
        textColorTextPressedError: dangerTextPressed,
        textColorGhostError: dangerText,
        textColorGhostHoverError: dangerTextHover,
        textColorGhostPressedError: dangerTextPressed,
      },
      Dialog: {
        borderRadius: '14px',
        titleFontSize: '17px',
        titleFontWeight: '700',
        padding: '22px 24px 20px',
        iconSize: '22px',
        iconColorError: dangerText,
        contentMargin: '10px 0 20px',
        actionSpace: '10px',
        closeSize: '28px',
        closeIconSize: '18px',
        closeMargin: '12px 14px 0 0',
      },
      Card: {
        borderRadius: '10px',
      },
      Input: {
        borderRadius: '8px',
      },
      Tooltip: {
        color: controlPrimary,
        textColor: onPrimary,
        borderRadius: '8px',
        padding: '7px 10px',
        boxShadow: dark
          ? '0 8px 20px rgba(0, 0, 0, 0.32)'
          : '0 8px 20px rgba(23, 37, 50, 0.18)',
      },
      Menu: {
        itemHeight: '42px',
        itemColorActive: controlPrimary,
        itemColorActiveHover: controlPrimaryHover,
        itemColorActiveCollapsed: controlPrimary,
        itemTextColorActive: onPrimary,
        itemTextColorActiveHover: onPrimary,
        itemTextColorActiveHorizontal: onPrimary,
        itemTextColorActiveHoverHorizontal: onPrimary,
        itemIconColorActive: onPrimary,
        itemIconColorActiveHover: onPrimary,
        itemIconColorActiveHorizontal: onPrimary,
        itemIconColorActiveHoverHorizontal: onPrimary,
        arrowColorActive: onPrimary,
        arrowColorActiveHover: onPrimary,
      },
      Tabs: {
        colorSegment: dark ? '#1f313d' : '#edf2f5',
        tabColorSegment: infoSurface,
        tabTextColorActiveSegment: infoText,
        tabTextColorHoverSegment: infoText,
        tabTextColorActiveLine: primary,
        tabTextColorHoverLine: primaryHover,
        tabTextColorActiveBar: primary,
        tabTextColorHoverBar: primaryHover,
        tabTextColorActiveCard: infoText,
        tabTextColorHoverCard: infoText,
        barColor: primary,
      },
      Tag: {
        textColorChecked: onPrimary,
        colorChecked: controlPrimary,
        colorCheckedHover: controlPrimaryHover,
        colorCheckedPressed: controlPrimaryPressed,
        borderPrimary: `1px solid ${infoBorder}`,
        textColorPrimary: infoText,
        colorPrimary: infoSurface,
        colorBorderedPrimary: infoSurface,
        closeIconColorPrimary: infoText,
        closeIconColorHoverPrimary: infoText,
        closeIconColorPressedPrimary: infoText,
        borderInfo: `1px solid ${infoBorder}`,
        textColorInfo: infoText,
        colorInfo: infoSurface,
        colorBorderedInfo: infoSurface,
        closeIconColorInfo: infoText,
        closeIconColorHoverInfo: infoText,
        closeIconColorPressedInfo: infoText,
      },
      Alert: {
        borderInfo: `1px solid ${infoBorder}`,
        colorInfo: infoSurface,
        titleTextColorInfo: infoText,
        iconColorInfo: infoText,
        contentTextColorInfo: infoText,
        closeIconColorInfo: infoText,
        closeIconColorHoverInfo: infoText,
        closeIconColorPressedInfo: infoText,
        closeColorHoverInfo: infoSurfaceHover,
        closeColorPressedInfo: infoSurfaceHover,
      },
    }
  })

  watch(
    () => isDark.value,
    (dark) => {
      if (dark)
        document.documentElement.classList.add('dark')
      else
        document.documentElement.classList.remove('dark')
    },
    { immediate: true },
  )

  return { theme, themeOverrides }
}
