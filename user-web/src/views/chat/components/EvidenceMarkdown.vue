<script lang="ts" setup>
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import mdKatex from '@traptitech/markdown-it-katex'
import mila from 'markdown-it-link-attributes'
import hljs from 'highlight.js'
import { t } from '@/locales'

interface Props {
  /** 检索证据原文（可能来自 Markdown/TXT/PDF 等任意文档切块） | Evidence excerpt, possibly Markdown source */
  text?: string
}

const props = defineProps<Props>()

// 与聊天消息 Text.vue 保持同一套渲染配置（katex/链接属性/代码高亮），
// 但不带聊天气泡样式，供引用/记忆/关键词等证据弹窗复用。
const mdi = new MarkdownIt({
  linkify: true,
  highlight(code, language) {
    const validLang = !!(language && hljs.getLanguage(language))
    if (validLang) {
      const lang = language ?? ''
      return highlightBlock(hljs.highlight(code, { language: lang }).value, lang)
    }
    return highlightBlock(hljs.highlightAuto(code).value, '')
  },
})

mdi.use(mila, { attrs: { target: '_blank', rel: 'noopener' } })
mdi.use(mdKatex, { blockClass: 'katexmath-block rounded-md p-[10px]', errorColor: ' #cc0000' })

const html = computed(() => mdi.render(props.text ?? ''))

function highlightBlock(str: string, lang?: string) {
  return `<pre class="code-block-wrapper"><div class="code-block-header"><span class="code-block-header__lang">${lang}</span><span class="code-block-header__copy">${t('chat.copyCode')}</span></div><code class="hljs code-block-body ${lang}">${str}</code></pre>`
}
</script>

<template>
  <div class="markdown-body evidence-markdown" v-html="html" />
</template>

<style scoped lang="less">
// 证据摘录在弹窗折叠面板内展示：宽度贴边即可，字号比聊天正文略小一档
.evidence-markdown {
  max-width: 100%;
  font-size: 13.5px;
}
</style>
