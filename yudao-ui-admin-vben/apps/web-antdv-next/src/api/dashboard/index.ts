import type { PageResult } from '@vben/request';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { MarketApi } from '#/api/asset/market';
import type { MarketCheckApi } from '#/api/asset/market/check';
import type { AssetTagApi } from '#/api/asset/tag';
import type { BpmTaskApi } from '#/api/bpm/task';
import type { DataforgeAccessApi } from '#/api/dataforge/access';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { DataforgeDataFileApi } from '#/api/dataforge/datafile';
import type { DataforgeDevApi } from '#/api/dataforge/dev';
import type { IntegrationApi } from '#/api/dataforge/integration';
import type { JobApi } from '#/api/dataforge/job';
import type { OpsInstanceApi } from '#/api/dataforge/ops';
import type { FoundationSystemSourceApi } from '#/api/dataforge/systemsource';
import type { GovernanceAuditRuleApi } from '#/api/governance/auditrule';
import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';
import type { GovernanceMetaCollectInstanceApi } from '#/api/governance/metacollectinstance';
import type { MetaOverview } from '#/api/governance/metaobject';
import type { ModelHomeApi } from '#/api/governance/model-home';
import type { QualityAlertApi } from '#/api/governance/quality/alert';
import type { QualityAuditLogApi } from '#/api/governance/quality/auditlog';
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { GovernanceStandardTermApi } from '#/api/governance/standardterm';
import type { SupplyApiLogApi } from '#/api/supply/apilog';
import type { SupplyAppApi } from '#/api/supply/app';
import type { SystemNoticeApi } from '#/api/system/notice';
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { requestClient } from '#/api/request';

/**
 * 首页工作台及各业务域总览页的看板数据接口。
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

// ==================== 模块概览（基础管理 / 数据研发 / 数据治理 / 数据资产 / 数据服务） ====================
// 模块概览只读取各列表接口的 total 或最近若干条记录，过滤条件与对应列表页一致，
// 权限与数据范围仍由后端各列表接口执行；无权限时由页面降级为“暂无权限或服务不可用”。

/** 静默分页：pageSize 默认 1，仅用于读取 total */
function silentPage<T>(
  url: string,
  params: Record<string, unknown> = {},
  pageSize = 1,
) {
  return silentGet<PageResult<T>>(url, { ...params, pageNo: 1, pageSize });
}

/** 数据连接分页（按编号倒序，用于总数与最近接入） */
export function getDashboardDatabasePage(pageSize = 1) {
  return silentPage<FoundationDatabaseApi.Database>(
    '/dataforge/database/page',
    {},
    pageSize,
  );
}

/** 连接器（数据库类型）数量；status 为 0 时仅统计启用 */
export function getDashboardDatabaseTypePage(status?: number) {
  return silentPage<FoundationDatabaseTypeApi.DatabaseType>(
    '/dataforge/database-type/page',
    { status },
  );
}

/** 来源系统分页；status 为 0 时仅统计启用 */
export function getDashboardSystemSourcePage(pageSize = 1, status?: number) {
  return silentPage<FoundationSystemSourceApi.SystemSource>(
    '/dataforge/system-source/page',
    { status },
    pageSize,
  );
}

/** 文件管理可浏览的文件服务连接（FTP / HDFS） */
export function getDashboardFileConnections() {
  return silentGet<DataforgeDataFileApi.Connection[]>(
    '/dataforge/file-resource/connections',
  );
}

/** 稽查规则质量维度 */
export function getDashboardAuditRuleDimensionList() {
  return silentGet<GovernanceAuditRuleApi.Dimension[]>(
    '/governance/audit-rule/dimension-list',
  );
}

/** 稽查规则数量；可按启用状态与质量维度过滤 */
export function getDashboardAuditRulePage(params?: {
  dimensionId?: GovernanceAuditRuleApi.Id;
  status?: number;
}) {
  return silentPage<GovernanceAuditRuleApi.AuditRule>(
    '/governance/audit-rule/page',
    params,
  );
}

/** 清洗规则数量；status 为 0 时仅统计启用 */
export function getDashboardCleanRulePage(status?: number) {
  return silentPage<GovernanceCleanRuleApi.CleanRule>(
    '/governance/clean-rule/page',
    { status },
  );
}

/** 离线 / 实时开发任务数量 */
export function getDashboardIntegrationPage(mode: IntegrationApi.Mode) {
  return silentPage<IntegrationApi.Task>('/dataforge/integration/page', {
    mode,
  });
}

/** 离线 / 实时开发今日执行统计 */
export function getDashboardIntegrationStatistics(mode: IntegrationApi.Mode) {
  return silentGet<IntegrationApi.Statistics>(
    '/dataforge/integration/statistics',
    { mode },
  );
}

/** 运维实例今日执行统计（整库同步 / 作业管理） */
export function getDashboardOpsStatistics(kind: OpsInstanceApi.Kind) {
  return silentGet<OpsInstanceApi.Statistics>(
    '/dataforge/ops-instance/statistics',
    { kind },
  );
}

/** 作业数量；status 为 1 时仅统计已上线 */
export function getDashboardJobPage(status?: number) {
  return silentPage<JobApi.Job>('/dataforge/job/page', { status });
}

/** 最近的运维实例（按开始时间倒序），kind 区分集成 / 作业等实例类型 */
export function getDashboardOpsInstancePage(
  kind: OpsInstanceApi.Kind,
  pageSize = 5,
) {
  return silentPage<OpsInstanceApi.Instance>(
    '/dataforge/ops-instance/page',
    { kind, sortField: 'startTime', sortOrder: 'desc' },
    pageSize,
  );
}

/** 最近的元数据采集实例 */
export function getDashboardMetaCollectInstancePage(pageSize = 5) {
  return silentPage<GovernanceMetaCollectInstanceApi.CollectInstance>(
    '/governance/meta-collect-instance/page',
    {},
    pageSize,
  );
}

/** 最近的质量告警 */
export function getDashboardQualityAlertPage(pageSize = 5) {
  return silentPage<QualityAlertApi.Alert>(
    '/governance/quality-alert/page',
    {},
    pageSize,
  );
}

/** 最近的质量问题工单 */
export function getDashboardQualityWorkOrderPage(pageSize = 5) {
  return silentPage<QualityWorkOrderApi.WorkOrder>(
    '/governance/quality-work-order/page',
    {},
    pageSize,
  );
}

/** 当前用户可见集市资产统计（计数无损） */
export function getDashboardMartStatistics() {
  return silentGet<MarketApi.MartStatistics>('/asset/market/mart/statistics');
}

/** 资产标签数量 */
export function getDashboardAssetTagPage() {
  return silentPage<AssetTagApi.Tag>('/asset/tag/page');
}

/** 业务术语数量 */
export function getDashboardStandardTermPage() {
  return silentPage<GovernanceStandardTermApi.StandardTerm>(
    '/governance/standard-term/page',
  );
}

/** 最近的资产申请审批工单（按申请时间倒序） */
export function getDashboardMarketCheckPage(pageSize = 5) {
  return silentPage<MarketCheckApi.Ticket>(
    '/asset/market/check/page',
    { sortBy: 'applyTime', sortOrder: 'desc' },
    pageSize,
  );
}

/** 数据需求数量 */
export function getDashboardMarketDemandPage() {
  return silentPage<MarketApi.Demand>('/asset/market/demand/page');
}

/** 接入应用数量 */
export function getDashboardSupplyAppPage() {
  return silentPage<SupplyAppApi.App>('/supply/app/page');
}

/** 最近的 API 调用记录 */
export function getDashboardApiLogPage(pageSize = 5) {
  return silentPage<SupplyApiLogApi.ApiLog>(
    '/supply/api-log/page',
    {},
    pageSize,
  );
}
