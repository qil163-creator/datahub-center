import type { RouteRecordRaw } from 'vue-router';

import { useAccessStore, useUserStore } from '@vben/stores';

import { parseResourceId } from '#/utils/resource-id';
import {
  DEPARTMENT_ASSET_DETAIL_ROUTE,
  DEPARTMENT_ASSET_PERMISSION,
} from '#/views/market/apply/data';

const routes: RouteRecordRaw[] = [
  {
    path: '/market/apply/detail/:id',
    name: DEPARTMENT_ASSET_DETAIL_ROUTE,
    component: () => import('#/views/market/apply/detail/index.vue'),
    meta: {
      title: '申请资产详情',
      hideInMenu: true,
      keepAlive: false,
      activePath: '/asset/my-assets',
    },
    beforeEnter(to) {
      if (!parseResourceId(to.params.id))
        return { path: '/404', replace: true };
      const access = useAccessStore();
      const user = useUserStore();
      if (
        !access.accessToken ||
        access.loginExpired ||
        !parseResourceId(user.userInfo?.userId) ||
        !access.accessCodes.includes(DEPARTMENT_ASSET_PERMISSION)
      )
        return { path: '/403', replace: true };
      return true;
    },
  },
];
export default routes;
