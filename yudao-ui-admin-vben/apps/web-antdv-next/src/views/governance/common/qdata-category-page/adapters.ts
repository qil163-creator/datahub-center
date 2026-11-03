import type { CategoryAdapter, CategoryPayload, CategoryRow } from './model';

import type { AssetCategoryTagApi } from '#/api/asset/categorytag';
import type { FoundationCategoryCleanRuleApi } from '#/api/governance/categorycleanrule';
import type { GovernanceCategoryTermApi } from '#/api/governance/categoryterm';

import {
  createCategoryTag,
  deleteCategoryTag,
  getCategoryTag,
  getCategoryTagList,
  updateCategoryTag,
} from '#/api/asset/categorytag';
import {
  createCategoryCleanRule,
  deleteCategoryCleanRule,
  getCategoryCleanRule,
  getCategoryCleanRuleList,
  updateCategoryCleanRule,
} from '#/api/governance/categorycleanrule';
import {
  createCategoryTerm,
  deleteCategoryTerm,
  getCategoryTerm,
  getCategoryTermList,
  updateCategoryTerm,
} from '#/api/governance/categoryterm';

function normalize(row: {
  id: number | string;
  parentId?: number | string;
  categoryName?: string;
  categoryCode?: string;
  sort?: number;
  status?: number;
  remark?: string;
  createBy?: string;
  creatorName?: string;
  creator?: string;
  createTime?: number | string;
}): CategoryRow {
  return {
    id: row.id,
    parentId: row.parentId ?? 0,
    key: String(row.id),
    name: row.categoryName ?? '',
    description: row.remark ?? '',
    status: row.status ?? 0,
    sort: row.sort ?? 0,
    creatorName:
      row.creatorName ??
      row.createBy ??
      (row.creator === '1' || row.creator?.startsWith('migration:')
        ? '系统管理员'
        : row.creator),
    createTime: row.createTime,
  };
}

function toCategoryPayload(data: CategoryPayload) {
  return {
    id: data.id,
    parentId: data.parentId,
    categoryName: data.name,
    categoryCode: data.name,
    sort: data.sort,
    status: data.status,
    remark: data.description,
  };
}

export const cleanRuleCategoryAdapter: CategoryAdapter = {
  label: '清洗规则类目',
  nameLabel: '清洗规则类目名称',
  permissionPrefix: 'governance:category-clean-rule',
  list: async (params) => getCategoryCleanRuleList(params ?? {}),
  get: getCategoryCleanRule,
  create: (data) => createCategoryCleanRule(toCategoryPayload(data) as FoundationCategoryCleanRuleApi.CategoryCleanRule),
  update: (data) => updateCategoryCleanRule(toCategoryPayload(data) as FoundationCategoryCleanRuleApi.CategoryCleanRule),
  remove: deleteCategoryCleanRule,
  normalize: (row) => normalize(row as FoundationCategoryCleanRuleApi.CategoryCleanRule),
  toPayload: toCategoryPayload,
};

export const tagCategoryAdapter: CategoryAdapter = {
  label: '标签类目',
  nameLabel: '标签类目名称',
  permissionPrefix: 'asset:category-tag',
  list: async (params) => getCategoryTagList(params as { categoryName?: string; status?: number }),
  get: getCategoryTag,
  create: (data) => createCategoryTag(toCategoryPayload(data) as AssetCategoryTagApi.CategoryTag),
  update: (data) => updateCategoryTag(toCategoryPayload(data) as AssetCategoryTagApi.CategoryTag),
  remove: deleteCategoryTag,
  normalize: (row) => normalize(row as AssetCategoryTagApi.CategoryTag),
  toPayload: toCategoryPayload,
};

export const termCategoryAdapter: CategoryAdapter = {
  label: '业务术语类目',
  nameLabel: '业务术语类目名称',
  permissionPrefix: 'governance:category-term',
  list: async (params) => getCategoryTermList(params),
  get: getCategoryTerm,
  create: (data) => createCategoryTerm(toCategoryPayload(data) as GovernanceCategoryTermApi.Save),
  update: (data) => updateCategoryTerm(toCategoryPayload(data) as GovernanceCategoryTermApi.Save),
  remove: deleteCategoryTerm,
  normalize: (row) => normalize(row as GovernanceCategoryTermApi.Category),
  toPayload: toCategoryPayload,
};
