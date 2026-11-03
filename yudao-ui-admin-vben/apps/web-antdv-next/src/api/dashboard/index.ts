import type { PageResult } from '@vben/request';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { BpmTaskApi } from '#/api/bpm/task';
import type { DataforgeAccessApi } from '#/api/dataforge/access';
import type { DataforgeDevApi } from '#/api/dataforge/dev';
import type { MetaOverview } from '#/api/governance/metaobject';
import type { ModelHomeApi } from '#/api/governance/model-home';
import type { QualityAlertApi } from '#/api/governance/quality/alert';
import type { QualityAuditLogApi } from '#/api/governance/quality/auditlog';
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { SupplyApiLogApi } from '#/api/supply/apilog';
import type { SystemNoticeApi } from '#/api/system/notice';
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { requestClient } from '#/api/request';

/**
 * 首页看板（工作台 / 数据总览）数据接口。
 *
 * 各业务域各自提供一个看板汇总接口（`/xxx/dashboard/summary` 或既有的 overview / statistics），
 * 首页按域并行拉取。任何一个域无权限或服务未启动，都只应让对应卡片降级，而不能打断整页，
 * 因此这里所有请求都开启 `silentError`，由调用方自行处理异常（见 views/dashboard/shared）。
 *
 * 响应类型尽量复用各业务模块 api 中已定义的类型，避免出现第二套定义。
 */

/** 静默 GET：失败不弹全局提示 */
function silentGet<T>(url: string, params?: Record<string, any>) {
  return requestClient.get<T>(url, { params, silentError: true });
}

export namespace DashboardApi {
  /** 治理总览的安全统计 */
  export interface SafetySummary {
    metaColumnCount?: number;
    sensitiveColumnCount?: number;
    pendingTagCount?: number;
    confirmedTagCount?: number;
    identifyRuleCount?: number;
    activeWhitelistCount?: number;
    accessCount30d?: number;
    exemptCount30d?: number;
    staticRunCount30d?: number;
    staticRunSuccessRate?: number;
    levelDistribution?: {
      color?: string;
      count?: number;
      levelId?: number | string;
      levelName?: string;
    }[];
    accessTrend?: { date?: string; exempt?: number; total?: number }[];
  }

  /** 名称计数项 */
  export interface NameCount {
    name?: string;
    count?: number;
  }

  /** 执行统计（今日） */
  export interface RunSummary {
    totalCount?: number;
    successCount?: number;
    failedCount?: number;
    /** 运行中（含等待中） */
    runningCount?: number;
  }

  /** 数据集成 / 数据研发域汇总（后端 DataforgeDashboardRespVO） */
  export interface DataforgeSummary {
    databaseCount?: number;
    databaseTypeDistribution?: NameCount[];
    accessJobCount?: number;
    accessReleasedCount?: number;
    todayAccessRun?: RunSummary;
    pushJobCount?: number;
    pushReleasedCount?: number;
    todayPushRun?: RunSummary;
    devTaskCount?: number;
    devTaskOnlineCount?: number;
    todayDevRun?: RunSummary;
  }

  /** 质量维度通过率 */
  export interface QualityDimension {
    /** COMPLETENESS / UNIQUENESS / NORMALIZATION / ACCURACY / TIMELINESS / CONSISTENCY */
    qualityDim?: string;
    checkCount?: number;
    /** 0~100 */
    passRate?: number;
  }

  /** 数据治理域汇总（后端 GovernanceDashboardRespVO） */
  export interface GovernanceSummary {
    meta?: MetaOverview;
    standard?: GovernanceStandardElementApi.Statistics;
    qualityRuleCount?: number;
    qualityRuleEnabledCount?: number;
    qualityTaskCount?: number;
    qualityReport?: QualityAuditLogApi.ReportSummary;
    qualityDimensions?: QualityDimension[];
    qualityAlert?: QualityAlertApi.Summary;
    /** 待处理的质量问题工单数（全平台，不限创建时间） */
    pendingWorkOrderCount?: number;
    /** 主数据模型总数 */
    masterModelCount?: number;
    /** 已发布的主数据模型数 */
    masterModelReleasedCount?: number;
    /** 主数据派发任务总数 */
    masterDistributeCount?: number;
    /** 已发布的主数据派发任务数 */
    masterDistributeReleasedCount?: number;
  }

  /** 工单统计（与 governance/quality/workorder.ts 中 getWorkOrderStatistics 返回一致） */
  export type WorkOrderStatistics = QualityWorkOrderApi.Statistics;

  /** 质量检核趋势点（与 getReportTrend 返回一致；当日无检核时 passRate 为 null） */
  export interface QualityTrendPoint {
    checkCount: number;
    date: string;
    passRate: null | number;
  }
}

// ==================== 个人相关（system / bpm） ====================

/** 当前登录用户（含部门、角色、岗位） */
export function getDashboardUserProfile() {
  return silentGet<SystemUserProfileApi.UserProfileRespVO>(
    '/system/user/profile/get',
  );
}

/** 我的待办审批（BPM） */
export function getDashboardTodoTaskPage(pageSize = 5) {
  return silentGet<PageResult<BpmTaskApi.Task>>('/bpm/task/todo-page', {
    pageNo: 1,
    pageSize,
  });
}

/** 我的待处理质量工单 */
export function getDashboardMyWorkOrderList() {
  return silentGet<QualityWorkOrderApi.WorkOrder[]>(
    '/governance/quality-work-order/my-todo',
  );
}

/** 未读站内信数量 */
export function getDashboardUnreadNotifyCount() {
  return silentGet<number>('/system/notify-message/get-unread-count');
}

/** 系统公告（仅开启状态） */
export function getDashboardNoticePage(pageSize = 5) {
  return silentGet<PageResult<SystemNoticeApi.Notice>>('/system/notice/page', {
    pageNo: 1,
    pageSize,
    status: 0,
  });
}

// ==================== 数据集成 / 数据研发（dataforge） ====================

/** 数据集成 / 数据研发域汇总（数据源 / 同步任务 / 开发任务 / 今日执行） */
export function getDashboardDataforgeSummary() {
  return silentGet<DashboardApi.DataforgeSummary>(
    '/dataforge/dashboard/summary',
  );
}

/** 最近的开发任务执行实例（按编号倒序） */
export function getDashboardDevRecordPage(pageSize = 5) {
  return silentGet<PageResult<DataforgeDevApi.Record>>(
    '/dataforge/dev-record/page',
    { pageNo: 1, pageSize },
  );
}

/** 最近的数据同步执行记录（按编号倒序） */
export function getDashboardAccessTaskPage(pageSize = 5) {
  return silentGet<PageResult<DataforgeAccessApi.AccessTask>>(
    '/dataforge/access/task/page',
    { pageNo: 1, pageSize },
  );
}

// ==================== 数据治理（governance） ====================

/** 数据治理域汇总（元数据 / 数据标准 / 质量规则、检核、告警、工单） */
export function getDashboardGovernanceSummary() {
  return silentGet<DashboardApi.GovernanceSummary>(
    '/governance/dashboard/summary',
  );
}

/** 质量检核趋势（近 N 天按日统计检测次数与通过率） */
export function getDashboardQualityTrend(days?: number) {
  return silentGet<DashboardApi.QualityTrendPoint[]>(
    '/governance/quality-audit-log/trend',
    days ? { days } : undefined,
  );
}

/** 质量工单统计（处理趋势 / 状态占比 / 责任人处理量；统计范围为近 N 天创建的工单） */
export function getDashboardWorkOrderStatistics(days?: number) {
  return silentGet<DashboardApi.WorkOrderStatistics>(
    '/governance/quality-work-order/statistics',
    days ? { days } : undefined,
  );
}

/** 治理总览安全统计（敏感字段、分级分布、访问趋势等） */
export function getDashboardSafetyOverview() {
  return silentGet<DashboardApi.SafetySummary>(
    '/governance/dashboard/safety-summary',
  );
}

// ==================== 数据建模（modeling） ====================

/** 建模总览（数据域 / 业务域 / 模型 / 已发布 / 已物化） */
export function getDashboardModelOverview() {
  return silentGet<ModelHomeApi.ModelOverview>('/modeling/overview/get');
}

// ==================== 数据资产（asset） ====================

/** 资产聚合（总数、按类目 / 主题域 / 分层分布） */
export function getDashboardAssetFacets() {
  return silentGet<DataAssetApi.Facets>('/asset/data-asset/facets');
}

// ==================== 数据服务（supply） ====================

/** API 调用统计（API 总数 / 在线数 / 调用量 / 成功率 / 耗时 / 趋势 / 排行） */
export function getDashboardApiLogStatistics(days?: number) {
  return silentGet<SupplyApiLogApi.Statistics>('/supply/api-log/statistics', {
    days,
  });
}
