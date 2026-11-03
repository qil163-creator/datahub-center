import type { Ref } from 'vue';

import { onBeforeUnmount, watch } from 'vue';

import { Modal } from 'antdv-next';

/** Static dialogs are outside the page tree; close their old-object content on scope changes. */
export function useBatchConfirm(scope: Ref<string>) {
  const dialogs = new Set<ReturnType<typeof Modal.confirm>>();
  function clear() {
    for (const dialog of dialogs) dialog.destroy();
    dialogs.clear();
  }
  watch(scope, clear, { flush: 'sync' });
  onBeforeUnmount(clear);
  return (options: Parameters<typeof Modal.confirm>[0]) => {
    const dialog: ReturnType<typeof Modal.confirm> = Modal.confirm({
      ...options,
      afterClose() {
        if (dialog) dialogs.delete(dialog);
        options.afterClose?.();
      },
    });
    if (dialog) dialogs.add(dialog);
    return dialog;
  };
}
