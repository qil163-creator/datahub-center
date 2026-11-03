import type { RouteRecordRaw } from 'vue-router';
const routes: RouteRecordRaw[] = [
  {
    path: '/dataforge/integration/designer/:id',
    name: 'DataforgeIntegrationDesigner',
    component: () => import('#/views/dataforge/integration/designer.vue'),
    meta: {
      title: '集成任务配置',
      hideInMenu: true,
      activePath: '/develop/integrate/offline-development',
      fullPathKey: false,
    },
  },
  {
    path: '/dataforge/integration/realtime/designer/:id',
    name: 'DataforgeRealtimeIntegrationDesigner',
    component: () => import('#/views/dataforge/integration/designer.vue'),
    meta: {
      title: '实时集成任务配置',
      hideInMenu: true,
      activePath: '/develop/integrate/realtime-development',
      fullPathKey: false,
    },
  },
];
export default routes;
