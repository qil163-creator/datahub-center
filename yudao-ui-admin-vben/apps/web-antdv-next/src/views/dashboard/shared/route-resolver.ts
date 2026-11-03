import type { Router } from 'vue-router';

import { useRouter } from 'vue-router';

/**
 * 看板跳转目标。
 *
 * 首页上的入口不硬编码 URL：菜单路径来自后端 system_menu，各环境（以及后续调整菜单结构）
 * 都可能不同。这里按“路由 name（对应菜单的 component_name）→ 菜单标题”的顺序，在当前用户
 * 已加载的路由表中解析出真实路径；解析不到说明当前用户没有该菜单权限，入口应直接隐藏。
 */
export interface RouteTarget {
  /** 路由 name（后端菜单 component_name / 前端静态路由 name），按顺序尝试 */
  names?: string[];
  /** 兜底：按菜单标题匹配叶子路由 */
  titles?: string[];
}

function isLeafRoute(components: unknown) {
  return !!components && Object.keys(components as object).length > 0;
}

function isNavigablePath(path?: string) {
  return !!path && path.startsWith('/') && !path.includes(':');
}

/** 在路由表中解析目标路径；找不到返回 undefined */
export function resolveRoutePath(
  router: Router,
  target?: RouteTarget,
): string | undefined {
  if (!target) {
    return undefined;
  }
  const routes = router.getRoutes();
  for (const name of target.names ?? []) {
    const matched = routes.find((route) => route.name === name);
    if (matched && isNavigablePath(matched.path)) {
      return matched.path;
    }
  }
  for (const title of target.titles ?? []) {
    const matched = routes.find(
      (route) =>
        route.meta?.title === title &&
        isLeafRoute(route.components) &&
        isNavigablePath(route.path),
    );
    if (matched) {
      return matched.path;
    }
  }
  return undefined;
}

/** 看板导航：解析 + 跳转 */
export function useDashboardNav() {
  const router = useRouter();

  function resolve(target?: RouteTarget) {
    return resolveRoutePath(router, target);
  }

  function navTo(target?: RouteTarget | string) {
    const path = typeof target === 'string' ? target : resolve(target);
    if (!path) {
      return;
    }
    router.push(path).catch((error) => {
      console.error('Navigation failed:', error);
    });
  }

  return { navTo, resolve };
}

/**
 * 常用页面的跳转目标（name 取自 sql/postgresql 中各菜单脚本的 component_name，
 * 标题作为老菜单没有 component_name 时的兜底）。
 */
export const DASHBOARD_TARGETS = {
  /** 数据源 */
  database: { names: ['Database'], titles: ['数据库信息', '数据源'] },
  /** 数据同步任务 */
  access: { names: ['DataforgeAccess'], titles: ['数据同步'] },
  /** 数据推送配置 */
  dataPushConfig: {
    names: ['DataforgeDataPushConfig'],
    titles: ['推送配置'],
  },
  /** 数据推送任务 */
  dataPushTask: {
    names: ['DataforgeDataPushTask'],
    titles: ['推送任务'],
  },
  /** 数据同步执行记录（同页面内 Tab，回到任务列表） */
  accessTask: { names: ['DataforgeAccess'], titles: ['数据同步'] },
  /** 开发工作台 */
  devWorkbench: { names: ['DataforgeDevWorkbench'], titles: ['开发工作台'] },
  /** 开发任务实例 */
  devInstance: { names: ['DataforgeDevInstance'], titles: ['任务实例'] },
  /** 可视化 ETL 流程 */
  etlPipeline: { names: ['DataforgeEtlPipeline'], titles: ['生产流程'] },
  /** 元数据管理 */
  metaObject: {
    names: ['GovernanceMetaObject'],
    titles: ['最新元数据', '元数据管理', '元数据对象'],
  },
  /** 元数据采集任务 */
  metaCollectTask: {
    names: ['GovernanceMetaCollectTask'],
    titles: ['采集任务'],
  },
  /** 标准数据元 */
  standardElement: { names: ['StandardElement'], titles: ['标准数据元管理'] },
  /** 数据字典（码表） */
  standardCodeSet: { names: ['StandardCodeSet'], titles: ['数据字典'] },
  /** 质量报告 */
  qualityReport: {
    names: ['GovernanceQualityReport'],
    titles: ['质量报告'],
  },
  /** 质量检查对象 */
  qualityCheckObject: {
    names: ['GovernanceQualityCheckObject'],
    titles: ['检查对象'],
  },
  /** 质量任务 */
  qualityTask: { names: ['GovernanceQualityTask'], titles: ['质量任务'] },
  /** 质量规则 */
  qualityRule: { names: ['GovernanceQualityRule'], titles: ['质量规则'] },
  /** 质量问题数据 */
  qualityIssueData: {
    names: ['GovernanceQualityIssueData'],
    titles: ['问题数据'],
  },
  /** 元数据采集失败清单 */
  metaCollectInstance: {
    names: ['GovernanceMetaCollectInstance'],
    titles: ['采集实例'],
  },
  /** 质量问题工单 */
  qualityWorkOrder: {
    names: ['GovernanceQualityWorkOrder'],
    titles: ['问题工单'],
  },
  /** 主数据模型 */
  masterModel: { names: ['GovernanceMasterData'], titles: ['主数据模型'] },
  /** 主数据派发 */
  masterDistribute: {
    names: ['GovernanceMasterDistribute'],
    titles: ['主数据派发'],
  },
  /** 逻辑模型 */
  logicModel: {
    names: ['ModelingTableModel', 'ModelingLogicModel'],
    titles: ['模型管理', '逻辑模型'],
  },
  /** 资产地图 */
  dataAsset: { names: ['AssetMarketMap'], titles: ['资产地图'] },
  /** 数据查询 */
  dataQuery: { names: ['AssetDataQuery'], titles: ['数据查询'] },
  /** API 服务管理 */
  dataApi: { names: ['SupplyDataApi'], titles: ['API 管理'] },
  /** API 调用记录 */
  apiLog: { names: ['SupplyApiLog'], titles: ['调用记录'] },
  /** 应用管理 */
  supplyApp: { names: ['SupplyApp'], titles: ['应用管理'] },
  /** 大屏设计器 */
  goView: { names: ['GoView'], titles: ['大屏设计器'] },
  /** 报表设计器 */
  jimuReport: { names: ['JimuReport'], titles: ['报表设计器'] },
  /** BPM 待办任务 */
  bpmTodo: { names: ['BpmTodoTask'], titles: ['待办任务'] },
  /** 我的站内信（前端静态路由） */
  myNotify: { names: ['MyNotifyMessage'], titles: ['我的站内信'] },
  /** 通知公告管理 */
  notice: { names: ['SystemNotice'], titles: ['通知公告'] },
} satisfies Record<string, RouteTarget>;
