import type { PageParam, PageResult } from '@vben/request';

import type { DataAssetApi } from '#/api/asset/dataasset';

import { consumptionHeaders } from '#/api/asset/consumption';
import { requestClient } from '#/api/request';

export namespace MarketApi {
  export type Id = number | string;
  export type DeliveryResolution =
    | 'APPLIED'
    | 'PENDING'
    | 'REJECTED'
    | 'REVOKED'
    | 'SUPERSEDED'
    | 'SUSPENDED'
    | 'UNKNOWN';

  export type DeliveryAction = 'GRANT' | 'REVOKE' | 'SUSPEND' | 'UNKNOWN';

  /** 脱敏交付账本；默认 LocalDateTime 在线协议中为毫秒时间戳。 */
  export interface DeliveryOperation {
    id: Id;
    operationId: string;
    applyId: Id;
    /** 经验证的冻结请求意图，不代表执行结果；旧服务可能缺失。 */
    action?: DeliveryAction | null;
    /** 技术操作类型；历史 REVOKE 同时承载暂停与撤销，不可据此推断 action。 */
    opType: string;
    sourceRevision: Id | null;
    status: string;
    resolutionStatus: string;
    protocolVersion?: number;
    attemptCount?: number;
    resultRef?: null | string;
    lastError?: null | string;
    updateTime?: null | number;
  }

  export type DeliveryApprovalStatus =
    | 'CONFLICT'
    | 'MATCHED'
    | 'MISSING'
    | 'NOT_APPLICABLE'
    | 'UNVERIFIABLE';

  /** 仅原操作证据分类，不是迁移决定，也不包含当前可访问范围。 */
  export interface DeliveryEvidence {
    applyId: Id;
    operationId: string;
    sourceRevision: Id | null;
    classification:
      | 'FROZEN_COMMAND_CONFLICT'
      | 'FROZEN_COMMAND_UNAVAILABLE'
      | 'LEGACY_REVIEW_REQUIRED'
      | 'LOCAL_BINDING_CONFLICT'
      | 'LOCAL_STATE_CHANGED'
      | 'ORIGINAL_APPLIED'
      | 'ORIGINAL_REJECTED'
      | 'ORIGINAL_STALE'
      | 'RECEIPT_CONFLICT'
      | 'RECEIPT_NOT_FOUND'
      | 'RECEIPT_UNAVAILABLE'
      | 'RECEIPT_UNKNOWN';
    originalAction?: null | string;
    frozenFieldCount?: null | number;
    providerOutcome?: null | string;
    /** 独立于回执分类；兼容旧响应缺失及新增状态，展示时未知值必须降级。 */
    approvalStatus?: null | string;
  }

  export type DeliveryReviewDecision = 'KEEP_PENDING' | 'REAPPLY_REQUIRED';
  /** 实际处置：REVOKE 真实收回授权；CONFIRM_ORIGINAL 仅在证据完整时把原操作账本确认为成功。 */
  export type DeliveryDisposalAction = 'CONFIRM_ORIGINAL' | 'REVOKE';
  export interface DeliveryDisposalRequest {
    requestId: string;
    operationId: string;
    action: DeliveryDisposalAction;
    reason: string;
    basis: string;
    /** 必须绑定此前记录的核查决定；服务端复验其属于同一原操作且证据快照与当前一致。 */
    basisReviewRequestId: string;
  }
  export interface DeliveryDisposalBatchItem {
    requestId: string;
    operationId: string;
    action: DeliveryDisposalAction;
    reason: string;
    basis: string;
    basisReviewRequestId: string;
  }
  export interface DeliveryDisposalBatchRequest {
    /** 1..20 条；同批 operationId 不重复，逐条独立成败。 */
    items: DeliveryDisposalBatchItem[];
  }
  export interface DeliveryDisposalBatchItemResult {
    requestId: string;
    operationId: string;
    success: boolean;
    /** 成功条目的处置回执；失败条目为空。 */
    result?: DeliveryReview | null;
    errorCode?: null | string;
    errorMsg?: null | string;
  }
  export interface DeliveryDisposalBatchResponse {
    items: DeliveryDisposalBatchItemResult[];
  }
  export interface DeliveryReviewRequest {
    requestId: string;
    operationId: string;
    decision: DeliveryReviewDecision;
    reason: string;
    basis: string;
  }
  export interface DeliveryRedeliverRequest {
    /** 小写 UUID；重试保留同一编号，服务端按操作编号幂等，不会重复重发。 */
    requestId: string;
    operationId: string;
    /** 1..500 字的重投说明。 */
    note: string;
    /** 必须绑定此前记录的核查决定；服务端复验其属于同一原操作。 */
    basisReviewRequestId: string;
  }
  export interface DeliveryReview {
    id: Id;
    requestId: string;
    operationId: string;
    applyId: Id;
    sourceRevision: Id | null;
    /** 待办结论或实际处置；未知值展示时原样回显。 */
    decision: DeliveryDisposalAction | DeliveryReviewDecision;
    classification: DeliveryEvidence['classification'];
    /** DECISION 仅记录待办；DISPOSAL 已改变授权或交付账本。旧响应缺失按 DECISION 处理。 */
    kind?: 'DECISION' | 'DISPOSAL' | null;
    /** 仅收回处置：新冻结的收回操作号。 */
    resultingOperationId?: null | string;
    /** 记录时的原批准凭证核对结果，不是当前授权状态。 */
    approvalStatus?: null | string;
    actorUserId: Id;
    actorDeptId?: Id | null;
    reason: string;
    basis: string;
    occurredAt: number;
  }
  export interface DeliveryReviewPage {
    items: DeliveryReview[];
    nextId?: Id | null;
    hasMore: boolean;
  }

  export interface MartPageReq extends PageParam {
    keyword?: string;
    assetTypes?: number[];
    marketCategoryId?: Id;
    authorizedOnly?: boolean;
    favoriteOnly?: boolean;
    sort?: 'FAVORITE' | 'LATEST' | 'POPULAR';
  }

  export interface MartStatistics {
    totalAssetCount: Id;
    authorizedAssetCount: Id;
    favoriteAssetCount: Id;
    applyingAssetCount: Id;
  }

  export interface MartListing {
    assetId: Id;
    marketCategoryId?: Id;
    name?: string;
    description?: string;
    frequency?: string;
    deliveryTypes?: number[];
    expectedRevision?: Id;
    revision?: Id;
  }

  export interface MartPublishReq extends MartListing {
    marketCategoryId: Id;
    name: string;
    frequency: string;
    deliveryTypes: number[];
    expectedRevision: Id;
    expectedListingRevision: Id;
  }

  export interface MartTopic {
    id: Id;
    name: string;
    assetCount: Id;
  }

  export interface Resource extends DataAssetApi.DataAsset {
    sourceRecordId?: string;
    fileName?: string;
    fileType?: string;
    serviceIdentifier?: string;
    primaryDeliveryType?: number;
    displayScore?: string;
    marketCategoryId?: Id;
    marketCategoryName?: string;
    marketName?: string;
    marketDescription?: string;
    frequency?: string;
    deliveryTypes?: number[];
    favorite?: boolean;
    commentAvg?: number;
    commentCount?: number;
    applyCount?: number;
    myApplyStatus?: number;
    myApplyId?: Id;
    authorized?: boolean;
    usable?: boolean;
  }

  export interface ConsumerDetail {
    asset?: Resource;
    columns?: DataAssetApi.MetadataColumn[];
    qualityTasks?: unknown[];
  }

  export interface EligibleApi {
    id?: Id;
    name?: string;
    path?: string;
    version?: string;
    apiType?: string;
    configType?: string;
  }

  export interface Apply {
    id?: Id;
    applyNo?: string;
    title?: string;
    assetId?: Id;
    assetName?: string;
    assetCode?: string;
    assetType?: number;
    apiId?: Id;
    appId?: Id;
    demandId?: Id;
    demandNo?: string;
    applyDeptId?: Id;
    applyDeptName?: string;
    applyUserId?: Id;
    applyUserName?: string;
    phone?: string;
    email?: string;
    purpose?: string;
    fieldScope?: string;
    applyNote?: string;
    applyStartTime?: number | string;
    applyEndTime?: number | string;
    applyUseType?: number;
    applyUseTimes?: number;
    status?: number;
    ifAuth?: number;
    effective?: boolean;
    usable?: boolean;
    approvalResult?: number;
    techProvisionStatus?: number;
    authStatus?: number;
    /** 申请内容版本；编辑、提交、撤销、补正、审批时作为客户端所见版本回传。 */
    revision?: null | number;
    /** 交付来源版本（Long，无损）；开通/停用、暂停/恢复、收回时回传。 */
    sourceRevision?: Id | null;
    dutyMatter?: string;
    legalBasis?: string;
    supplementNote?: string;
    receivedTime?: number | string;
    replyDeadline?: number | string;
    deliveryDeadline?: number | string;
    deliveryStatus?: number;
    /** 交付结果来自账本；无需交付时为空，不能代替实际 usable。 */
    deliveryResolution?: DeliveryResolution;
    deliveryOperationId?: null | string;
    /** 续期所对应的原申请编号；续期是新申请，原批准不被延长 */
    renewalOfApplyId?: Id;
    sourceGrantId?: string;
    firstUsedTime?: number | string;
    paused?: number;
    checkUserId?: Id;
    checkUserName?: string;
    checkTime?: string;
    checkNote?: string;
    remark?: string;
    createTime?: string;
    processes?: CheckProcess[];
    submit?: boolean;
    datasourceId?: Id;
    tableName?: string;
    defaultCheckerUserId?: Id;
    defaultCheckerName?: string;
    ownerReviewer?: boolean;
  }

  export interface App {
    id?: Id;
    name?: string;
    appType?: string;
    appKey?: string;
    secret?: string;
    publicFlag?: number;
    description?: string;
    remark?: string;
    createTime?: string;
  }

  export interface CheckProcess {
    id?: Id;
    action?: string;
    fromStatus?: number;
    toStatus?: number;
    operatorName?: string;
    note?: string;
    createTime?: string;
  }

  export interface Comment {
    id?: Id;
    assetId?: Id;
    applyId?: Id;
    commentStr?: string;
    level?: number;
    replyStr?: string;
    replyUserName?: string;
    replyTime?: string;
    creatorName?: string;
    createTime?: string;
    commentKind?: number;
    problemType?: string;
    impactScope?: string;
    foundTime?: number | string;
    workOrderId?: Id;
    workOrderStatus?: string;
    idempotentKey?: string;
  }

  export interface Demand {
    id?: Id;
    demandNo?: string;
    title?: string;
    demandType?: number;
    description?: string;
    purpose?: string;
    expectedFields?: string;
    expectedUpdateFreq?: string;
    expectedDelivery?: number;
    expectFinishTime?: number | string;
    urgency?: number;
    applyDeptId?: Id;
    applyDeptName?: string;
    applyUserId?: Id;
    applyUserName?: string;
    phone?: string;
    email?: string;
    status?: number;
    publishTime?: number | string;
    closeReason?: string;
    acceptanceStatus?: number;
    acceptedApplyId?: Id;
    acceptedTime?: number | string;
    remark?: string;
    createTime?: number | string;
    matchCount?: number;
    replyCount?: number;
    matches?: DemandMatch[];
    replies?: DemandReply[];
    publish?: boolean;
  }

  export interface DemandMatch {
    id?: Id;
    demandId?: Id;
    assetId?: Id;
    assetName?: string;
    assetCode?: string;
    assetType?: number;
    matchType?: number;
    matchScore?: number;
    matchNote?: string;
    status?: number;
    applyId?: Id;
    matcherName?: string;
    confirmUserName?: string;
    confirmTime?: number | string;
    confirmNote?: string;
    createTime?: number | string;
  }

  export interface DemandReply {
    id?: Id;
    demandId?: Id;
    replyType?: number;
    content?: string;
    replyDeptName?: string;
    replyUserName?: string;
    createTime?: string;
  }

  export interface Endpoint {
    assetId?: Id;
    apiId?: Id;
    name?: string;
  }
}

export function getMarketResourcePage(
  params: PageParam & {
    assetType?: number;
    categoryId?: number;
    keyword?: string;
    layer?: string;
    subjectDomainId?: number;
  },
) {
  return requestClient.get<PageResult<MarketApi.Resource>>(
    '/asset/market/resource/page',
    { params },
  );
}

export function getMarketResourceDetail(id: MarketApi.Id) {
  return requestClient.get<MarketApi.ConsumerDetail>(
    `/asset/market/resource/get?id=${id}`,
  );
}

export function getMarketResourceFacets() {
  return requestClient.get<DataAssetApi.Facets>(
    '/asset/market/resource/facets',
  );
}

export function getMarketApplyPage(
  params: PageParam & {
    applyNo?: string;
    assetId?: MarketApi.Id;
    assetType?: number;
    ifAuth?: number;
    ifOwner?: number;
    ifSelf?: number;
    keyword?: string;
    status?: number;
  },
) {
  return requestClient.get<PageResult<MarketApi.Apply>>(
    '/asset/market/apply/page',
    { params },
  );
}

export function getMarketApply(id: MarketApi.Id) {
  return requestClient.get<MarketApi.Apply>(`/asset/market/apply/get?id=${id}`);
}

export function createMarketApply(data: MarketApi.Apply) {
  return requestClient.post<MarketApi.Id>('/asset/market/apply/create', data);
}

/** expectedRevision 为页面所见内容版本；缺省时服务端保持原有 CAS，兼容旧调用。 */
export function updateMarketApply(
  data: MarketApi.Apply & { expectedRevision?: null | number },
) {
  return requestClient.put('/asset/market/apply/update', data);
}

export function submitMarketApply(
  id: MarketApi.Id,
  expectedRevision?: null | number,
) {
  return requestClient.put('/asset/market/apply/submit', undefined, {
    params: versionParams(id, { expectedRevision }),
  });
}

/** 仅在调用方提供时发送所见版本；ID 与 Long 版本保持原值，不做数值转换。 */
function versionParams(
  id: MarketApi.Id,
  versions: Record<string, MarketApi.Id | null | number | undefined>,
) {
  const params: Record<string, MarketApi.Id | number> = { id };
  for (const [key, value] of Object.entries(versions)) {
    if (value !== undefined && value !== null) params[key] = value;
  }
  return params;
}

export function getMarketEligibleApis(assetId: MarketApi.Id) {
  return requestClient.get<MarketApi.EligibleApi[]>(
    '/asset/market/apply/eligible-apis',
    { params: { assetId } },
  );
}

export function deleteMarketApply(id: MarketApi.Id) {
  return requestClient.delete(`/asset/market/apply/delete?id=${id}`);
}

export function cancelMarketApply(
  id: MarketApi.Id,
  expectedRevision?: null | number,
) {
  return requestClient.put('/asset/market/apply/cancel', undefined, {
    params: versionParams(id, { expectedRevision }),
  });
}

export function checkMarketApply(data: {
  checkNote?: string;
  expectedRevision?: null | number;
  id: MarketApi.Id;
  status: number;
}) {
  return requestClient.put('/asset/market/apply/check', data);
}

export function authMarketApply(
  id: MarketApi.Id,
  auth: number,
  expectedSourceRevision?: MarketApi.Id | null,
) {
  return requestClient.put('/asset/market/apply/auth', undefined, {
    params: { ...versionParams(id, { expectedSourceRevision }), auth },
  });
}

export function supplementMarketApply(data: {
  expectedRevision?: null | number;
  id: MarketApi.Id;
  note?: string;
}) {
  return requestClient.put('/asset/market/apply/supplement', data);
}

export function resubmitMarketApply(
  id: MarketApi.Id,
  expectedRevision?: null | number,
) {
  return requestClient.put('/asset/market/apply/resubmit', undefined, {
    params: versionParams(id, { expectedRevision }),
  });
}

/**
 * 续期申请：创建一条关联原申请的新申请，必须重新审批。
 * 原批准在其原有期限内保持不变，不会被原地延长。
 * operationId 在同一次表单提交及其重试内保持固定，重复提交只产生一个续期申请。
 */
export function createRenewalRequest(req: {
  applyEndTime: number | string;
  id: MarketApi.Id;
  operationId: string;
}) {
  return requestClient.post<MarketApi.Id>(
    '/asset/market/apply/renewal-request',
    {
      applyEndTime: req.applyEndTime,
      id: req.id,
      operationId: req.operationId,
    },
  );
}

export function pauseMarketApply(data: {
  expectedSourceRevision?: MarketApi.Id | null;
  id: MarketApi.Id;
  note?: string;
}) {
  return requestClient.put('/asset/market/apply/pause', data);
}

export function resumeMarketApply(
  id: MarketApi.Id,
  expectedSourceRevision?: MarketApi.Id | null,
) {
  return requestClient.put('/asset/market/apply/resume', undefined, {
    params: versionParams(id, { expectedSourceRevision }),
  });
}

export function retryMarketDeliveries() {
  return requestClient.put<number>('/asset/market/apply/retry-delivery');
}

export function getMarketDeliveryOperation(operationId: string) {
  return requestClient.get<MarketApi.DeliveryOperation>(
    '/asset/market/apply/delivery-operation',
    { params: { operationId } },
  );
}

export function getMarketDeliveryEvidence(operationId: string) {
  return requestClient.get<MarketApi.DeliveryEvidence>(
    '/asset/market/apply/delivery-evidence',
    { params: { operationId } },
  );
}

export function recordMarketDeliveryReview(
  data: MarketApi.DeliveryReviewRequest,
) {
  return requestClient.post<MarketApi.DeliveryReview>(
    '/asset/market/apply/delivery-review',
    {
      requestId: data.requestId,
      operationId: data.operationId,
      decision: data.decision,
      reason: data.reason,
      basis: data.basis,
    },
  );
}

/** 实际处置；服务端重新取证并锁内复验，失败不改授权。只发送白名单字段。 */
export function recordMarketDeliveryDisposal(
  data: MarketApi.DeliveryDisposalRequest,
) {
  return requestClient.post<MarketApi.DeliveryReview>(
    '/asset/market/apply/delivery-disposal',
    {
      requestId: data.requestId,
      operationId: data.operationId,
      action: data.action,
      reason: data.reason,
      basis: data.basis,
      basisReviewRequestId: data.basisReviewRequestId,
    },
  );
}

/**
 * 批量处置：逐条独立执行并逐条返回结果，部分成功不整体回滚。
 * 只发送白名单字段，不附带操作者或前端推测的证据。
 */
export function recordMarketDeliveryDisposalBatch(
  data: MarketApi.DeliveryDisposalBatchRequest,
) {
  return requestClient.post<MarketApi.DeliveryDisposalBatchResponse>(
    '/asset/market/apply/delivery-disposal-batch',
    {
      items: data.items.map((item) => ({
        requestId: item.requestId,
        operationId: item.operationId,
        action: item.action,
        reason: item.reason,
        basis: item.basis,
        basisReviewRequestId: item.basisReviewRequestId,
      })),
    },
  );
}

export function getMarketDeliveryReviews(
  operationId: string,
  afterId: MarketApi.Id = 0,
) {
  return requestClient.get<MarketApi.DeliveryReviewPage>(
    '/asset/market/apply/delivery-reviews',
    {
      params: { operationId, afterId },
    },
  );
}

/**
 * 交付受控重投：仅限冻结命令完整匹配且动作是撤销/暂停的 MANUAL/UNKNOWN
 * 原操作，按原 operationId 重发一次，不重建命令；GRANT 或条件不满足服务端拒绝。
 * 只发送白名单字段，不附带操作者或前端推测的证据。
 */
export function recordMarketDeliveryRedeliver(
  data: MarketApi.DeliveryRedeliverRequest,
) {
  return requestClient.post<MarketApi.DeliveryOperation>(
    '/asset/market/apply/redeliver-delivery',
    {
      requestId: data.requestId,
      operationId: data.operationId,
      note: data.note,
      basisReviewRequestId: data.basisReviewRequestId,
    },
  );
}

/** 按原身份只读查询 Supply 回执并记录核查审计，不发送授权/撤销命令。 */
export function recheckMarketDelivery(data: {
  note: string;
  operationId: string;
}) {
  return requestClient.put<MarketApi.DeliveryOperation>(
    '/asset/market/apply/recheck-delivery',
    { note: data.note, operationId: data.operationId },
  );
}

export function revokeMarketApply(
  id: MarketApi.Id,
  note?: string,
  expectedSourceRevision?: MarketApi.Id | null,
) {
  const params: Record<string, MarketApi.Id | number | string> = versionParams(
    id,
    { expectedSourceRevision },
  );
  if (note) params.note = note;
  return requestClient.put('/asset/market/apply/revoke', undefined, {
    params,
  });
}

export function getMarketCommentPage(
  params: PageParam & { assetId?: MarketApi.Id },
) {
  return requestClient.get<PageResult<MarketApi.Comment>>(
    '/asset/market/comment/page',
    { params },
  );
}

export function createMarketComment(data: MarketApi.Comment) {
  return requestClient.post<MarketApi.Id>('/asset/market/comment/create', data);
}

export function replyMarketComment(data: {
  id: MarketApi.Id;
  replyStr: string;
}) {
  return requestClient.put('/asset/market/comment/reply', data);
}

export function getMarketDemandPage(
  params: PageParam & {
    demandNo?: string;
    demandType?: number;
    expectedDelivery?: number;
    ifSelf?: number;
    keyword?: string;
    status?: number;
    urgency?: number;
  },
) {
  return requestClient.get<PageResult<MarketApi.Demand>>(
    '/asset/market/demand/page',
    { params },
  );
}

export function getMarketDemand(id: MarketApi.Id) {
  return requestClient.get<MarketApi.Demand>(
    `/asset/market/demand/get?id=${id}`,
  );
}

export function createMarketDemand(data: MarketApi.Demand) {
  return requestClient.post<MarketApi.Id>('/asset/market/demand/create', data);
}

export function updateMarketDemand(data: MarketApi.Demand) {
  return requestClient.put('/asset/market/demand/update', data);
}

export function deleteMarketDemand(id: MarketApi.Id) {
  return requestClient.delete(`/asset/market/demand/delete?id=${id}`);
}

export function publishMarketDemand(id: MarketApi.Id) {
  return requestClient.put(`/asset/market/demand/publish?id=${id}`);
}

export function closeMarketDemand(data: {
  closeReason?: string;
  id: MarketApi.Id;
  status: number;
}) {
  return requestClient.put('/asset/market/demand/close', data);
}

export function replyMarketDemand(data: {
  content: string;
  demandId: MarketApi.Id;
  replyDeptId?: MarketApi.Id;
  replyType: number;
}) {
  return requestClient.post<MarketApi.Id>('/asset/market/demand/reply', data);
}

export function matchMarketDemand(data: {
  assetId: MarketApi.Id;
  demandId: MarketApi.Id;
  matchNote?: string;
}) {
  return requestClient.post<MarketApi.Id>('/asset/market/demand/match', data);
}

export function acceptMarketDemand(id: MarketApi.Id) {
  return requestClient.put(`/asset/market/demand/accept?id=${id}`);
}

export function confirmMarketDemandMatch(data: {
  confirmNote?: string;
  createApply?: boolean;
  id: MarketApi.Id;
  status: number;
}) {
  return requestClient.put('/asset/market/demand/match/confirm', data);
}

export function suggestMarketDemandAssets(demandId: MarketApi.Id) {
  return requestClient.get<MarketApi.Resource[]>(
    `/asset/market/demand/suggest?demandId=${demandId}`,
  );
}

export function checkMarketAccess(assetId: MarketApi.Id) {
  return requestClient.get<boolean>(
    `/asset/market/access/check?assetId=${assetId}`,
  );
}

export function previewMarketAsset(
  assetId: MarketApi.Id,
  limit = 20,
  consumptionKey?: string,
) {
  return requestClient.get<DataAssetApi.Preview>(
    `/asset/market/access/preview?assetId=${assetId}&limit=${limit}`,
    { headers: consumptionHeaders(consumptionKey) },
  );
}

export function getMarketAvailableApps() {
  return requestClient.get<MarketApi.App[]>(
    '/asset/market/apply/available-apps',
  );
}

export function getMarketAvailableApis(assetId: MarketApi.Id) {
  return requestClient.get<MarketApi.Endpoint[]>(
    '/asset/market/apply/available-apis',
    { params: { assetId } },
  );
}

/** 集市消费分页，采用独立集市类目与真实收藏筛选。 */
export function getMarketMartPage(params: MarketApi.MartPageReq) {
  return requestClient.get<PageResult<MarketApi.Resource>>(
    '/asset/market/mart/page',
    { params },
  );
}

/** 当前用户可见集市资产的统计，计数字段保持无损。 */
export function getMarketMartStatistics() {
  return requestClient.get<MarketApi.MartStatistics>(
    '/asset/market/mart/statistics',
  );
}

/** 读取发布配置及当前资产修订，用于预填和并发校验。 */
export function getMarketMartListing(assetId: MarketApi.Id) {
  return requestClient.get<MarketApi.MartListing>(
    '/asset/market/mart/listing',
    { params: { assetId } },
  );
}

/** 发布已审核目录版本，同时保存集市展示配置。 */
export function publishMarketMart(data: MarketApi.MartPublishReq) {
  return requestClient.put<boolean>('/asset/market/mart/publish', data);
}

/** 服务端幂等设置当前用户的收藏状态。 */
export function updateMarketMartFavorite(data: {
  assetId: MarketApi.Id;
  favorite: boolean;
}) {
  return requestClient.put<boolean>('/asset/market/mart/favorite', data);
}

/** 启用的顶层集市专题及其已发布资产数量。 */
export function getMarketMartTopics() {
  return requestClient.get<MarketApi.MartTopic[]>('/asset/market/mart/topics');
}
