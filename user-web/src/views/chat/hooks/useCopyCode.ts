import { onMounted, onUpdated } from 'vue'
import { copyText } from '@/utils/format'

export function useCopyCode() {
  const boundCopyButtons = new WeakSet<Element>()

  function copyCodeBlock() {
    const codeBlockWrapper = document.querySelectorAll('.code-block-wrapper')
    codeBlockWrapper.forEach((wrapper) => {
      const copyBtn = wrapper.querySelector('.code-block-header__copy')
      const codeBlock = wrapper.querySelector('.code-block-body')
      if (copyBtn && codeBlock && !boundCopyButtons.has(copyBtn)) {
        boundCopyButtons.add(copyBtn)
        copyBtn.addEventListener('click', () => {
          const text = codeBlock.textContent ?? ''
          if (navigator.clipboard?.writeText) {
            navigator.clipboard.writeText(text).catch(() => {
              copyText({ text, origin: true })
            })
          } else {
            copyText({ text, origin: true })
          }
        })
      }
    })
  }

  onMounted(() => copyCodeBlock())

  onUpdated(() => copyCodeBlock())
}
