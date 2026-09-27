<script lang="ts" setup>
import { computed, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import mdKatex from '@traptitech/markdown-it-katex'
import mila from 'markdown-it-link-attributes'
import markdownItFootnote from 'markdown-it-footnote'
import hljs from 'highlight.js'
import { SvgIcon } from '@/components/common'
import { t } from '@/locales'
interface Props {
  inversion?: boolean
  error?: boolean
  text?: string
  loading?: boolean
  asRawText?: boolean
  /** loading && !text 时等待状态条的状态键（chat.state.*），缺省问题分析中 */
  statusKey?: string
}

const props = defineProps<Props>()

const textRef = ref<HTMLElement>()

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
// 脚注式来源标注：正文句末 [^n] 渲染为可点击上标，文末聚合成参考来源区块（锚点与回链插件自带）
mdi.use(markdownItFootnote)

const wrapClass = computed(() => {
  return [
    'text-wrap',
    'min-w-[20px]',
    props.inversion ? 'message-request' : 'message-reply',
    { 'text-red-500': props.error },
  ]
})

// CommonMark flanking 规则：闭合 ** 前一字符为 CJK/全角标点（如 ：）时，须后接空白或标点才算
// right-flanking；紧跟汉字等非空白则星号原样显示。此处对这类行内加粗在闭合 ** 后插入发丝空格
// U+200A（Unicode 空白，视觉几乎不可见）使闭合合法；前一字非标点的常规加粗不受影响。
const CJK_PUNCT_BOLD_RE = /\*\*([^*\n]*[\u3000-\u303F\uFF01-\uFF5E\u2018-\u201D\u2026\u2014])\*\*(?=[^\s*])/g

function fixCjkBoldClose(value: string): string {
  return value.replace(CJK_PUNCT_BOLD_RE, '**$1**\u200A')
}

const text = computed(() => {
  const value = props.text ?? ''
  if (!props.asRawText)
    return mdi.render(fixCjkBoldClose(value))
  return value
})

function highlightBlock(str: string, lang?: string) {
  return `<pre class="code-block-wrapper"><div class="code-block-header"><span class="code-block-header__lang">${lang}</span><span class="code-block-header__copy">${t('chat.copyCode')}</span></div><code class="hljs code-block-body ${lang}">${str}</code></pre>`
}

defineExpose({ textRef })
</script>

<template>
  <div class="text-black" :class="wrapClass">
    <div v-if="loading && !text" class="message-thinking-state">
      <span class="message-thinking-icon"><SvgIcon icon="line-md:loading-twotone-loop" /></span>
      <span>{{ t(`chat.state.${statusKey || 'question_analysing'}`) }}</span>
    </div>
    <div v-else ref="textRef" class="leading-relaxed break-words">
      <div v-if="!inversion" class="flex items-end">
        <div v-if="!asRawText" class="w-full markdown-body" v-html="text" />
        <div v-else class="w-full whitespace-pre-wrap" v-text="text" />
        <span v-if="loading" class="dark:text-white w-[4px] h-[20px] block animate-blink" />
      </div>
      <div v-else class="whitespace-pre-wrap" v-text="text" />
    </div>
  </div>
</template>

<style lang="less">
@import url(./style.less);
</style>
