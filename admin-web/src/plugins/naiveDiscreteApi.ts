import * as NaiveUI from 'naive-ui'
import { computed } from 'vue'
import { useDesignSetting } from '@/store/modules/designSetting'
import {
  createZhiMeshDialogOverrides,
  createZhiMeshPalette,
  zhimeshWhite,
} from '@/theme/zhimeshPalette'

/**
 * 挂载 Naive-ui 脱离上下文的 API
 * 如果你想在 setup 外使用 useDialog、useMessage、useNotification、useLoadingBar，可以通过 createDiscreteApi 来构建对应的 API。
 * https://www.naiveui.com/zh-CN/dark/components/discrete
 */

export function setupNaiveDiscreteApi() {
  const designStore = useDesignSetting()

  const configProviderPropsRef = computed(() => {
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
      infoText,
      dangerText,
      dangerTextHover,
      dangerTextPressed,
      dangerSolid,
      dangerSolidHover,
      dangerSolidPressed,
    } = createZhiMeshPalette(designStore.appTheme, isDark)
    return {
      theme: isDark ? NaiveUI.darkTheme : undefined,
      themeOverrides: {
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
          cardColor: isDark ? '#1d2d39' : '#ffffff',
          modalColor: isDark ? '#1d2d39' : '#ffffff',
          popoverColor: isDark ? '#1d2d39' : '#ffffff',
        },
        Button: {
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
          textColorGhostPrimary: componentPrimary,
          textColorGhostHoverPrimary: componentPrimaryHover,
          textColorGhostPressedPrimary: componentPrimaryPressed,
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
          textColorGhostInfo: infoText,
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
        LoadingBar: {
          colorLoading: controlPrimary,
        },
      },
    }
  })
  const { message, dialog, notification, loadingBar } = NaiveUI.createDiscreteApi(
    ['message', 'dialog', 'notification', 'loadingBar'],
    {
      configProviderProps: configProviderPropsRef,
    }
  )

  window['$message'] = message
  window['$dialog'] = dialog
  window['$notification'] = notification
  window['$loading'] = loadingBar
}
