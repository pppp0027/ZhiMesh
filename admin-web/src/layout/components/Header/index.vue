<template>
  <div class="layout-header">
    <!--顶部菜单-->
    <div
      class="layout-header-left"
      v-if="navMode === 'horizontal' || (navMode === 'horizontal-mix' && mixMenu)"
    >
      <div class="logo" v-if="navMode === 'horizontal'">
        <img :src="websiteConfig.logo" alt="" />
        <h2 v-show="!collapsed" class="title">{{ websiteConfig.title }}</h2>
      </div>
      <AsideMenu
        :collapsed="collapsed"
        v-model:location="getMenuLocation"
        :inverted="getInverted"
        mode="horizontal"
      />
    </div>
    <!--左侧菜单-->
    <div class="layout-header-left" v-else>
      <!-- 菜单收起 -->
      <button
        type="button"
        aria-label="切换侧边导航"
        class="ml-1 layout-header-trigger layout-header-trigger-min"
        @click="handleMenuCollapsed"
      >
        <n-icon size="18" v-if="collapsed">
          <MenuUnfoldOutlined />
        </n-icon>
        <n-icon size="18" v-else>
          <MenuFoldOutlined />
        </n-icon>
      </button>
      <!-- 刷新 -->
      <button
        v-if="headerSetting.isReload"
        type="button"
        aria-label="刷新当前页面"
        class="mr-1 layout-header-trigger layout-header-trigger-min layout-header-reload"
        @click="reloadPage"
      >
        <n-icon size="18">
          <ReloadOutlined />
        </n-icon>
      </button>
      <div class="layout-header-mobile-context" aria-live="polite">
        {{ currentPageTitle }}
      </div>
      <!-- 面包屑 -->
      <n-breadcrumb v-if="crumbsSetting.show">
        <template
          v-for="routeItem in breadcrumbList"
          :key="routeItem.name === 'Redirect' ? void 0 : routeItem.name"
        >
          <n-breadcrumb-item v-if="routeItem.meta.title">
            <n-dropdown
              v-if="routeItem.children.length"
              :options="routeItem.children"
              @select="dropdownSelect"
            >
              <span class="link-text">
                <component
                  v-if="crumbsSetting.showIcon && routeItem.meta.icon"
                  :is="routeItem.meta.icon"
                />
                {{ t(routeItem.meta.title) }}
              </span>
            </n-dropdown>
            <span class="link-text" v-else>
              <component
                v-if="crumbsSetting.showIcon && routeItem.meta.icon"
                :is="routeItem.meta.icon"
              />
              {{ t(routeItem.meta.title) }}
            </span>
          </n-breadcrumb-item>
        </template>
      </n-breadcrumb>
    </div>
    <div class="layout-header-right">
      <!-- <div
        class="layout-header-trigger layout-header-trigger-min"
        v-for="item in iconList"
        :key="item.icon"
      >
        <n-tooltip placement="bottom">
          <template #trigger>
            <n-icon size="18">
              <component :is="item.icon" v-on="item.eventObject || {}" />
            </n-icon>
          </template>
          <span>{{ item.tips }}</span>
        </n-tooltip>
      </div> -->
      <!--切换语言-->
      <div class="layout-header-language mr-2">
        <n-dropdown trigger="click" :options="langOptions" @select="handleLangSelect">
          <button
            type="button"
            class="layout-header-trigger layout-header-trigger-min layout-header-text-btn"
            aria-label="切换界面语言"
          >
            <span class="language-label-long">{{ locale === 'zh-CN' ? '简体中文' : 'English' }}</span>
            <span class="language-label-short">{{ locale === 'zh-CN' ? '中' : 'EN' }}</span>
          </button>
        </n-dropdown>
      </div>
      <!--切换全屏-->
      <!-- <div class="layout-header-trigger layout-header-trigger-min">
        <n-tooltip placement="bottom">
          <template #trigger>
            <n-icon size="18">
              <component :is="fullscreenIcon" @click="toggleFullScreen" />
            </n-icon>
          </template>
          <span>{{ t('setting.fullscreen') }}</span>
        </n-tooltip>
      </div> -->
      <!-- 退出登录 -->
      <button
        type="button"
        class="layout-header-trigger layout-header-trigger-min layout-header-text-btn"
        @click="doLogout"
      >
        <span class="logout-button-content">
          <span class="logout-label">{{ t('login.logout') }}</span>
          <n-icon size="16"><LogoutOutlined /></n-icon>
        </span>
      </button>
      <!--设置-->
      <!-- <div class="layout-header-trigger layout-header-trigger-min" @click="openSetting">
        <n-tooltip placement="bottom-end">
          <template #trigger>
            <n-icon size="18" style="font-weight: bold">
              <SettingOutlined />
            </n-icon>
          </template>
          <span>{{ t('setting.projectConfig') }}</span>
        </n-tooltip>
      </div> -->
    </div>
  </div>
  <!--项目配置-->
  <ProjectSetting ref="drawerSetting" />
</template>

<script lang="ts">
  import { defineComponent, reactive, toRefs, ref, computed, unref, onMounted, onUnmounted } from 'vue'
  import { useRouter, useRoute } from 'vue-router'
  import components from './components'
  import { NDialogProvider, useDialog, useMessage } from 'naive-ui'
  import { TABS_ROUTES } from '@/store/mutation-types'
  import { useUserStore } from '@/store/modules/user'
  import ProjectSetting from './ProjectSetting.vue'
  import { AsideMenu } from '@/layout/components/Menu'
  import { useProjectSetting } from '@/hooks/setting/useProjectSetting'
  import { websiteConfig } from '@/config/website.config'
  import { t, type Locale } from '@/locales'
  import { useLocale } from '@/hooks/useLocale'
  import { GlobalOutlined } from '@vicons/antd'

  export default defineComponent({
    name: 'PageHeader',
    components: { ...components, NDialogProvider, ProjectSetting, AsideMenu, GlobalOutlined },
    props: {
      collapsed: {
        type: Boolean,
      },
      inverted: {
        type: Boolean,
      },
    },
    emits: ['update:collapsed'],
    setup(props, { emit }) {
      const userStore = useUserStore()
      const message = useMessage()
      const dialog = useDialog()
      const { navMode, navTheme, headerSetting, menuSetting, crumbsSetting } = useProjectSetting()
      const { locale, changeLocale } = useLocale()

      const drawerSetting = ref()

      const state = reactive({
        fullscreenIcon: 'FullscreenOutlined',
        navMode,
        navTheme,
        headerSetting,
        crumbsSetting,
      })

      const getInverted = computed(() => {
        return ['light', 'header-dark'].includes(unref(navTheme)) ? props.inverted : !props.inverted
      })

      const mixMenu = computed(() => {
        return unref(menuSetting).mixMenu
      })

      const getChangeStyle = computed(() => {
        const { collapsed } = props
        const { minMenuWidth, menuWidth } = unref(menuSetting)
        return {
          left: collapsed ? `${minMenuWidth}px` : `${menuWidth}px`,
          width: `calc(100% - ${collapsed ? `${minMenuWidth}px` : `${menuWidth}px`})`,
        }
      })

      const getMenuLocation = computed(() => {
        return 'header'
      })

      const router = useRouter()
      const route = useRoute()

      const generator: any = (routerMap) => {
        return routerMap.map((item) => {
          const currentMenu = {
            ...item,
            label: t(item.meta.title),
            key: item.name,
            disabled: item.path === '/',
          }
          // 是否有子菜单，并递归处理
          if (item.children && item.children.length > 0) {
            // Recursion
            currentMenu.children = generator(item.children, currentMenu)
          }
          return currentMenu
        })
      }

      const breadcrumbList = computed(() => {
        return generator(route.matched)
      })

      const currentPageTitle = computed(() => {
        const titledRoute = [...breadcrumbList.value]
          .reverse()
          .find((item: any) => item.meta?.title)
        return titledRoute ? t(titledRoute.meta.title) : websiteConfig.title
      })

      const dropdownSelect = (key) => {
        router.push({ name: key })
      }

      // 刷新页面
      const reloadPage = () => {
        router.push({
          path: '/redirect' + unref(route).fullPath,
        })
      }

      // 退出登录
      const doLogout = () => {
        dialog.info({
          title: t('common.tip'),
          content: t('login.logoutConfirmContent'),
          positiveText: t('common.positiveText'),
          negativeText: t('common.negativeText'),
          onPositiveClick: () => {
            userStore.logout().then(() => {
              message.success(t('login.logoutSuccess'))
              // 移除标签页
              localStorage.removeItem(TABS_ROUTES)
              router
                .replace({
                  name: 'Login',
                  query: {
                    redirect: route.fullPath,
                  },
                })
                .finally(() => location.reload())
            })
          },
          onNegativeClick: () => {},
        })
      }

      // 切换全屏图标
      const toggleFullscreenIcon = () =>
        (state.fullscreenIcon =
          document.fullscreenElement !== null ? 'FullscreenExitOutlined' : 'FullscreenOutlined')

      // 监听全屏切换事件，仅在组件存活期间注册，避免切换布局后重复监听。
      onMounted(() => {
        toggleFullscreenIcon()
        document.addEventListener('fullscreenchange', toggleFullscreenIcon)
      })
      onUnmounted(() => {
        document.removeEventListener('fullscreenchange', toggleFullscreenIcon)
      })

      // 全屏切换
      const toggleFullScreen = () => {
        if (!document.fullscreenElement) {
          document.documentElement.requestFullscreen()
        } else {
          if (document.exitFullscreen) {
            document.exitFullscreen()
          }
        }
      }

      // 图标列表
      // const iconList = [
      //   {
      //     icon: 'SearchOutlined',
      //     tips: '搜索',
      //   },
      // ];
      const avatarOptions = [
        {
          label: t('login.logout'),
          key: 2,
        },
      ]

      const langOptions = [
        { label: '简体中文', key: 'zh-CN' },
        { label: 'English', key: 'en-US' },
      ]

      const handleLangSelect = (key: string) => {
        changeLocale(key as Locale)
      }

      //头像下拉菜单
      const avatarSelect = (key) => {
        switch (key) {
          case 1:
            router.push({ name: 'Setting' })
            break
          case 2:
            doLogout()
            break
        }
      }

      function openSetting() {
        const { openDrawer } = drawerSetting.value
        openDrawer()
      }

      function handleMenuCollapsed() {
        emit('update:collapsed', !props.collapsed)
      }

      return {
        ...toRefs(state),
        // iconList,
        toggleFullScreen,
        doLogout,
        route,
        dropdownSelect,
        avatarOptions,
        getChangeStyle,
        avatarSelect,
        breadcrumbList,
        currentPageTitle,
        reloadPage,
        drawerSetting,
        openSetting,
        getInverted,
        getMenuLocation,
        mixMenu,
        websiteConfig,
        handleMenuCollapsed,
        langOptions,
        handleLangSelect,
        t,
        locale,
      }
    },
  })
</script>

<style lang="less" scoped>
  .layout-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    box-sizing: border-box;
    width: auto;
    height: 60px;
    margin: 0;
    padding: 0 22px;
    gap: 16px;
    border: 0;
    border-bottom: 1px solid var(--zhimesh-border);
    border-radius: 0;
    background: var(--zhimesh-glass-nav-strong) !important;
    box-shadow: inset 0 -1px 0 var(--zhimesh-glass-highlight);
    backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
    -webkit-backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
    z-index: 11;

    &-left {
      display: flex;
      min-width: 0;
      align-items: center;

      .logo {
        display: flex;
        align-items: center;
        justify-content: center;
        height: 64px;
        line-height: 64px;
        overflow: hidden;
        white-space: nowrap;
        padding-left: 10px;

        img {
          width: auto;
          height: 32px;
          margin-right: 10px;
        }

        .title {
          margin-bottom: 0;
        }
      }

      ::v-deep(.ant-breadcrumb span:last-child .link-text) {
        color: var(--zhimesh-muted);
      }

      .n-breadcrumb {
        display: inline-block;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      &-menu {
        color: var(--text-color);
      }
    }

    &-right {
      display: flex;
      flex: none;
      align-items: center;
      margin-right: 0;

      .avatar {
        display: flex;
        align-items: center;
        height: 64px;
      }

      > * {
        cursor: pointer;
      }
    }

    &-trigger {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 64px;
      height: 56px;
      padding: 0;
      border: 0;
      color: var(--zhimesh-text);
      background: transparent;
      font: inherit;
      cursor: pointer;
      transition: color 0.16s ease, background-color 0.16s ease;

      .n-icon {
        display: flex;
        align-items: center;
        height: 56px;
      }

      &:hover {
        color: var(--zhimesh-primary);
        background: var(--zhimesh-table-head);
      }

      .anticon {
        font-size: 16px;
        color: var(--zhimesh-muted);
      }
    }

    &-trigger-min {
      width: auto;
      padding: 0 12px;
    }

    &-text-btn {
      height: 34px;
      border: 1px solid var(--zhimesh-border-subtle);
      border-radius: 7px;
      padding: 0 16px;
      font-size: 14px;
      color: var(--zhimesh-text);
      background: var(--zhimesh-glass);
      white-space: nowrap;

      &:hover {
        border-color: var(--zhimesh-border-hover);
        color: var(--zhimesh-primary);
        background: var(--zhimesh-table-cell-hover);
      }
    }

    .language-label-short {
      display: none;
    }

    .logout-button-content {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      line-height: 1;
    }

    &-mobile-context {
      display: none;
    }
  }

  .layout-header-light {
    color: var(--zhimesh-text);
    background: transparent;

    .n-icon {
      color: var(--zhimesh-muted);
    }

    .layout-header-left {
      ::v-deep(.n-breadcrumb .n-breadcrumb-item:last-child .n-breadcrumb-item__link) {
        color: var(--zhimesh-muted);
      }
    }

    .layout-header-trigger {
      &:hover {
        background: var(--zhimesh-table-head);
      }
    }
  }

  @media (max-width: 800px) {
    .layout-header {
      height: calc(56px + env(safe-area-inset-top));
      margin: 0;
      padding: env(safe-area-inset-top) 8px 0;
      border-radius: 0;
      gap: 6px;

      &-left {
        min-width: 0;
        flex: 1;
      }

      .n-breadcrumb {
        display: none;
      }

      &-reload {
        display: none;
      }

      &-mobile-context {
        display: block;
        min-width: 0;
        overflow: hidden;
        flex: 1;
        color: var(--zhimesh-text);
        font-size: 16px;
        font-weight: 720;
        line-height: 1.2;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      &-right {
        gap: 6px;
      }

      &-language {
        margin-right: 0 !important;
      }

      &-trigger-min,
      &-text-btn {
        width: 44px;
        height: 44px;
        padding: 0;
        border-radius: 12px;
      }

      .language-label-long {
        display: none;
      }

      .language-label-short {
        display: inline;
      }

      .logout-button-content {
        gap: 4px;
      }

      .logout-label {
        display: none;
      }
    }
  }

  @supports not (backdrop-filter: blur(1px)) {
    .layout-header {
      background: var(--zhimesh-glass-strong) !important;
      backdrop-filter: none;
      -webkit-backdrop-filter: none;
    }
  }

  @media (prefers-reduced-transparency: reduce) {
    .layout-header {
      background: var(--zhimesh-glass-strong) !important;
      backdrop-filter: none !important;
      -webkit-backdrop-filter: none !important;
    }
  }

  .layout-header-fix {
    position: fixed;
    top: 0;
    right: 0;
    left: 200px;
    z-index: 11;
  }

  //::v-deep(.menu-router-link) {
  //  color: #515a6e;
  //
  //  &:hover {
  //    color: #1890ff;
  //  }
  //}
</style>
