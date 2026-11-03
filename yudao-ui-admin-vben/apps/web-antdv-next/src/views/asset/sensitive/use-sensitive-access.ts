import { computed } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';

import { parseResourceId } from '#/utils/resource-id';

export function useSensitiveAccess() {
  const access = useAccessStore();
  const user = useUserStore();
  const { hasAccessByCodes } = useAccess();
  const owner = computed(() =>
    JSON.stringify([
      access.accessToken,
      access.refreshToken,
      access.loginExpired,
      access.accessCodes,
      user.userInfo?.userId,
      user.userRoles,
    ]),
  );
  const authenticated = computed(
    () =>
      !!access.accessToken &&
      !access.loginExpired &&
      !!parseResourceId(user.userInfo?.userId),
  );
  function can(action: 'create' | 'delete' | 'query' | 'update') {
    return (
      authenticated.value &&
      hasAccessByCodes([`asset:sensitive-list:${action}`])
    );
  }
  return { owner, can };
}
