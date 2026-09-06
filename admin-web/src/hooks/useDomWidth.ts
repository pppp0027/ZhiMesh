import { ref, onMounted, onUnmounted } from 'vue'
import { debounce } from 'lodash-es'

/**
 * description: 获取页面宽度
 */

export function useDomWidth() {
  const domWidth = ref(window.innerWidth)

  function resize() {
    domWidth.value = document.body.clientWidth
  }

  const debouncedResize = debounce(resize, 80)

  onMounted(() => {
    window.addEventListener('resize', debouncedResize)
  })
  onUnmounted(() => {
    window.removeEventListener('resize', debouncedResize)
    debouncedResize.cancel()
  })

  return domWidth
}
