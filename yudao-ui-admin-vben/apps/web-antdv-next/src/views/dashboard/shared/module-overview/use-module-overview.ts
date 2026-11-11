import type { ModuleOverviewData } from './types';

import { computed, onActivated, onDeactivated, ref, shallowRef, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { useObjectRequest } from '#/hooks/use-object-request';

/**
 * 模块概览数据加载。
 *
 * 与建模概览保持相同的请求语义：用户、角色、权限码或登录会话变化时立即清空旧数据并重载；
 * 只有最后一次请求可以写回；页签缓存失活时作废进行中的请求，重新激活后再加载。
 * 各数据源在加载函数内部各自降级，整体失败（加载函数抛出）才进入 failed 状态。
 */
export function useModuleOverview(load: () => Promise<ModuleOverviewData>) {
  const access = useAccessStore();
  const user = useUserStore();
  const data = shallowRef<ModuleOverviewData>();
  const loading = ref(false);
  const failed = ref(false);
  const active = ref(true);

  const scope = computed(() =>
    JSON.stringify([
      user.userInfo?.userId,
      user.userRoles,
      access.accessCodes,
      access.accessToken,
      access.refreshToken,
    ]),
  );

  function reset() {
    data.value = undefined;
    loading.value = false;
    failed.value = false;
  }

  const requests = useObjectRequest(() => scope.value, reset);

  async function refresh() {
    const current = requests.begin();
    if (!active.value || !access.accessToken) {
      reset();
      return;
    }
    loading.value = true;
    failed.value = false;
    try {
      const result = await load();
      if (current()) data.value = result;
    } catch {
      if (current()) {
        data.value = undefined;
        failed.value = true;
      }
    } finally {
      if (current()) loading.value = false;
    }
  }

  watch(scope, refresh, { flush: 'sync', immediate: true });
  onDeactivated(() => {
    active.value = false;
    requests.begin();
    reset();
  });
  onActivated(() => {
    if (!active.value) {
      active.value = true;
      void refresh();
    }
  });

  return { data, failed, loading, refresh };
}
