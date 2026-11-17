import { onActivated, onDeactivated, onMounted } from 'vue';

/** 首次进入和从其他业务页切回时，重新读取目录。 */
export function useTreeReload(load: () => Promise<void>) {
  let deactivated = false;
  onMounted(load);
  onDeactivated(() => {
    deactivated = true;
  });
  onActivated(() => {
    if (deactivated) {
      deactivated = false;
      void load();
    }
  });
}
