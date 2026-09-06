import type { DialogOptions, DialogProviderInst, DialogReactive } from 'naive-ui'

type DeleteDialogOptions = Omit<DialogOptions, 'onPositiveClick'> & {
  onPositiveClick: () => unknown | Promise<unknown>
}

/**
 * Keeps destructive confirmations consistent and prevents duplicate requests
 * while the confirmed action is still running.
 */
export function openDeleteDialog(dialog: DialogProviderInst, options: DeleteDialogOptions) {
  const onPositiveClick = options.onPositiveClick
  const instance: DialogReactive = dialog.error({
    ...options,
    positiveButtonProps: {
      type: 'error',
      ...options.positiveButtonProps,
    },
    async onPositiveClick() {
      if (instance.loading)
        return false
      instance.loading = true
      try {
        return await onPositiveClick()
      } catch (error) {
        console.error('Destructive action failed', error)
        return false
      } finally {
        instance.loading = false
      }
    },
  })

  return instance
}
