import type { RouteRecordRaw } from 'vue-router';

const activePath = '/develop/integrate/batch-file';
const routes: RouteRecordRaw[] = [
  {
    path: `${activePath}/create`,
    name: 'DataforgeBatchFileCreate',
    component: () => import('#/views/dataforge/batchfile/wizard.vue'),
    meta: { title: '新增批量文件入库任务', hideInMenu: true, activePath },
  },
  {
    path: `${activePath}/edit/:id`,
    name: 'DataforgeBatchFileEdit',
    component: () => import('#/views/dataforge/batchfile/wizard.vue'),
    meta: { title: '配置批量文件入库任务', hideInMenu: true, activePath },
  },
  {
    path: `${activePath}/detail/:id`,
    name: 'DataforgeBatchFileDetail',
    component: () => import('#/views/dataforge/batchfile/detail.vue'),
    meta: {
      title: '批量文件入库任务详情',
      hideInMenu: true,
      activePath,
      fullPathKey: false,
    },
  },
];
export default routes;
