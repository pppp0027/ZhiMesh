<template>
  <div class="console" :class="{ 'is-ready': !loading }">
    <header class="console-header">
      <div>
        <h1>能力网络</h1>
        <p class="console-subtitle">查看今日用户、知识、角色和资源使用情况。</p>
      </div>
      <n-button secondary :loading="loading" @click="loadStatistics">
        <template #icon>
          <n-icon><RefreshOutline /></n-icon>
        </template>
        刷新数据
      </n-button>
    </header>

    <n-alert v-if="loadError" class="console-error" type="error" :title="loadError" />

    <section class="console-overview" aria-label="系统概览">
      <article class="console-focus-panel">
        <div class="console-focus-heading">
          <span>用户总量</span>
          <n-icon size="20"><PeopleOutline /></n-icon>
        </div>
        <div class="console-focus-value">
          <n-skeleton v-if="loading" :width="92" height="42" />
          <CountTo v-else :startVal="0" :endVal="userStatistic.totalNormal" />
        </div>
        <p>当前可用用户</p>
        <div class="console-focus-note">
          <span>今日新增</span>
          <strong v-if="!loading">{{ userStatistic.todayCreated }}</strong>
          <n-skeleton v-else text :width="42" />
        </div>
      </article>

      <div class="console-metric-ledger" aria-label="内容资源">
        <article class="console-metric-row">
          <span>知识库</span>
          <n-skeleton v-if="loading" :width="70" height="28" />
          <CountTo v-else :startVal="0" :endVal="kbStatistic.kbTotal" class="console-metric-value" />
          <small v-if="!loading">今日新增 {{ kbStatistic.kbTodayCreated }}</small>
        </article>
        <article class="console-metric-row">
          <span>知识点</span>
          <n-skeleton v-if="loading" :width="70" height="28" />
          <CountTo v-else :startVal="0" :endVal="kbStatistic.itemTotal" class="console-metric-value" />
          <small v-if="!loading">今日新增 {{ kbStatistic.itemTodayCreated }}</small>
        </article>
        <article class="console-metric-row">
          <span>角色</span>
          <n-skeleton v-if="loading" :width="70" height="28" />
          <CountTo v-else :startVal="0" :endVal="characterStatistic.total" class="console-metric-value" />
          <small v-if="!loading">今日新增 {{ characterStatistic.todayCreated }}</small>
        </article>
      </div>
    </section>

    <section class="console-usage" aria-label="资源使用">
      <div class="console-section-heading">
        <h2>资源消耗</h2>
        <p>当前计费周期内的实际调用量。</p>
      </div>

      <div class="console-ledger">
        <div class="console-ledger-row">
          <div class="console-ledger-name">
            <span class="console-ledger-mark is-teal"><n-icon><PulseOutline /></n-icon></span>
            <div><strong>Token</strong><small>模型调用消耗</small></div>
          </div>
          <div class="console-ledger-values">
            <span>今日 <b v-if="!loading">{{ tokenCostStatistic.todayTokenCost }}</b><n-skeleton v-else text :width="44" /></span>
            <span>本月 <b v-if="!loading">{{ tokenCostStatistic.monthTokenCost }}</b><n-skeleton v-else text :width="44" /></span>
          </div>
        </div>
        <div class="console-ledger-row">
          <div class="console-ledger-name">
            <span class="console-ledger-mark is-amber"><n-icon><ImageOutline /></n-icon></span>
            <div><strong>图像</strong><small>图像生成消耗</small></div>
          </div>
          <div class="console-ledger-values">
            <span>今日 <b v-if="!loading">{{ imageCostStatistic.todayCost }}</b><n-skeleton v-else text :width="44" /></span>
            <span>本月 <b v-if="!loading">{{ imageCostStatistic.monthCost }}</b><n-skeleton v-else text :width="44" /></span>
          </div>
        </div>
      </div>
    </section>

    <section class="console-path" aria-label="能力路径">
      <div class="console-section-heading">
        <h2>配置链路</h2>
        <p>用户请求如何经过知识上下文并调用执行能力。</p>
      </div>
      <div class="console-path-grid">
        <div class="console-path-step">
          <span class="console-path-icon"><n-icon><PeopleOutline /></n-icon></span>
          <div><strong>用户入口</strong><small>进入对话与工作流</small></div>
        </div>
        <i aria-hidden="true"></i>
        <div class="console-path-step">
          <span class="console-path-icon"><n-icon><LibraryOutline /></n-icon></span>
          <div><strong>知识上下文</strong><small>提供可追溯依据</small></div>
        </div>
        <i aria-hidden="true"></i>
        <div class="console-path-step">
          <span class="console-path-icon"><n-icon><GitNetworkOutline /></n-icon></span>
          <div><strong>执行能力</strong><small>模型与工具协同运行</small></div>
        </div>
      </div>
    </section>
  </div>
</template>

<script lang="ts" setup>
  import { onMounted, ref } from 'vue'
  import {
    GitNetworkOutline,
    ImageOutline,
    LibraryOutline,
    PeopleOutline,
    PulseOutline,
    RefreshOutline,
  } from '@vicons/ionicons5'
  import { getStatistic } from '@/api/dashboard/console'
  import { CountTo } from '@/components/CountTo/index'

  interface UserStatistic {
    todayCreated: number
    todayActivated: number
    totalNormal: number
  }

  interface KbStatistic {
    kbTodayCreated: number
    itemTodayCreated: number
    kbTotal: number
    itemTotal: number
  }

  interface TokenCostStatistic {
    todayTokenCost: number
    monthTokenCost: number
  }

  interface ImageCostStatistic {
    todayCost: number
    monthCost: number
  }

  interface CharacterStatistic {
    todayCreated: number
    total: number
  }

  const loading = ref(true)
  const loadError = ref('')
  const userStatistic = ref<UserStatistic>({ todayCreated: 0, todayActivated: 0, totalNormal: 0 })
  const kbStatistic = ref<KbStatistic>({ kbTodayCreated: 0, itemTodayCreated: 0, kbTotal: 0, itemTotal: 0 })
  const tokenCostStatistic = ref<TokenCostStatistic>({ todayTokenCost: 0, monthTokenCost: 0 })
  const imageCostStatistic = ref<ImageCostStatistic>({ todayCost: 0, monthCost: 0 })
  const characterStatistic = ref<CharacterStatistic>({ todayCreated: 0, total: 0 })

  async function loadStatistics() {
    loading.value = true
    loadError.value = ''
    try {
      const { data: resp } = await getStatistic()
      userStatistic.value = resp.userStatistic
      kbStatistic.value = resp.kbStatistic
      tokenCostStatistic.value = resp.tokenCostStatistic
      imageCostStatistic.value = resp.imageCostStatistic
      characterStatistic.value = resp.characterStatistic
    } catch (error) {
      console.error('load dashboard statistics failed', error)
      loadError.value = '统计数据暂时不可用，请稍后重试。'
    } finally {
      loading.value = false
    }
  }

  onMounted(loadStatistics)
</script>

<style lang="less" scoped>
  .console {
    max-width: 1440px;
    margin: 0 auto;
    padding: 30px 0 48px;
  }

  .console-header,
  .console-focus-heading,
  .console-focus-note,
  .console-ledger-row,
  .console-ledger-name,
  .console-ledger-values,
  .console-path-step {
    display: flex;
    align-items: center;
  }

  .console-header,
  .console-focus-heading,
  .console-focus-note,
  .console-ledger-row {
    justify-content: space-between;
  }

  .console-header {
    gap: 24px;
    margin-bottom: 28px;
  }

  .console-error {
    margin: -8px 0 18px;
  }

  h1,
  h2,
  p {
    margin-top: 0;
  }

  h1 {
    margin-bottom: 8px;
    color: var(--zhimesh-text);
    font-size: 31px;
    font-weight: 720;
    letter-spacing: -0.03em;
  }

  h2 {
    margin-bottom: 0;
    color: var(--zhimesh-text);
    font-size: 20px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }

  .console-subtitle,
  .console-section-heading p {
    margin-bottom: 0;
    color: var(--zhimesh-muted);
    line-height: 1.6;
  }

  .console-subtitle {
    max-width: 540px;
  }

  .console-overview {
    display: grid;
    grid-template-columns: minmax(280px, 0.82fr) minmax(0, 2fr);
    gap: 14px;
  }

  .console-focus-panel {
    min-height: 236px;
    padding: 24px;
    border-radius: 12px;
    color: var(--zhimesh-on-solid);
    background: var(--zhimesh-control-primary);
  }

  .console-focus-heading {
    color: rgba(255, 255, 255, 0.82);
    font-size: 13px;
    font-weight: 650;
  }

  .console-focus-value {
    min-height: 52px;
    margin-top: 30px;
    color: #ffffff;
    font-size: 46px;
    font-weight: 720;
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.04em;
    line-height: 1;
  }

  .console-focus-panel > p {
    margin: 8px 0 0;
    color: rgba(255, 255, 255, 0.72);
    font-size: 13px;
  }

  .console-focus-note {
    margin-top: 34px;
    padding-top: 14px;
    border-top: 1px solid rgba(255, 255, 255, 0.22);
    color: rgba(255, 255, 255, 0.72);
    font-size: 12px;
  }

  .console-focus-note strong {
    color: #ffffff;
    font-size: 17px;
    font-variant-numeric: tabular-nums;
  }

  .console-metric-ledger {
    overflow: hidden;
    border: 1px solid var(--zhimesh-border);
    border-radius: 12px;
    background: var(--zhimesh-glass);
  }

  .console-metric-row {
    display: grid;
    min-height: 78px;
    padding: 0 20px;
    align-items: center;
    grid-template-columns: minmax(96px, 1fr) minmax(80px, auto) minmax(110px, 0.8fr);
    gap: 18px;
    border-bottom: 1px solid var(--zhimesh-border-subtle);
    transition: background-color 160ms ease;
  }

  .console-metric-row:last-child {
    border-bottom: 0;
  }

  .console-metric-row:hover {
    background: var(--zhimesh-table-cell-hover);
  }

  .console-metric-row > span:first-child {
    color: var(--zhimesh-text);
    font-size: 14px;
    font-weight: 650;
  }

  .console-metric-value {
    color: var(--zhimesh-text);
    font-size: 29px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.03em;
    text-align: right;
  }

  .console-metric-row small {
    color: var(--zhimesh-accent-text);
    font-size: 12px;
    text-align: right;
  }

  .console-usage {
    margin-top: 14px;
    padding: 22px 24px;
    border: 1px solid var(--zhimesh-border);
    border-radius: 12px;
    background: var(--zhimesh-glass);
  }

  .console-section-heading {
    margin-bottom: 18px;
  }

  .console-section-heading p {
    max-width: 65ch;
    margin-top: 5px;
    font-size: 13px;
  }

  .console-ledger-row {
    min-height: 64px;
    padding: 10px 0;
    border-top: 1px solid var(--zhimesh-border-subtle);
  }

  .console-ledger-name {
    gap: 12px;
  }

  .console-ledger-name strong,
  .console-ledger-name small {
    display: block;
  }

  .console-ledger-name strong {
    color: var(--zhimesh-text);
    font-size: 14px;
  }

  .console-ledger-name small {
    margin-top: 4px;
    color: var(--zhimesh-muted);
    font-size: 12px;
  }

  .console-ledger-mark {
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 8px;
    color: var(--zhimesh-on-solid);
  }

  .console-ledger-mark.is-teal { background: var(--zhimesh-accent-solid); }
  .console-ledger-mark.is-amber { background: #986027; }

  .console-ledger-values {
    gap: 30px;
    color: var(--zhimesh-muted);
    font-size: 12px;
  }

  .console-ledger-values span {
    display: inline-flex;
    align-items: center;
  }

  .console-ledger-values b {
    margin-left: 7px;
    color: var(--zhimesh-text);
    font-size: 15px;
    font-variant-numeric: tabular-nums;
  }

  .console-path {
    margin-top: 34px;
    padding-top: 26px;
    border-top: 1px solid var(--zhimesh-border);
  }

  .console-path-grid {
    display: grid;
    width: min(100%, 760px);
    margin-top: 22px;
    align-items: center;
    grid-template-columns: minmax(0, 1fr) 32px minmax(0, 1fr) 32px minmax(0, 1fr);
    column-gap: 16px;
  }

  .console-path-step {
    min-width: 0;
    gap: 12px;
  }

  .console-path-icon {
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

  .console-path-step strong,
  .console-path-step small {
    display: block;
  }

  .console-path-step strong {
    color: var(--zhimesh-text);
    font-size: 14px;
  }

  .console-path-step small {
    margin-top: 4px;
    color: var(--zhimesh-muted);
    font-size: 12px;
  }

  .console-path-grid > i {
    position: relative;
    height: 1px;
    overflow: hidden;
    background: var(--zhimesh-border);
  }

  .console-path-grid > i::after {
    position: absolute;
    inset: 0;
    background: var(--zhimesh-control-primary);
    content: '';
    transform: scaleX(1);
    transform-origin: left center;
  }

  @media (prefers-reduced-motion: no-preference) {
    .console.is-ready .console-path-grid > i::after {
      animation: console-path-connect 520ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    .console.is-ready .console-path-grid > i:nth-of-type(2)::after {
      animation-delay: 220ms;
    }

    .console.is-ready .console-path-step .console-path-icon {
      animation: console-path-node 320ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    .console.is-ready .console-path-step:nth-of-type(2) .console-path-icon { animation-delay: 180ms; }
    .console.is-ready .console-path-step:nth-of-type(3) .console-path-icon { animation-delay: 400ms; }
  }

  @keyframes console-path-connect {
    from { opacity: 0.35; transform: scaleX(0); }
    to { opacity: 1; transform: scaleX(1); }
  }

  @keyframes console-path-node {
    from { opacity: 0.45; transform: scale(0.9); }
    to { opacity: 1; transform: scale(1); }
  }

  @media (max-width: 900px) {
    .console-overview { grid-template-columns: 1fr; }
  }

  @media (max-width: 640px) {
    .console { padding: 22px 0 32px; }
    .console-header { align-items: flex-start; flex-direction: column; }
    h1 { font-size: 27px; }
    .console-focus-panel, .console-usage { padding: 19px; }

    .console-metric-row {
      padding: 14px 16px;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 6px 14px;
    }

    .console-metric-row small {
      grid-column: 1 / -1;
      text-align: left;
    }

    .console-ledger-row { align-items: flex-start; flex-direction: column; gap: 12px; }
    .console-ledger-values { width: 100%; justify-content: space-between; }

    .console-path-grid {
      grid-template-columns: 1fr;
      gap: 0;
    }

    .console-path-grid > i {
      width: 1px;
      height: 18px;
      margin-left: 19px;
    }

    .console-path-grid > i::after {
      transform: scaleY(1);
      transform-origin: center top;
    }

    @media (prefers-reduced-motion: no-preference) {
      .console.is-ready .console-path-grid > i::after {
        animation-name: console-path-connect-mobile;
      }
    }
  }

  @keyframes console-path-connect-mobile {
    from { opacity: 0.35; transform: scaleY(0); }
    to { opacity: 1; transform: scaleY(1); }
  }
</style>
