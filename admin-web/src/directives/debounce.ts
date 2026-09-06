/**
 * v-debounce
 * 按钮防抖指令，可自行扩展至input
 * 接收参数：function类型
 */
import type { Directive, DirectiveBinding } from 'vue'
interface ElType extends HTMLElement {
  __handleClick__: () => any
  __callback__: () => any
  __timer__?: ReturnType<typeof setTimeout>
}
const debounce: Directive = {
  mounted(el: ElType, binding: DirectiveBinding) {
    if (typeof binding.value !== 'function') {
      throw new TypeError('callback must be a function')
    }
    el.__callback__ = binding.value
    el.__handleClick__ = function () {
      if (el.__timer__)
        clearTimeout(el.__timer__)
      el.__timer__ = setTimeout(() => {
        el.__timer__ = undefined
        el.__callback__()
      }, 500)
    }
    el.addEventListener('click', el.__handleClick__)
  },
  updated(el: ElType, binding: DirectiveBinding) {
    if (typeof binding.value === 'function')
      el.__callback__ = binding.value
  },
  beforeUnmount(el: ElType) {
    el.removeEventListener('click', el.__handleClick__)
    if (el.__timer__)
      clearTimeout(el.__timer__)
    el.__timer__ = undefined
  },
}

export default debounce
