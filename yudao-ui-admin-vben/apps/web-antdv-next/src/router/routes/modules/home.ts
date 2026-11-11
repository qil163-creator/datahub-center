import type { RouteRecordRaw } from 'vue-router';

import { $t } from '#/locales';

/** 首页地址，与 preferences.ts 中的 defaultHomePath 保持一致 */
const HOME_PATH = '/home';

/**
 * 已下线的旧地址：概览分组、工作台、数据总览。
 * 统一跳到首页，兼容旧书签以及浏览器偏好缓存里的旧首页地址。
 * 注意：首页是固定标签页，不能用 alias（alias 记录也会被识别成固定标签页，导致出现重复标签）。
 */
const LEGACY_PATHS = [
  '/dashboard',
  '/dashboard/workspace',
  '/workspace',
  '/dashboard/analytics',
  '/analytics',
];

const routes: RouteRecordRaw[] = [
  {
    name: 'Home',
    path: HOME_PATH,
    component: () => import('#/views/dashboard/workspace/index.vue'),
    meta: {
      affixTab: true,
      hideInMenu: true,
      icon: 'lucide:house',
      title: $t('page.dashboard.home'),
    },
  },
  ...LEGACY_PATHS.map<RouteRecordRaw>((path) => ({
    name: `LegacyHomeRedirect:${path}`,
    path,
    redirect: HOME_PATH,
    meta: {
      hideInBreadcrumb: true,
      hideInMenu: true,
      hideInTab: true,
      title: $t('page.dashboard.home'),
    },
  })),
  {
    name: 'Profile',
    path: '/profile',
    component: () => import('#/views/_core/profile/index.vue'),
    meta: {
      icon: 'ant-design:profile-outlined',
      title: $t('ui.widgets.profile'),
      hideInMenu: true,
    },
  },
];

export default routes;
