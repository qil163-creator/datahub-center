import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { SystemUserApi } from '#/api/system/user';

import { computed, ref, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

/** 业务规划选项随登录身份和权限变化清空并重新加载。 */
export function usePlanningOptions(withDomains = false) {
  const access = useAccessStore();
  const user = useUserStore();
  const scope = computed(() =>
    JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]),
  );
  const users = ref<SystemUserApi.User[]>([]);
  const domains = ref<ModelingDataDomainApi.DataDomain[]>([]);
  const loading = ref(false);
  const failed = ref(false);
  const request = useObjectRequest(
    () => scope.value,
    () => {
      users.value = [];
      domains.value = [];
    },
  );
  async function reload() {
    const current = request.begin();
    loading.value = true;
    failed.value = false;
    try {
      const [userList, domainList] = await Promise.all([
        getSimpleUserList(),
        withDomains ? getDataDomainSimpleList() : Promise.resolve([]),
      ]);
      if (!current()) return;
      users.value = userList;
      domains.value = domainList;
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  }
  watch(
    () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
    reload,
    { immediate: true },
  );
  const userOptions = computed(() =>
    users.value.map((item) => ({
      label: item.nickname,
      value: String(item.id),
    })),
  );
  const domainOptions = computed(() =>
    domains.value
      .filter((item) => item.status === 0)
      .map((item) => ({
        label: item.name ?? String(item.id),
        value: String(item.id),
      })),
  );
  const userName = (id?: number | string) =>
    users.value.find((item) => String(item.id) === String(id))?.nickname ??
    (id === undefined ? '-' : String(id));
  const userPhone = (id?: number | string) =>
    users.value.find((item) => String(item.id) === String(id))?.mobile ?? '';
  return {
    users,
    domains,
    loading,
    failed,
    reload,
    userOptions,
    domainOptions,
    userName,
    userPhone,
  };
}
