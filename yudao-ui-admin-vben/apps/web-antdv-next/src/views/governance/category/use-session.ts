import { computed } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';

import { parseResourceId } from '#/utils/resource-id';

export function useCategorySession() {
  const access = useAccessStore();
  const user = useUserStore();
  const { hasAccessByCodes } = useAccess();
  const identity = computed(() =>
    JSON.stringify([
      access.accessToken,
      access.refreshToken,
      access.loginExpired,
      access.accessCodes,
      user.userInfo,
      user.userRoles,
    ]),
  );
  const signedIn = computed(
    () =>
      !!access.accessToken &&
      !access.loginExpired &&
      !!parseResourceId(user.userInfo?.userId),
  );
  function can(action: string, domain = 'category') {
    return (
      signedIn.value && hasAccessByCodes([`governance:${domain}:${action}`])
    );
  }
  return { identity, can };
}
