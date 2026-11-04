import type { RouteLocationRaw } from 'vue-router';

import type { MarketApi } from '#/api/asset/market';

import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';

import { message } from 'antdv-next';

import { parseResourceId, sameResourceId } from '#/utils/resource-id';

export const MY_ASSETS_ROUTE_NAME = 'AssetMarketMyAssets';

/** 申请编号与 Java Long 契约一致，全程保留十进制字符串。 */
export function parseAuthorizedApplyId(value: unknown): string | undefined {
  const id = parseResourceId(value);
  if (!id || id.length > 19 || (id.length === 19 && id > '9223372036854775807'))
    return;
  return id;
}

export function authorizedAssetLocation(
  row: Pick<MarketApi.Apply, 'applyUserId' | 'id' | 'status'>,
  currentUserId: unknown,
  permitted: boolean,
): RouteLocationRaw | undefined {
  const id = parseAuthorizedApplyId(row.id);
  if (
    !permitted ||
    !id ||
    ![2, 5, 6].includes(row.status ?? -1) ||
    !sameResourceId(row.applyUserId, currentUserId)
  )
    return;
  return { name: MY_ASSETS_ROUTE_NAME, query: { applyId: id } };
}

/** 通过动态菜单名称定位页面，保留管理员调整过的菜单层级。 */
export function useAuthorizedAssetsNavigation() {
  const router = useRouter();
  const access = useAccessStore();
  const user = useUserStore();
  const { hasAccessByCodes } = useAccess();
  function destination(row: MarketApi.Apply) {
    return authorizedAssetLocation(
      row,
      user.userInfo?.userId,
      !!access.accessToken &&
        !access.loginExpired &&
        hasAccessByCodes(['asset:market:my-assets:query']),
    );
  }
  return {
    canOpen: (row: MarketApi.Apply) => !!destination(row),
    async open(row: MarketApi.Apply) {
      const target = destination(row);
      if (!target) return false;
      if (!router.hasRoute(MY_ASSETS_ROUTE_NAME)) {
        message.warning('我的资产菜单尚未加载，请刷新页面或联系管理员。');
        return false;
      }
      try {
        const failure = await router.push(target);
        if (!failure) return true;
      } catch {
        // 路由加载失败保留原申请详情，由用户选择重试。
      }
      message.warning('未能打开我的资产，请稍后重试。');
      return false;
    },
  };
}
