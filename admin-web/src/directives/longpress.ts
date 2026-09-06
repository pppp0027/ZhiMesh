/**
 * v-longpress
 * 长按指令，长按时触发事件
 */
import type { Directive, DirectiveBinding } from 'vue'

interface LongPressElement extends HTMLElement {
  __longpressCallback__?: (event: MouseEvent | TouchEvent) => void
  __longpressCleanup__?: () => void
}

const directive: Directive = {
  mounted(el: LongPressElement, binding: DirectiveBinding) {
    if (typeof binding.value !== 'function') {
      throw new TypeError('callback must be a function')
    }
    el.__longpressCallback__ = binding.value
    // 定义变量
    let pressTimer: any = null
    // 创建计时器（ 2秒后执行函数 ）
    const start = (e: any) => {
      if (e instanceof MouseEvent && e.button !== 0)
        return
      if (pressTimer === null) {
        pressTimer = setTimeout(() => {
          handler(e)
        }, 1000)
      }
    }
    // 取消计时器
    const cancel = () => {
      if (pressTimer !== null) {
        clearTimeout(pressTimer)
        pressTimer = null
      }
    }
    // 运行函数
    const handler = (e: MouseEvent | TouchEvent) => {
      el.__longpressCallback__?.(e)
    }
    // 添加事件监听器
    el.addEventListener('mousedown', start)
    el.addEventListener('touchstart', start)
    // 取消计时器
    el.addEventListener('click', cancel)
    el.addEventListener('mouseout', cancel)
    el.addEventListener('touchend', cancel)
    el.addEventListener('touchcancel', cancel)
    el.__longpressCleanup__ = () => {
      cancel()
      el.removeEventListener('mousedown', start)
      el.removeEventListener('touchstart', start)
      el.removeEventListener('click', cancel)
      el.removeEventListener('mouseout', cancel)
      el.removeEventListener('touchend', cancel)
      el.removeEventListener('touchcancel', cancel)
    }
  },
  updated(el: LongPressElement, binding: DirectiveBinding) {
    if (typeof binding.value === 'function')
      el.__longpressCallback__ = binding.value
  },
  beforeUnmount(el: LongPressElement) {
    el.__longpressCleanup__?.()
    el.__longpressCallback__ = undefined
    el.__longpressCleanup__ = undefined
  },
}

export default directive
