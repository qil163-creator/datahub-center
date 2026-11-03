import { computed, onActivated, onDeactivated, ref } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

export function useFullSyncScope(identity: () => unknown = () => '') {
  const access = useAccessStore();
  const user = useUserStore();
  const active = ref(true);
  onActivated(() => {
    active.value = true;
  });
  onDeactivated(() => {
    active.value = false;
  });
  const scope = computed(() =>
    JSON.stringify([
      active.value,
      access.accessToken,
      access.refreshToken,
      access.accessCodes,
      user.userInfo?.userId,
      user.userRoles,
      identity(),
    ]),
  );
  return { active, scope };
}
