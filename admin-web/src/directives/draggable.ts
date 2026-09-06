/*
	需求：实现一个拖拽指令，可在父元素区域任意拖拽元素。

	思路：
		1、设置需要拖拽的元素为absolute，其父元素为relative。
		2、鼠标按下(onmousedown)时记录目标元素当前的 left 和 top 值。
		3、鼠标移动(onmousemove)时计算每次移动的横向距离和纵向距离的变化值，并改变元素的 left 和 top 值
		4、鼠标松开(onmouseup)时完成一次拖拽

	使用：在 Dom 上加上 v-draggable 即可
	<div class="dialog-model" v-draggable></div>
*/
import type { Directive } from 'vue'
interface ElType extends HTMLElement {
  parentNode: any
  __dragMouseDown__?: (event: MouseEvent) => void
  __dragMouseMove__?: (event: MouseEvent) => void
  __dragMouseUp__?: () => void
}
const draggable: Directive = {
  mounted: function (el: ElType) {
    el.style.cursor = 'move'
    el.style.position = 'absolute'
    el.__dragMouseDown__ = function (e: MouseEvent) {
      const disX = e.pageX - el.offsetLeft
      const disY = e.pageY - el.offsetTop
      el.__dragMouseMove__ = function (e: MouseEvent) {
        let x = e.pageX - disX
        let y = e.pageY - disY
        const maxX = el.parentNode.offsetWidth - el.offsetWidth
        const maxY = el.parentNode.offsetHeight - el.offsetHeight
        if (x < 0) {
          x = 0
        } else if (x > maxX) {
          x = maxX
        }

        if (y < 0) {
          y = 0
        } else if (y > maxY) {
          y = maxY
        }
        el.style.left = x + 'px'
        el.style.top = y + 'px'
      }
      el.__dragMouseUp__ = function () {
        if (el.__dragMouseMove__)
          document.removeEventListener('mousemove', el.__dragMouseMove__)
        if (el.__dragMouseUp__)
          document.removeEventListener('mouseup', el.__dragMouseUp__)
        el.__dragMouseMove__ = undefined
        el.__dragMouseUp__ = undefined
      }
      document.addEventListener('mousemove', el.__dragMouseMove__)
      document.addEventListener('mouseup', el.__dragMouseUp__)
    }
    el.addEventListener('mousedown', el.__dragMouseDown__)
  },
  beforeUnmount(el: ElType) {
    if (el.__dragMouseDown__)
      el.removeEventListener('mousedown', el.__dragMouseDown__)
    if (el.__dragMouseMove__)
      document.removeEventListener('mousemove', el.__dragMouseMove__)
    if (el.__dragMouseUp__)
      document.removeEventListener('mouseup', el.__dragMouseUp__)
    el.__dragMouseDown__ = undefined
    el.__dragMouseMove__ = undefined
    el.__dragMouseUp__ = undefined
  },
}
export default draggable
