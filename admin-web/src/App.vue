<template>
  <NConfigProvider
    :locale="naiveLocalePair.locale"
    :theme="getDarkTheme"
    :theme-overrides="getThemeOverrides"
    :date-locale="naiveLocalePair.dateLocale"
  >
    <AppProvider>
      <RouterView />
    </AppProvider>
  </NConfigProvider>
</template>

<script lang="ts" setup>
  import { computed, watchEffect } from 'vue'
  import { darkTheme } from 'naive-ui'
  import { AppProvider } from '@/components/Application'
  import { useDesignSettingStore } from '@/store/modules/designSetting'
  import { useLocale } from '@/hooks/useLocale'
  import {
    createZhiMeshDialogOverrides,
    createZhiMeshPalette,
    zhimeshWhite,
  } from '@/theme/zhimeshPalette'

  const { naiveLocalePair } = useLocale()

  const designStore = useDesignSettingStore()

  watchEffect(() => {
    document.documentElement.classList.toggle('dark', designStore.darkTheme)
  })

  const getThemeOverrides = computed(() => {
    const appTheme = designStore.appTheme
    const isDark = designStore.darkTheme
    const {
      componentPrimary,
      componentPrimaryHover,
      componentPrimaryPressed,
      controlPrimary,
      controlPrimaryHover,
      controlPrimaryPressed,
      infoSolid,
      infoSolidHover,
      infoSolidPressed,
      infoSurface,
      infoSurfaceHover,
      infoText,
      infoContent,
      infoBorder,
      dangerText,
      dangerTextHover,
      dangerTextPressed,
      dangerSolid,
      dangerSolidHover,
      dangerSolidPressed,
    } = createZhiMeshPalette(appTheme, isDark)
    return {
      common: {
        primaryColor: componentPrimary,
        primaryColorHover: componentPrimaryHover,
        primaryColorPressed: componentPrimaryPressed,
        primaryColorSuppl: componentPrimary,
        infoColor: componentPrimary,
        infoColorHover: componentPrimaryHover,
        infoColorPressed: componentPrimaryPressed,
        infoColorSuppl: componentPrimary,
        errorColor: dangerText,
        errorColorHover: dangerTextHover,
        errorColorPressed: dangerTextPressed,
        errorColorSuppl: dangerText,
        borderRadius: '8px',
        borderColor: designStore.darkTheme
          ? '#314653'
          : '#d7e0e5',
        cardColor: designStore.darkTheme
          ? '#1d2d39'
          : '#ffffff',
        modalColor: designStore.darkTheme
          ? '#1d2d39'
          : '#ffffff',
      },
      Button: {
        borderRadiusMedium: '8px',
        borderRadiusLarge: '8px',
        colorPrimary: controlPrimary,
        colorHoverPrimary: controlPrimaryHover,
        colorPressedPrimary: controlPrimaryPressed,
        colorFocusPrimary: controlPrimaryHover,
        colorDisabledPrimary: controlPrimary,
        textColorPrimary: zhimeshWhite,
        textColorHoverPrimary: zhimeshWhite,
        textColorPressedPrimary: zhimeshWhite,
        textColorFocusPrimary: zhimeshWhite,
        textColorDisabledPrimary: zhimeshWhite,
        textColorTextPrimary: componentPrimary,
        textColorTextHoverPrimary: componentPrimaryHover,
        textColorTextPressedPrimary: componentPrimaryPressed,
        textColorTextFocusPrimary: componentPrimaryHover,
        textColorGhostPrimary: componentPrimary,
        textColorGhostHoverPrimary: componentPrimaryHover,
        textColorGhostPressedPrimary: componentPrimaryPressed,
        textColorGhostFocusPrimary: componentPrimaryHover,
        borderPrimary: `1px solid ${controlPrimary}`,
        borderHoverPrimary: `1px solid ${controlPrimaryHover}`,
        borderPressedPrimary: `1px solid ${controlPrimaryPressed}`,
        borderFocusPrimary: `1px solid ${controlPrimaryHover}`,
        colorInfo: infoSolid,
        colorHoverInfo: infoSolidHover,
        colorPressedInfo: infoSolidPressed,
        colorFocusInfo: infoSolidHover,
        colorDisabledInfo: infoSolid,
        textColorInfo: zhimeshWhite,
        textColorHoverInfo: zhimeshWhite,
        textColorPressedInfo: zhimeshWhite,
        textColorFocusInfo: zhimeshWhite,
        textColorDisabledInfo: zhimeshWhite,
        textColorTextInfo: infoText,
        textColorTextHoverInfo: isDark ? '#e4f0f8' : '#183e63',
        textColorTextPressedInfo: isDark ? '#f0f6fa' : '#102f4d',
        textColorTextFocusInfo: isDark ? '#e4f0f8' : '#183e63',
        textColorGhostInfo: infoText,
        textColorGhostHoverInfo: isDark ? '#e4f0f8' : '#183e63',
        textColorGhostPressedInfo: isDark ? '#f0f6fa' : '#102f4d',
        textColorGhostFocusInfo: isDark ? '#e4f0f8' : '#183e63',
        borderInfo: `1px solid ${infoSolid}`,
        borderHoverInfo: `1px solid ${infoSolidHover}`,
        borderPressedInfo: `1px solid ${infoSolidPressed}`,
        borderFocusInfo: `1px solid ${infoSolidHover}`,
        colorError: dangerSolid,
        colorHoverError: dangerSolidHover,
        colorPressedError: dangerSolidPressed,
        colorFocusError: dangerSolidHover,
        colorDisabledError: dangerSolid,
        textColorError: zhimeshWhite,
        textColorHoverError: zhimeshWhite,
        textColorPressedError: zhimeshWhite,
        textColorFocusError: zhimeshWhite,
        textColorDisabledError: zhimeshWhite,
        textColorTextError: dangerText,
        textColorTextHoverError: dangerTextHover,
        textColorTextPressedError: dangerTextPressed,
        textColorGhostError: dangerText,
        textColorGhostHoverError: dangerTextHover,
        textColorGhostPressedError: dangerTextPressed,
      },
      Dialog: createZhiMeshDialogOverrides(isDark),
      Card: {
        borderRadius: '10px',
      },
      Input: {
        borderRadius: '8px',
      },
      Tooltip: {
        color: controlPrimary,
        textColor: zhimeshWhite,
        borderRadius: '8px',
        padding: '7px 10px',
        boxShadow: isDark
          ? '0 8px 20px rgba(0, 0, 0, 0.32)'
          : '0 8px 20px rgba(23, 37, 50, 0.18)',
      },
      Alert: {
        colorInfo: infoSurface,
        borderInfo: `1px solid ${infoBorder}`,
        titleTextColorInfo: infoText,
        contentTextColorInfo: infoContent,
        iconColorInfo: infoText,
      },
      Tag: {
        colorPrimary: infoSurface,
        colorBorderedPrimary: infoSurface,
        borderPrimary: `1px solid ${infoBorder}`,
        textColorPrimary: infoText,
        closeIconColorPrimary: infoText,
        closeIconColorHoverPrimary: infoText,
        closeColorHoverPrimary: infoSurfaceHover,
        colorInfo: infoSurface,
        colorBorderedInfo: infoSurface,
        borderInfo: `1px solid ${infoBorder}`,
        textColorInfo: infoText,
        closeIconColorInfo: infoText,
        closeIconColorHoverInfo: infoText,
        closeColorHoverInfo: infoSurfaceHover,
        colorChecked: controlPrimary,
        colorCheckedHover: controlPrimaryHover,
        colorCheckedPressed: controlPrimaryPressed,
        textColorChecked: zhimeshWhite,
      },
      Tabs: {
        tabTextColorActiveLine: componentPrimary,
        tabTextColorHoverLine: componentPrimaryHover,
        tabTextColorActiveBar: componentPrimary,
        tabTextColorHoverBar: componentPrimaryHover,
        tabTextColorActiveCard: componentPrimary,
        tabTextColorHoverCard: componentPrimaryHover,
        barColor: controlPrimary,
      },
      Pagination: {
        itemColorActive: controlPrimary,
        itemColorActiveHover: controlPrimaryHover,
        itemTextColorActive: zhimeshWhite,
        itemTextColorHover: componentPrimary,
        itemTextColorPressed: componentPrimaryPressed,
        itemBorderActive: `1px solid ${controlPrimary}`,
      },
      Checkbox: {
        colorChecked: controlPrimary,
        borderChecked: `1px solid ${controlPrimary}`,
        borderFocus: `1px solid ${controlPrimary}`,
        checkMarkColor: zhimeshWhite,
      },
      Switch: {
        railColorActive: controlPrimary,
        loadingColor: componentPrimary,
        textColor: zhimeshWhite,
      },
      Radio: {
        dotColorActive: controlPrimary,
        boxShadowActive: `inset 0 0 0 1px ${controlPrimary}`,
        buttonColorActive: infoSurface,
        buttonBorderColorActive: infoBorder,
        buttonTextColorActive: infoText,
        buttonTextColorHover: componentPrimary,
      },
      Menu: {
        itemHeight: '42px',
        itemBorderRadius: '7px',
        itemTextColor: designStore.darkTheme ? '#cbd5e1' : '#334155',
        itemTextColorHover: componentPrimary,
        itemColorActive: controlPrimary,
        itemColorActiveHover: controlPrimaryHover,
        itemTextColorActive: '#ffffff',
        itemTextColorActiveHover: '#ffffff',
        itemIconColor: designStore.darkTheme ? '#94a3b8' : '#64748b',
        itemIconColorHover: componentPrimary,
        itemIconColorActive: '#ffffff',
        itemIconColorActiveHover: '#ffffff',
        arrowColor: designStore.darkTheme ? '#94a3b8' : '#64748b',
        arrowColorHover: componentPrimary,
        arrowColorActive: '#ffffff',
      },
      LoadingBar: {
        colorLoading: controlPrimary,
      },
    }
  })

  const getDarkTheme = computed(() => (designStore.darkTheme ? darkTheme : undefined))
</script>
