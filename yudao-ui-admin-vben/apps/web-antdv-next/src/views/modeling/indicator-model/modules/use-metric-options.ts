import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';
import type { TableModelApi } from '#/api/modeling/tablemodel';
import type { SystemUserApi } from '#/api/system/user';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';

import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import { getTableModelPage } from '#/api/modeling/tablemodel';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

/** 指标页面选项随身份失效；分页获取来源，避免把第一页冒充完整目录。 */
export function useMetricOptions() {
  const access = useAccessStore();
  const user = useUserStore();
  const { hasAccessByCodes } = useAccess();
  const domains = ref<ModelingDataDomainApi.DataDomain[]>([]);
  const subjects = ref<ModelingSubjectDomainApi.SubjectDomain[]>([]);
  const models = ref<TableModelApi.Model[]>([]);
  const users = ref<SystemUserApi.User[]>([]);
  const loading = ref(false);
  const error = ref(false);
  const scope = computed(() =>
    JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]),
  );
  const requests = useObjectRequest(
    () => scope.value,
    () => {
      domains.value = [];
      subjects.value = [];
      models.value = [];
      users.value = [];
    },
  );
  async function reload() {
    const current = requests.begin();
    loading.value = true;
    error.value = false;
    try {
      const [domainList, subjectList, userList, modelList] = await Promise.all([
        getDataDomainSimpleList(),
        hasAccessByCodes(['modeling:subject-domain:query'])
          ? getSubjectDomainList({})
          : Promise.resolve([]),
        getSimpleUserList(),
        (async () => {
          if (!hasAccessByCodes(['modeling:model:query'])) return [];
          const result: TableModelApi.Model[] = [];
          for (let pageNo = 1; ; pageNo++) {
            const page = await getTableModelPage({ pageNo, pageSize: 100 });
            if (!current()) return [];
            result.push(...page.list);
            if (result.length >= page.total) break;
            if (page.list.length === 0)
              throw new Error('来源逻辑模型分页不完整');
          }
          return result;
        })(),
      ]);
      if (!current()) return;
      domains.value = domainList;
      subjects.value = subjectList;
      users.value = userList;
      models.value = modelList;
    } catch {
      if (current()) error.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  }
  watch(
    () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
    reload,
    { immediate: true },
  );
  const userLabel = (id?: number | string) =>
    users.value.find((item) => String(item.id) === String(id))?.nickname ||
    (id === undefined ? '-' : String(id));
  const userPhone = (id?: number | string) =>
    users.value.find((item) => String(item.id) === String(id))?.mobile || '';
  const domainLabel = (id?: number | string) =>
    domains.value.find((item) => String(item.id) === String(id))?.name ||
    (id === undefined ? '-' : String(id));
  const subjectLabel = (id?: number | string) =>
    subjects.value.find((item) => String(item.id) === String(id))?.name ||
    (id === undefined ? '-' : String(id));
  return {
    domains,
    subjects,
    models,
    users,
    loading,
    error,
    reload,
    userLabel,
    userPhone,
    domainLabel,
    subjectLabel,
  };
}
