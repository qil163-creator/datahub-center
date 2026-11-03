import { computed, onActivated, onDeactivated, ref } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

/** 登录、权限、路由对象和缓存页面的生命周期共同约束请求回写。 */
export function useBatchScope(identity: () => unknown = () => '') {
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
