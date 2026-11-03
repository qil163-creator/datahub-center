import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/modeling',
    name: 'ModelingCenter',
    redirect: '/model/models/logic-model',
    meta: {
      title: '数据建模',
      hideInMenu: true,
    },
    children: [
      {
        path: 'model-materialized/detail/:modelId',
        name: 'ModelingMaterializedDetail',
        component: () =>
          import('#/views/modeling/model-materialized/detail.vue'),
        meta: {
          title: '发布模型详情',
          hideInMenu: true,
          keepAlive: false,
          activePath: '/model/models/model-materialized',
        },
      },
      {
        path: 'data-domain/detail/:id',
        name: 'ModelingDataDomainDetail',
        component: () => import('#/views/modeling/datadomain/detail.vue'),
        meta: {
          title: '数据板块详情',
          hideInMenu: true,
          keepAlive: false,
          activePath: '/model/warehouse-plan/data-domain',
        },
      },
      {
        path: 'subject-domain/detail/:id',
        name: 'ModelingSubjectDomainDetail',
        component: () => import('#/views/modeling/subjectdomain/detail.vue'),
        meta: {
          title: '主题域详情',
          hideInMenu: true,
          keepAlive: false,
          activePath: '/model/warehouse-plan/subject-domain',
        },
      },
      {
        path: '/model/metric/atomic/detail/:id',
        name: 'ModelingAtomicIndicatorDetail',
        component: () => import('#/views/modeling/indicator-model/detail.vue'),
        meta: {
          title: '原子指标详情',
          hideInMenu: true,
          activePath: '/model/indicator-model/atomic',
        },
      },
      {
        path: '/model/metric/derived/detail/:id',
        name: 'ModelingDerivedIndicatorDetail',
        component: () => import('#/views/modeling/indicator-model/detail.vue'),
        meta: {
          title: '派生指标详情',
          hideInMenu: true,
          activePath: '/model/indicator-model/derived',
        },
      },
      {
        path: '/model/metric/condition/detail/:id',
        name: 'ModelingBusinessConditionDetail',
        component: () =>
          import('#/views/modeling/business-condition/detail.vue'),
        meta: {
          title: '业务限定详情',
          hideInMenu: true,
          activePath: '/model/indicator-model/condition',
        },
      },
      {
        path: '/model/metric/condition/edit',
        name: 'ModelingBusinessConditionForm',
        component: () => import('#/views/modeling/business-condition/form.vue'),
        meta: {
          title: '维护业务限定',
          hideInMenu: true,
          activePath: '/model/indicator-model/condition',
        },
      },
      {
        path: 'logic-table/edit/:kind(source|dimension|fact|summary|application)',
        name: 'ModelingLogicTableEdit',
        component: () => import('#/views/modeling/logictable/editor-entry.vue'),
        meta: {
          title: '维护逻辑表',
          hideInMenu: true,
          keepAlive: false,
          activePath: '/model/models',
        },
        beforeEnter(to) {
          const kind = String(to.params.kind);
          const menu = (
            {
              source: 'source',
              dimension: 'dimension',
              fact: 'fact',
              summary: 'summary',
              application: 'application',
            } as Record<string, string>
          )[kind];
          to.meta.activePath = menu
            ? `/model/models/${menu}-table`
            : '/model/models';
        },
      },
      {
        path: 'table-model/detail/:id',
        name: 'ModelingTableModelDetail',
        component: () => import('#/views/modeling/tablemodel/detail.vue'),
        meta: {
          title: '逻辑模型详情',
          hideInMenu: true,
          activePath: '/model/models',
        },
      },
      {
        path: 'table-model/er',
        name: 'ModelingTableModelEr',
        component: () => import('#/views/modeling/tablemodel/er.vue'),
        meta: {
          title: '模型 ER 关系图',
          hideInMenu: true,
          activePath: '/model/models',
        },
      },
      {
        path: 'logic-model/detail/:id',
        name: 'ModelingLogicModelDetail',
        redirect: (to) => ({
          name: 'ModelingTableModelDetail',
          params: to.params,
          query: to.query,
        }),
        meta: {
          title: '逻辑模型详情',
          activePath: '/model/models',
        },
      },
      {
        path: 'logic-model/er',
        name: 'ModelingLogicModelEr',
        redirect: (to) => ({ name: 'ModelingTableModelEr', query: to.query }),
        meta: {
          title: '逻辑模型 ER 图',
          activePath: '/model/models',
        },
      },
    ],
  },
];

export default routes;
