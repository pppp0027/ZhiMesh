<template>
  <div class="view-account">
    <section class="view-account-context" aria-label="ZhiMesh 能力说明">
      <div class="context-brand">
        <img :src="websiteConfig.logo" alt="ZhiMesh logo" />
        <div><strong>ZhiMesh</strong><span>管理端</span></div>
      </div>
      <h1>模型、知识与工具，从这里接入。</h1>
      <p>在一处配置权限、用量与运行状态。</p>

      <div class="context-network" aria-label="管理范围">
        <div class="context-node">
          <span><n-icon><HardwareChipOutline /></n-icon></span>
          <div><strong>模型能力</strong><small>平台、模型与额度</small></div>
        </div>
        <i aria-hidden="true"></i>
        <div class="context-node">
          <span><n-icon><LibraryOutline /></n-icon></span>
          <div><strong>知识资源</strong><small>知识库与可追溯内容</small></div>
        </div>
        <i aria-hidden="true"></i>
        <div class="context-node">
          <span><n-icon><ExtensionPuzzleOutline /></n-icon></span>
          <div><strong>执行工具</strong><small>工作流与 MCP 服务</small></div>
        </div>
        <i aria-hidden="true"></i>
        <div class="context-node">
          <span><n-icon><ShieldCheckmarkOutline /></n-icon></span>
          <div><strong>运行治理</strong><small>用户、配额与系统状态</small></div>
        </div>
      </div>
    </section>
    <div class="view-account-container">
      <div class="view-account-top">
        <div class="view-account-top-logo">
          <img :src="websiteConfig.loginImage" alt="ZhiMesh logo" />
        </div>
        <h2>登录控制台</h2>
        <p>使用管理员账号继续</p>
      </div>
      <div class="view-account-form">
          <n-form
            ref="formRef"
            label-placement="top"
            size="large"
            :model="formInline"
            :rules="rules"
            @submit.prevent="handleSubmit"
          >
          <n-form-item path="email" :label="t('login.emailLabel')">
            <n-input
              v-model:value="formInline.email"
              :placeholder="t('login.emailPlaceholder')"
              :input-props="{ autocomplete: 'email' }"
            >
              <template #prefix>
      <n-icon size="18" color="var(--zhimesh-muted)">
                  <PersonOutline />
                </n-icon>
              </template>
            </n-input>
          </n-form-item>
          <n-form-item path="password" :label="t('login.passwordLabel')">
            <n-input
              v-model:value="formInline.password"
              type="password"
              showPasswordOn="click"
              :placeholder="t('login.passwordPlaceholder')"
              :input-props="{ autocomplete: 'current-password' }"
            >
              <template #prefix>
      <n-icon size="18" color="var(--zhimesh-muted)">
                  <LockClosedOutline />
                </n-icon>
              </template>
            </n-input>
          </n-form-item>
          <n-form-item v-if="loginCaptchaId" :label="t('login.captchaLabel')">
            <n-input
              v-model:value="loginCaptchaCode"
              style="flex: 1; height: 40px"
              :placeholder="t('login.captchaPlaceholder')"
            />
            <button
              type="button"
              class="captcha-refresh"
              :aria-label="t('login.captchaLabel')"
              @click="captchaTimestamp = new Date().getTime()"
            >
              <img
                :src="`/api/auth/login/captcha?captchaId=${loginCaptchaId}&t_${captchaTimestamp}`"
                :alt="t('login.captchaLabel')"
              />
            </button>
          </n-form-item>
          <!-- <n-form-item class="default-color">
            <div class="flex justify-between">
              <div class="flex-initial">
                <n-checkbox v-model:checked="autoLogin">自动登录</n-checkbox>
              </div>
              <div class="flex-initial order-last">
                <a href="javascript:">忘记密码</a>
              </div>
            </div>
          </n-form-item> -->
          <n-form-item>
            <n-button type="primary" attr-type="submit" size="large" :loading="loading" block>
              {{ t('login.title') }}
            </n-button>
          </n-form-item>
          <n-form-item class="default-color">
            <div class="flex-initial" style="margin-left: auto">
              <a class="user-workspace-link" href="/" target="_blank" rel="noopener noreferrer">{{
                t('login.goToSite')
              }}</a>
            </div>
          </n-form-item>
        </n-form>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { reactive, ref } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { useUserStore } from '@/store/modules/user'
  import { useMessage } from 'naive-ui'
  import { t } from '@/locales'
  import {
    ExtensionPuzzleOutline,
    HardwareChipOutline,
    LibraryOutline,
    LockClosedOutline,
    PersonOutline,
    ShieldCheckmarkOutline,
  } from '@vicons/ionicons5'
  import { websiteConfig } from '@/config/website.config'
  interface FormState {
    email: string
    password: string
    captchaId?: string
    captchaCode?: string
  }

  const loginCaptchaId = ref('')
  const loginCaptchaCode = ref('')
  const captchaTimestamp = ref(new Date().getTime())
  const formRef = ref()
  const message = useMessage()
  const loading = ref(false)
  // const autoLogin = ref(false);
  const formInline = reactive({
    email: '',
    password: '',
    captchaId: '',
    captchaCode: '',
    isCaptcha: true,
  })

  const rules = {
    email: { required: true, message: () => t('login.emailRequired'), trigger: 'blur' },
    password: { required: true, message: () => t('login.passwordRequired'), trigger: 'blur' },
  }

  const userStore = useUserStore()

  const router = useRouter()
  const route = useRoute()

  function getSafeRedirectPath() {
    const redirect = route.query?.redirect
    if (typeof redirect !== 'string') return '/'
    try {
      const decoded = decodeURIComponent(redirect)
      return decoded.startsWith('/') && !decoded.startsWith('//') ? decoded : '/'
    } catch (error) {
      console.warn('Invalid login redirect ignored', error)
      return '/'
    }
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    if (loginCaptchaId.value && !loginCaptchaCode.value) {
      message.error(t('login.captchaRequired'))
      return
    }
    formRef.value.validate(async (errors) => {
      if (!errors) {
        const { email, password } = formInline
        message.loading(t('login.loggingIn'))
        loading.value = true
        const params: FormState = {
          email,
          password,
          captchaId: loginCaptchaId.value,
          captchaCode: loginCaptchaCode.value,
        }
        try {
          const { success, message: msg, data } = await userStore.login(params)
          message.destroyAll()
          if (success) {
            message.success(t('login.loginSuccess'))
            router.replace(getSafeRedirectPath())
          } else if (data?.captchaId) {
            // 显示验证码
            loginCaptchaId.value = data.captchaId
          } else {
            message.error(msg || t('login.loginFailed'))
          }
        } catch (error) {
          message.destroyAll()
          console.error('Login request failed', error)
          if (!(error as { isAxiosError?: boolean })?.isAxiosError)
            message.error(t('login.loginFailed'))
        } finally {
          loading.value = false
        }
      } else {
        message.error(t('login.fillCompleteInfo'))
      }
    })
  }
</script>

<style lang="less" scoped>
  .view-account {
    display: flex;
    flex-direction: row;
    box-sizing: border-box;
    width: 100%;
    min-height: 100vh;
    min-height: 100dvh;
    overflow-y: auto;
    position: relative;
    justify-content: center;
    align-items: center;
    gap: clamp(36px, 6vw, 72px);
    padding: 32px;
    background: var(--zhimesh-page-bg);

    &-context {
      width: min(440px, 38vw);
      color: var(--zhimesh-text);

      .context-brand {
        display: flex;
        margin-bottom: 34px;
        align-items: center;
        gap: 12px;

        img {
          width: 46px;
          height: 46px;
        }

        strong,
        span {
          display: block;
        }

        strong {
          font-size: 18px;
          letter-spacing: -0.02em;
        }

        span {
          margin-top: 3px;
          color: var(--zhimesh-muted);
          font-size: 11px;
        }
      }

      h1 {
        margin: 0;
        max-width: 10ch;
        font-size: clamp(34px, 4vw, 48px);
        font-weight: 700;
        letter-spacing: -0.035em;
        line-height: 1.16;
      }

      > p {
        max-width: 31ch;
        margin: 16px 0 0;
        color: var(--zhimesh-muted);
        font-size: 15px;
        line-height: 1.7;
      }
    }

    &-container {
      z-index: 1;
      flex: none;
      box-sizing: border-box;
      width: min(420px, 100%);
      padding: 38px 38px 28px;
      min-width: 0;
      margin: 0;
      border: 1px solid var(--zhimesh-border);
      border-radius: 12px;
      background: var(--zhimesh-glass-strong);
      box-shadow: none;
    }

    &-top {
      padding: 0 0 28px;
      text-align: center;

      &-logo img {
        width: 60px;
        height: 60px;
      }

      h2 {
        margin: 12px 0 5px;
        color: var(--zhimesh-text);
        font-size: 24px;
        line-height: 1.2;
        letter-spacing: -0.035em;
      }

      p {
        margin: 0;
        color: var(--zhimesh-muted);
        font-size: 13px;
      }

      &-desc {
        font-size: 14px;
        color: var(--zhimesh-muted);
        letter-spacing: 0.02em;
      }
    }

    &-other {
      width: 100%;
    }

    .default-color {
      color: var(--zhimesh-muted);

      .ant-checkbox-wrapper {
        color: var(--zhimesh-muted);
      }
    }

    .user-workspace-link {
      color: var(--zhimesh-primary);
      font-size: 14px;
      font-weight: 650;
      text-decoration: none;
      text-underline-offset: 4px;
      transition: color 0.2s ease, text-decoration-color 0.2s ease;

      &:hover {
        color: var(--zhimesh-accent);
        text-decoration: underline;
      }
    }

    .captcha-refresh {
      flex: 0 0 132px;
      height: 40px;
      margin-left: 10px;
      padding: 0;
      overflow: hidden;
      border: 1px solid var(--zhimesh-border);
      border-radius: 7px;
      background: var(--zhimesh-glass);
      cursor: pointer;

      img {
        display: block;
        width: 100%;
        height: 100%;
        object-fit: fill;
      }
    }
  }

  .context-network {
    display: grid;
    margin-top: 38px;
    grid-template-columns: 1fr;
  }

  .context-node {
    display: flex;
    align-items: center;
    gap: 13px;
  }

  .context-node > span {
    display: grid;
    width: 38px;
    height: 38px;
    flex: none;
    place-items: center;
    border: 1px solid var(--zhimesh-info-border);
    border-radius: 10px;
    color: var(--zhimesh-info-text);
    background: var(--zhimesh-info-surface);
  }

  .context-node strong,
  .context-node small {
    display: block;
  }

  .context-node strong {
    color: var(--zhimesh-text);
    font-size: 14px;
  }

  .context-node small {
    margin-top: 3px;
    color: var(--zhimesh-muted);
    font-size: 12px;
  }

  .context-network > i {
    position: relative;
    width: 1px;
    height: 18px;
    margin-left: 19px;
    overflow: hidden;
    background: var(--zhimesh-border);
  }

  .context-network > i::after {
    position: absolute;
    inset: 0;
    background: var(--zhimesh-control-primary);
    content: '';
    transform: scaleY(1);
    transform-origin: center top;
  }

  @media (prefers-reduced-motion: no-preference) {
    .context-network > i::after {
      animation: login-network-connect 420ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    .context-network > i:nth-of-type(2)::after { animation-delay: 170ms; }
    .context-network > i:nth-of-type(3)::after { animation-delay: 340ms; }

    .context-node > span {
      animation: login-network-node 320ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    .context-node:nth-of-type(2) > span { animation-delay: 140ms; }
    .context-node:nth-of-type(3) > span { animation-delay: 300ms; }
    .context-node:nth-of-type(4) > span { animation-delay: 460ms; }

    .view-account-container {
      animation: login-panel-enter 480ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }
  }

  @keyframes login-network-connect {
    from { opacity: 0.35; transform: scaleY(0); }
    to { opacity: 1; transform: scaleY(1); }
  }

  @keyframes login-network-node {
    from { opacity: 0.35; clip-path: inset(0 100% 0 0); }
    to { opacity: 1; clip-path: inset(0 0 0 0); }
  }

  @keyframes login-panel-enter {
    from { opacity: 0; transform: translate3d(12px, 0, 0); }
    to { opacity: 1; transform: translate3d(0, 0, 0); }
  }

  @media (min-width: 768px) {
    .page-account-container {
      padding: 32px 0 24px 0;
    }
  }

  @media (max-width: 820px) {
    .view-account {
      flex-direction: column;
      gap: 22px;
      padding: 24px 16px;

      &-context {
        width: min(420px, 100%);

        h1 {
          max-width: 18ch;
          font-size: 28px;
        }

        > p {
          margin-top: 8px;
        }
      }

      &-container {
        padding: 30px 24px 22px;
      }
    }

    .context-brand {
      margin-bottom: 18px !important;
    }

    .context-network {
      display: none;
    }
  }
</style>
