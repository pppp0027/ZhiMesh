<template>
  <div class="logo">
    <img :src="websiteConfig.logo" alt="ZhiMesh logo" :class="{ 'mr-2': !collapsed }" />
    <div v-show="!collapsed" class="brand-copy">
      <h2 class="title">{{ websiteConfig.title }}</h2>
      <span class="subtitle">知脉</span>
    </div>
  </div>
</template>

<script lang="ts">
  import { websiteConfig } from '@/config/website.config'
  export default {
    name: 'Index',
    props: {
      collapsed: {
        type: Boolean,
      },
    },
    data() {
      return {
        websiteConfig,
      }
    },
  }
</script>

<style lang="less" scoped>
  .logo {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 76px;
    overflow: hidden;
    white-space: nowrap;

    img {
      width: auto;
      height: 42px;
    }

    .brand-copy {
      display: flex;
      flex-direction: column;
      line-height: 1.1;
    }

    .title {
      margin: 0;
      color: var(--zhimesh-text);
      font-size: 18px;
      font-weight: 750;
      letter-spacing: -0.02em;
    }

    .subtitle {
      margin-top: 5px;
      color: var(--zhimesh-muted);
      font-size: 9px;
      font-weight: 700;
      letter-spacing: 0.16em;
    }
  }
</style>
