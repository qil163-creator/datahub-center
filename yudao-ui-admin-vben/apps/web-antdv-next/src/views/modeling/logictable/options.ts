import type { SafetyCategoryApi } from '#/api/governance/category';
import type { StandardDocApi } from '#/api/governance/doc';
import type { SafetyLevelApi } from '#/api/governance/level';
import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { BusinessEntityApi } from '#/api/modeling/business-entity';
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { FoundationDwLayerApi } from '#/api/modeling/dwlayer';
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';
import type { SystemUserApi } from '#/api/system/user';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import { getCategoryPage } from '#/api/governance/category';
import { getDocSimpleList } from '#/api/governance/doc';
import { getLevelPage } from '#/api/governance/level';
import { getStandardCodeSetPage } from '#/api/governance/standardcodeset';
import { getStandardElementPage } from '#/api/governance/standardelement';
import { getBusinessEntityPage } from '#/api/modeling/business-entity';
import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import { getDwLayerList } from '#/api/modeling/dwlayer';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import { getSimpleUserList } from '#/api/system/user';

/** 拉取完整下拉数据，避免把第一页误当成全部可选项。 */
export async function loadLogicOptionPages<T>(
  fetchPage: (params: {
    pageNo: number;
    pageSize: number;
  }) => Promise<{ list: T[]; total: number }>,
) {
  const rows: T[] = [];
  for (let pageNo = 1; ; pageNo++) {
    const page = await fetchPage({ pageNo, pageSize: 100 });
    rows.push(...page.list);
    if (page.list.length === 0 || rows.length >= page.total) return rows;
  }
}

/** 五类逻辑表共用选项；登录身份、权限变化及卸载时丢弃旧请求。 */
export function useLogicOptions() {
  const access = useAccessStore();
  const dataDomains = ref<ModelingDataDomainApi.DataDomain[]>([]);
  const domains = ref<ModelingSubjectDomainApi.SubjectDomain[]>([]);
  const entities = ref<BusinessEntityApi.Entity[]>([]);
  const users = ref<SystemUserApi.User[]>([]);
  const documents = ref<StandardDocApi.SimpleDoc[]>([]);
  const standards = ref<GovernanceStandardElementApi.StandardElement[]>([]);
  const codeTables = ref<GovernanceStandardCodeSetApi.StandardCodeSet[]>([]);
  const classifications = ref<SafetyCategoryApi.Category[]>([]);
  const levels = ref<SafetyLevelApi.Level[]>([]);
  const layers = ref<FoundationDwLayerApi.DwLayer[]>([]);
  const loading = ref(false);
  const error = ref('');
  let sequence = 0;
  const scope = computed(() =>
    JSON.stringify([
      access.accessToken,
      access.refreshToken,
      access.accessCodes,
    ]),
  );
  function clear() {
    for (const target of [
      dataDomains,
      domains,
      entities,
      users,
      documents,
      standards,
      codeTables,
      classifications,
      layers,
      levels,
    ])
      target.value = [];
  }
  async function load() {
    const current = ++sequence;
    loading.value = true;
    error.value = '';
    const results = await Promise.allSettled([
      getDataDomainSimpleList().then((rows) => {
        if (current === sequence) dataDomains.value = rows;
      }),
      getSubjectDomainList({}).then((rows) => {
        if (current === sequence) domains.value = rows;
      }),
      loadLogicOptionPages((params) => getBusinessEntityPage(params)).then(
        (rows) => {
          if (current === sequence) entities.value = rows;
        },
      ),
      getSimpleUserList().then((rows) => {
        if (current === sequence) users.value = rows;
      }),
      getDocSimpleList().then((rows) => {
        if (current === sequence) documents.value = rows;
      }),
      loadLogicOptionPages((params) => getStandardElementPage(params)).then(
        (rows) => {
          if (current === sequence) standards.value = rows;
        },
      ),
      loadLogicOptionPages((params) => getStandardCodeSetPage(params)).then(
        (rows) => {
          if (current === sequence) codeTables.value = rows;
        },
      ),
      getDwLayerList({}).then((rows) => {
        if (current === sequence) layers.value = rows;
      }),
      loadLogicOptionPages((params) => getCategoryPage(params)).then((rows) => {
        if (current === sequence) classifications.value = rows;
      }),
      loadLogicOptionPages((params) => getLevelPage(params)).then((rows) => {
        if (current === sequence) levels.value = rows;
      }),
    ]);
    if (current !== sequence) return;
    loading.value = false;
    const labels = [
      '业务板块',
      '主题域',
      '业务对象/活动',
      '负责人',
      '标准登记',
      '标准数据元',
      '标准代码表',
      '目标分层',
      '数据分类',
      '数据分级',
    ];
    error.value = results
      .flatMap((result, index) =>
        result.status === 'rejected' ? [labels[index]] : [],
      )
      .join('、');
  }
  watch(scope, () => {
    sequence++;
    clear();
    void load();
  });
  onBeforeUnmount(() => {
    sequence++;
  });
  return {
    dataDomains,
    domains,
    entities,
    users,
    documents,
    standards,
    codeTables,
    classifications,
    levels,
    layers,
    loading,
    error,
    load,
  };
}
