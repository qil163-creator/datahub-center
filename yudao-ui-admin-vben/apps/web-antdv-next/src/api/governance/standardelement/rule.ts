import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace StandardElementRuleApi {
  export type Id = number | string;
  export type Kind = 'AUDIT' | 'CLEAN';
  export interface Template {
    id: Id;
    kind: Kind;
    code: string;
    name: string;
    strategyKey: string;
    categoryId?: Id;
    categoryName?: string;
    dimensionName?: string;
    description?: string;
    useCase?: string;
    example?: string;
    enabled: boolean;
    engineSupported: boolean;
    disabledReason?: string;
    config: Record<string, unknown>;
  }
  export interface Rule {
    id?: Id;
    elementId: Id;
    kind: Kind;
    name: string;
    templateId: Id;
    templateCode?: string;
    templateName?: string;
    strategyKey?: string;
    dimensionName?: string;
    status: number;
    description?: string;
    whereCondition?: string;
    config: Record<string, unknown>;
    severity?: 'HIGH' | 'LOW' | 'MID';
    errorExample?: string;
    repairSuggestion?: string;
    revision?: Id;
    creatorName?: string;
    createTime?: number | string;
  }
  export interface Context {
    fields: { label: string; modelId?: Id; value: string }[];
    referenceObjects: {
      fields: { label: string; value: string }[];
      id: Id;
      name: string;
    }[];
    message?: string;
  }
}
const base = '/governance/standard-element';
export function getElementRulePage(
  params: PageParam & {
    elementId: StandardElementRuleApi.Id;
    kind: StandardElementRuleApi.Kind;
  },
) {
  return requestClient.get<PageResult<StandardElementRuleApi.Rule>>(
    `${base}/rule-page`,
    { params },
  );
}
export function getElementRule(
  elementId: StandardElementRuleApi.Id,
  id: StandardElementRuleApi.Id,
) {
  return requestClient.get<StandardElementRuleApi.Rule>(`${base}/rule-get`, {
    params: { elementId, id },
  });
}
export function saveElementRule(data: StandardElementRuleApi.Rule) {
  return data.id === undefined
    ? requestClient.post<StandardElementRuleApi.Id>(`${base}/rule-create`, data)
    : requestClient.put<boolean>(`${base}/rule-update`, data);
}
export function deleteElementRule(
  elementId: StandardElementRuleApi.Id,
  id: StandardElementRuleApi.Id,
) {
  return requestClient.delete<boolean>(`${base}/rule-delete`, {
    params: { elementId, id },
  });
}
export function getElementRuleTemplates(kind: StandardElementRuleApi.Kind) {
  return requestClient.get<StandardElementRuleApi.Template[]>(
    `${base}/rule-templates`,
    { params: { kind } },
  );
}
export function getElementRuleContext(elementId: StandardElementRuleApi.Id) {
  return requestClient.get<StandardElementRuleApi.Context>(
    `${base}/rule-context`,
    { params: { elementId } },
  );
}
export function sampleElementRule(data: {
  config: Record<string, unknown>;
  elementId: StandardElementRuleApi.Id;
  sample: string;
  templateId: StandardElementRuleApi.Id;
}) {
  return requestClient.post<{ message: string; valid: boolean }>(
    `${base}/rule-sample`,
    data,
  );
}
