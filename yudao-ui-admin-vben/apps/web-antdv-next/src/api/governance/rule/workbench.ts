import type { PageParam, PageResult } from '@vben/request';

import type { SafetyCategoryWorkbenchApi } from '#/api/governance/category/workbench';

import { requestClient } from '#/api/request';

export namespace SafetyRuleWorkbenchApi {
  export type Id = number | string;
  export type Time = null | number | string;
  export interface Rule {
    id: Id;
    sourceRecordId?: string;
    ruleName: string;
    categoryId: Id;
    categoryRevision: Id;
    categoryName?: string;
    categorySourceRecordId?: string;
    applicationScene: string[];
    maskType: '2';
    replaceRule: '1';
    replaceContent: string;
    intervalList: { endNum: null | number; startNum: null | number }[];
    status: number;
    description?: string;
    ruleVersionId: Id;
    ruleRevisionToken: string;
    creatorName?: string;
    updaterName?: string;
    createTime?: Time;
    updateTime?: Time;
  }
  export interface Save extends Omit<
    Rule,
    | 'categoryName'
    | 'categorySourceRecordId'
    | 'createTime'
    | 'creatorName'
    | 'id'
    | 'ruleRevisionToken'
    | 'ruleVersionId'
    | 'sourceRecordId'
    | 'updaterName'
    | 'updateTime'
  > {
    id?: Id;
    ruleVersionId?: Id;
    ruleRevisionToken?: string;
  }
  export interface Version {
    id: Id;
    categoryRevision: Id;
    ruleVersionId: Id;
    ruleRevisionToken: string;
  }
  export interface PageReq extends PageParam {
    ruleName?: string;
    categoryId?: Id;
    status?: number;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  }
  export interface DictionaryItem {
    value: string;
    label: string;
    disabled?: boolean;
  }
  export interface Options {
    categories: SafetyCategoryWorkbenchApi.Category[];
    directories: SafetyCategoryWorkbenchApi.Directory[];
    applicationScenes?: DictionaryItem[];
    maskTypes?: DictionaryItem[];
    replaceRules?: DictionaryItem[];
  }
  export interface SensitiveRow {
    id: Id;
    revision: Id;
    sourceRecordId?: string;
    assetName?: string;
    assetId?: Id;
    categoryId?: Id;
    ruleId?: Id;
    assetDescription?: string;
    tableName?: string;
    columnName?: string;
    levelName?: string;
    levelColor?: string;
    status: 0 | 1;
    creatorName?: string;
    updaterName?: string;
    createTime?: Time;
    updateTime?: Time;
  }
}
const prefix = '/governance/safety-rule/workbench';
export const getSafetyRules = (params: SafetyRuleWorkbenchApi.PageReq) =>
  requestClient.get<PageResult<SafetyRuleWorkbenchApi.Rule>>(`${prefix}/page`, {
    params,
  });
export const getSafetyRule = (id: SafetyRuleWorkbenchApi.Id) =>
  requestClient.get<SafetyRuleWorkbenchApi.Rule>(`${prefix}/get`, {
    params: { id },
  });
export const getSafetyRuleOptions = () =>
  requestClient.get<SafetyRuleWorkbenchApi.Options>(`${prefix}/options`);
export const saveSafetyRule = (data: SafetyRuleWorkbenchApi.Save) =>
  data.id === undefined
    ? requestClient.post<SafetyRuleWorkbenchApi.Id>(`${prefix}/create`, data)
    : requestClient.put(`${prefix}/update`, data);
export const changeSafetyRuleStatus = (
  data: SafetyRuleWorkbenchApi.Version & { status: number },
) => requestClient.put(`${prefix}/change-status`, data);
export const removeSafetyRule = (data: SafetyRuleWorkbenchApi.Version) =>
  requestClient.post(`${prefix}/delete`, data);
export const removeSafetyRules = (items: SafetyRuleWorkbenchApi.Version[]) =>
  requestClient.post(`${prefix}/delete-list`, { items });
export const getSafetyRuleSensitiveRows = (
  params: PageParam & {
    ruleId: SafetyRuleWorkbenchApi.Id;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  },
) =>
  requestClient.get<PageResult<SafetyRuleWorkbenchApi.SensitiveRow>>(
    `${prefix}/sensitive-page`,
    { params },
  );
