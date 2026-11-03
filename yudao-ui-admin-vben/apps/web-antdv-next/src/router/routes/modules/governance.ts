import type { RouteRecordRaw } from 'vue-router';

import { useAccessStore, useUserStore } from '@vben/stores';

import { parseResourceId } from '#/utils/resource-id';

const routes: RouteRecordRaw[] = [
  {
    path: '/governance',
    name: 'GovernanceCenter',
    redirect: '/model/standard-data-element/code-table',
    meta: {
      title: '数据治理',
      hideInMenu: true,
    },
    children: [
      {
        path: 'rule/detail/:id',
        name: 'GovernanceSafetyRuleDetail',
        component: () => import('#/views/governance/rule/detail/index.vue'),
        meta: {
          title: '脱敏规则详情',
          activePath: '/govern/safety/rule',
          hideInMenu: true,
        },
        beforeEnter(to) {
          if (!parseResourceId(to.params.id))
            return { path: '/404', replace: true };
          const access = useAccessStore();
          if (
            !access.accessToken ||
            access.loginExpired ||
            !parseResourceId(useUserStore().userInfo?.userId) ||
            !access.accessCodes.includes('governance:rule:query')
          )
            return { path: '/403', replace: true };
          return true;
        },
      },
      {
        path: 'standard/doc-register/detail/:id',
        name: 'GovernanceStandardDocRegisterDetail',
        component: () => import('#/views/governance/doc-register/detail.vue'),
        meta: {
          title: '标准详情',
          activePath: '/model/standard/doc-register',
          hideInMenu: true,
        },
      },
      {
        path: 'metacollecttask/detail/:id',
        name: 'GovernanceMetaCollectTaskDetail',
        component: () =>
          import('#/views/governance/metacollecttask/detail.vue'),
        meta: {
          title: '采集任务详情',
          activePath: '/govern/metadata/metacollecttask',
          hideInMenu: true,
        },
      },
      {
        path: 'metaobject/detail/:id',
        name: 'GovernanceMetaTableDetail',
        component: () => import('#/views/governance/metaobject/detail.vue'),
        meta: {
          title: '最新元数据详情',
          activePath: '/govern/metadata/metaobject',
          hideInMenu: true,
        },
      },
      {
        path: 'metaobject/edit/:id',
        name: 'GovernanceMetaTableEdit',
        component: () => import('#/views/governance/metaobject/edit.vue'),
        beforeEnter: (to) =>
          /^[1-9]\d*$/.test(String(to.params.id ?? '')) ||
          '/govern/metadata/metaobject',
        meta: {
          title: '修改最新元数据（库表）',
          activePath: '/govern/metadata/metaobject',
          hideInMenu: true,
        },
      },
      {
        path: 'metaobject/compare/:id',
        name: 'GovernanceMetaTableCompare',
        component: () => import('#/views/governance/metaobject/compare.vue'),
        beforeEnter: (to) =>
          /^[1-9]\d*$/.test(String(to.params.id ?? '')) ||
          '/govern/metadata/metaobject',
        meta: {
          title: '元数据对比',
          activePath: '/govern/metadata/metaobject',
          hideInMenu: true,
        },
      },
      {
        path: 'metapublished/detail/:id',
        name: 'GovernanceMetaFinalizedDetail',
        component: () => import('#/views/governance/metaobject/detail.vue'),
        props: { finalized: true },
        meta: {
          title: '定版元数据详情',
          activePath: '/govern/metadata/metapublished',
          hideInMenu: true,
        },
      },
      {
        path: 'metafile/detail/:id',
        name: 'GovernanceMetaFileDetail',
        component: () => import('#/views/governance/metafile/detail.vue'),
        meta: {
          title: '最新元数据（文件）详情',
          activePath: '/govern/metadata/metaobject',
          hideInMenu: true,
        },
      },
      {
        path: 'metafile/edit/:id',
        name: 'GovernanceMetaFileEdit',
        component: () => import('#/views/governance/metafile/edit.vue'),
        meta: {
          title: '修改最新元数据（文件）',
          activePath: '/govern/metadata/metaobject',
          hideInMenu: true,
        },
      },
      {
        path: 'standardcodeset/detail/:id',
        name: 'GovernanceStandardCodeSetDetail',
        component: () =>
          import('#/views/governance/standardcodeset/detail/index.vue'),
        meta: {
          title: '标准码表详情',
          activePath: '/model/standard-data-element/code-table',
          hideInMenu: true,
        },
      },
      {
        path: 'standardelement/detail/:id',
        name: 'GovernanceStandardElementDetail',
        component: () =>
          import('#/views/governance/standardelement/detail/index.vue'),
        meta: {
          title: '标准数据元详情',
          activePath: '/model/standard-data-element/data-element',
          hideInMenu: true,
        },
      },
    ],
  },
];

export default routes;
