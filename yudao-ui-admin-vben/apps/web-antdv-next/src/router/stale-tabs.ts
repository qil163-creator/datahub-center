import type { Router } from 'vue-router';

import type { TabDefinition } from '@vben/types';

import { getTabKey, useTabbarStore } from '@vben/stores';

import { fallbackNotFoundRoute } from './routes/core';

/**
 * 判断标签页是否已失效：指向的页面不存在（404）、已改为重定向，或被设置为不显示标签。
 * 解析异常时保守保留，避免误删。
 */
export function isStaleTab(router: Router, tab: TabDefinition): boolean {
  try {
    const record = router.resolve(tab.fullPath || tab.path).matched.at(-1);
    return (
      !record ||
      record.name === fallbackNotFoundRoute.name ||
      !!record.redirect ||
      !!record.meta?.hideInTab
    );
  } catch {
    return false;
  }
}

/**
 * 清理失效标签页。
 * 标签页（含固定标签页）持久化在 sessionStorage，页面下线或地址改为重定向后，旧标签仍会被恢复，
 * 且语言 key 删除后会直接显示原始 key。需在动态路由生成之后调用，否则会误判为 404。
 */
export async function removeStaleTabs(router: Router) {
  const tabbarStore = useTabbarStore();
  const keys = tabbarStore.tabs
    .filter((tab) => isStaleTab(router, tab))
    .map((tab) => tab.key ?? getTabKey(tab));
  if (keys.length > 0) {
    await tabbarStore._bulkCloseByKeys(keys);
  }
}
