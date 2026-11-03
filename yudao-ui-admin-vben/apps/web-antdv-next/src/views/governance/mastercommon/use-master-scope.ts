import { computed, onBeforeUnmount, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

/** 请求按登录会话、用户与页面生命周期隔离。 */
export function useMasterScope(onChange?: () => void) {
  const access = useAccessStore();
  const user = useUserStore();
  const owner = computed(() =>
    JSON.stringify([
      access.accessToken,
      access.refreshToken,
      user.userInfo?.userId,
    ]),
  );
  const lanes = new Map<string, number>();
  let active = true;
  let generation = 0;
  function invalidate() {
    ++generation;
    lanes.clear();
  }
  watch(owner, () => {
    invalidate();
    onChange?.();
  });
  onBeforeUnmount(() => {
    active = false;
    invalidate();
  });
  function begin(lane = 'list') {
    const revision = (lanes.get(lane) ?? 0) + 1;
    const identity = owner.value;
    const lifetime = generation;
    lanes.set(lane, revision);
    return () =>
      active &&
      lifetime === generation &&
      identity === owner.value &&
      lanes.get(lane) === revision;
  }
  return { begin, invalidate, owner };
}
