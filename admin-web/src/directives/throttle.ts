/*
  需求：防止按钮在短时间内被多次点击，使用节流函数限制规定时间内只能点击一次。

  思路：
    1、第一次点击，立即调用方法并禁用按钮，等延迟结束再次激活按钮
    2、将需要触发的方法绑定在指令上
  
  使用：给 Dom 加上 v-throttle 及回调函数即可
  <button v-throttle="debounceClick">节流提交</button>
*/
import type { Directive, DirectiveBinding } from 'vue'
interface ElType extends HTMLElement {
  __handleClick__: () => any
  __callback__: () => any
  __timer__?: ReturnType<typeof setTimeout>
  disabled: boolean
}
const throttle: Directive = {
  mounted(el: ElType, binding: DirectiveBinding) {
    if (typeof binding.value !== 'function') {
      throw new TypeError('callback must be a function')
    }
    el.__callback__ = binding.value
    el.__handleClick__ = function () {
      if (el.__timer__)
        clearTimeout(el.__timer__)
      if (!el.disabled) {
        el.disabled = true
        el.__callback__()
        el.__timer__ = setTimeout(() => {
          el.disabled = false
          el.__timer__ = undefined
        }, 1000)
      }
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
    el.disabled = false
  },
}

export default throttle
