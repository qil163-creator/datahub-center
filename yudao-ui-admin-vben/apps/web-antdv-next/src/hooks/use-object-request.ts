import { computed, onBeforeUnmount, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import { createObjectRequestGuard } from './object-request-guard';

/** 同时隔离请求次序、登录会话、对象切换及组件卸载。 */
export function useObjectRequest(
  identity: () => unknown = () => '',
  reset?: () => void,
) {
  const access = useAccessStore();
  const scope = computed(
    () => `${access.accessToken}/${access.refreshToken}/${String(identity())}`,
  );
  const guard = createObjectRequestGuard(() => scope.value);
  watch(
    scope,
    () => {
      guard.begin();
      reset?.();
    },
    { flush: 'sync' },
  );
  onBeforeUnmount(() => guard.dispose());
  return guard;
}
