<script setup lang='ts'>
import { v4 as uuidv4 } from 'uuid'
import { computed, onMounted, ref } from 'vue'
import { NButton, NIcon, NInput, NModal, NSpace, NTabPane, NTabs, NTag, useMessage } from 'naive-ui'
// import { useRouter } from 'vue-router'
import { CheckmarkCircle } from '@vicons/ionicons5'
import api from '@/api'
import { t } from '@/locales'
import { useAppStore, useAuthStore, useUserStore } from '@/store'

import brandLogo from '@/assets/zhimesh-logo.svg'

interface LoginResp {
  token: string
  name: string
  email: string
  uuid: string
  avatar: string
  locale: string
  captchaId?: string
}

interface RegisterResp {
  autoLogin: boolean
  message: string
  login?: LoginResp
}

// const router = useRouter()
const authStore = useAuthStore()
const userStore = useUserStore()
const appStore = useAppStore()
const ms = useMessage()
const loading = ref(false)
const email = ref('')
const password = ref('')
const loginCaptchaId = ref('')
const loginCaptchaCode = ref('')
const registerCaptchaId = ref(uuidv4().replace(/-/g, ''))
const registerCaptchaCode = ref('')
const loginCaptchaTimestamp = ref(new Date().getTime())
const registerCaptchaTimestamp = ref(new Date().getTime())
const activeTab = ref('login')
const confirmPassword = ref('')
const disabled = computed(() => !email.value.trim() || loading.value)
const registerDisabled = computed(() => {
  const mail = email.value.trim()
  return loading.value
    || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(mail)
    || password.value.length < 8
    || password.value !== confirmPassword.value
})
const registerReturnSuccess = ref<boolean>(false)
const registerReturnMsg = ref<string>('')
const resetPasswordReturnMsg = ref<string>('')

function handlePress(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleLogin()
  }
}

const confirmPasswordStatus = computed(() => {
  if (!password.value || !confirmPassword.value)
    return undefined
  return password.value !== confirmPassword.value ? 'error' : 'success'
})

function refreshRegisterCaptcha(clearFeedback = true) {
  registerCaptchaCode.value = ''
  registerCaptchaId.value = uuidv4().replace(/-/g, '')
  registerCaptchaTimestamp.value = new Date().getTime()
  if (clearFeedback) {
    registerReturnSuccess.value = false
    registerReturnMsg.value = ''
  }
}

function refreshLoginCaptcha() {
  loginCaptchaCode.value = ''
  loginCaptchaTimestamp.value = new Date().getTime()
}

async function handleLogin() {
  const name = email.value.trim()
  const pwd = password.value.trim()
  if (!name || !pwd) {
    ms.error(t('common.emailOrPasswordEmpty'))
    return
  }

  if (loginCaptchaId.value && !loginCaptchaCode.value) {
    ms.error(t('common.pleaseInputCaptcha'))
    return
  }
  try {
    loading.value = true
    const result = await api.login<LoginResp>(email.value, pwd, loginCaptchaId.value, loginCaptchaCode.value)
    await authStore.setToken(result.data.token)
    userStore.replaceUserInfo(result.data)
    appStore.applyUserLocale(result.data.locale)
    ms.success('登录成功')
  } catch (error: any) {
    console.error('login error', error)
    ms.error(error.message ?? 'error')
    if (error.data?.captchaId) {
      // 显示验证码
      loginCaptchaId.value = error.data.captchaId
    }
    password.value = ''
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  const mail = email.value.trim()
  const pwd = password.value.trim()
  const confirmPwd = confirmPassword.value.trim()

  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(mail)) {
    ms.error('请输入有效的邮箱地址')
    return
  }
  if (pwd.length < 8) {
    ms.error('密码至少需要 8 位')
    return
  }
  if (!confirmPwd || pwd !== confirmPwd) {
    ms.error(t('common.passwordNotMatch'))
    return
  }
  const captchaCode = registerCaptchaCode.value.trim()
  if (!/^[a-zA-Z0-9]{4,6}$/.test(captchaCode)) {
    ms.error('请输入图片中的完整验证码')
    return
  }

  try {
    loading.value = true
    const result = await api.register<RegisterResp>(mail, pwd, registerCaptchaId.value, captchaCode)
    const autoLogin = result.data.autoLogin && Boolean(result.data.login?.token)
    registerReturnSuccess.value = true
    registerReturnMsg.value = autoLogin ? '注册成功，已自动登录' : '注册成功，请查收激活邮件完成激活'
    ms.success(registerReturnMsg.value)
    if (autoLogin && result.data.login) {
      await authStore.setToken(result.data.login.token)
      userStore.replaceUserInfo(result.data.login)
      appStore.applyUserLocale(result.data.login.locale)
    }
  } catch (error: any) {
    ms.error(error.message ?? 'error')
    registerReturnMsg.value = error.message ?? 'error'
    registerReturnSuccess.value = false
    refreshRegisterCaptcha(false)
  } finally {
    loading.value = false
  }
}

async function handleForgotPassword() {
  const name = email.value.trim()

  if (!name)
    return

  try {
    loading.value = true
    const result = await api.passwordFind(name)
    ms.success(result.data as string)
    resetPasswordReturnMsg.value = result.data as string
  } catch (error: any) {
    ms.error(error.message ?? 'error')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  console.info('login,onmounted')
})
</script>

<template>
  <NModal v-model:show="authStore.showLoginModal" style="width: 90%; max-width: 640px">
    <div class="p-8 sm:p-10 login-glass-card">
      <div class="space-y-4">
        <header class="login-brand">
          <img :src="brandLogo" alt="ZhiMesh logo">
          <div>
            <h2 class="login-brand-title">ZhiMesh</h2>
            <p class="login-brand-desc">让模型、知识与工具在同一条工作流中协作</p>
          </div>
        </header>

        <NTabs v-model:value="activeTab" default-value="login" type="line">
          <NTabPane name="login" :tab="t('common.login')">
            <NSpace vertical>
              <NInput v-model:value="email" type="text" :placeholder="t('common.email')" :input-props="{ 'autocomplete': 'email', 'aria-label': t('common.email') }" />
              <NInput
                v-model:value="password" type="password" show-password-on="click"
                :placeholder="t('common.password')" :input-props="{ 'autocomplete': 'current-password', 'aria-label': t('common.password') }" @keypress="handlePress"
              />
              <NSpace :wrap-item="false">
                <NInput
                  v-if="loginCaptchaId" v-model:value="loginCaptchaCode" style="flex:1;height:40px;"
                  :placeholder="t('common.captcha')"
                />
                <button
                  v-if="loginCaptchaId"
                  type="button"
                  class="captcha-refresh"
                  title="点击刷新验证码"
                  aria-label="点击刷新验证码"
                  @click="refreshLoginCaptcha"
                >
                  <img
                    :src="`/api/auth/login/captcha?captchaId=${loginCaptchaId}&t_${loginCaptchaTimestamp}`"
                    alt="登录验证码，点击刷新"
                  >
                </button>
              </NSpace>
              <NSpace justify="space-between">
                <NButton type="primary" size="medium" :disabled="disabled" :loading="loading" @click="handleLogin">
                  {{ t('common.login') }}
                </NButton>
                <NButton text type="primary" @click="activeTab = 'forgotPassword'">
                  {{ t('common.forgotPassword') }}
                </NButton>
              </NSpace>
            </NSpace>
          </NTabPane>

          <NTabPane name="register" :tab="t('common.register')">
            <NSpace vertical>
              <NInput v-model:value="email" type="text" size="large" :placeholder="t('common.email')" :input-props="{ autocomplete: 'email' }" />
              <NInput
                v-model:value="password" type="password" size="large" show-password-on="click"
                :placeholder="`${t('common.password')}（至少 8 位）`" :input-props="{ autocomplete: 'new-password' }"
              />
              <NInput
                v-model:value="confirmPassword" type="password" size="large" show-password-on="click"
                :placeholder="t('common.confirmPassword')" :status="confirmPasswordStatus" :input-props="{ autocomplete: 'new-password' }"
              />
              <NSpace :wrap-item="false">
                <NInput
                  v-if="registerCaptchaId" v-model:value="registerCaptchaCode" size="large"
                  style="flex:1;height:40px;" :placeholder="t('common.captcha')"
                  :input-props="{ maxlength: 6, autocomplete: 'off' }"
                />
                <button
                  v-if="registerCaptchaId"
                  type="button"
                  class="captcha-refresh"
                  title="点击刷新验证码"
                  aria-label="点击刷新验证码"
                  @click="refreshRegisterCaptcha"
                >
                  <img
                    :src="`/api/auth/register/captcha?captchaId=${registerCaptchaId}&_t=${registerCaptchaTimestamp}`"
                    alt="注册验证码，点击刷新"
                  >
                </button>
              </NSpace>
              <NSpace v-if="registerReturnMsg">
                <NTag :type="registerReturnSuccess ? 'success' : 'error'">
                  {{ registerReturnMsg }}
                  <template #icon>
                    <NIcon :component="CheckmarkCircle" />
                  </template>
                </NTag>
              </NSpace>
              <NButton
                type="primary" size="medium" :disabled="registerDisabled"
                :loading="loading" @click="handleRegister"
              >
                {{ t('common.register') }}
              </NButton>
            </NSpace>
          </NTabPane>

          <NTabPane name="forgotPassword" tab="">
            <NSpace vertical>
              <NInput v-model:value="email" type="text" size="large" :placeholder="t('common.email')" />
              <NTag v-if="resetPasswordReturnMsg" :type="resetPasswordReturnMsg ? 'success' : 'error'">
                {{ resetPasswordReturnMsg }}
                <template #icon>
                  <NIcon :component="CheckmarkCircle" />
                </template>
              </NTag>
              <NButton type="primary" :disabled="email.length <= 0" :loading="loading" @click="handleForgotPassword">
                {{ t('common.resetPassword') }}
              </NButton>
            </NSpace>
          </NTabPane>
        </NTabs>
      </div>
    </div>
  </NModal>
</template>

<style scoped>
.captcha-refresh {
  flex: 0 0 auto;
  width: clamp(124px, 30vw, 184px);
  height: 40px;
  padding: 0;
  overflow: hidden;
  line-height: 0;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 6px;
}

.captcha-refresh:focus-visible {
  outline: 2px solid #234a7a;
  outline-offset: 2px;
}

.captcha-refresh img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: fill;
}
</style>
