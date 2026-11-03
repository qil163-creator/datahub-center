import type { SystemUserApi } from '#/api/system/user';

import { shallowRef } from 'vue';

import { getSimpleUserList } from '#/api/system/user';

/** 用户缓存（创建人/更新人昵称展示用） */
const userList = shallowRef<SystemUserApi.User[]>([]);
getSimpleUserList().then((data) => {
  userList.value = data;
});

export function getUserNickname(userId?: null | number | string) {
  if (userId === null || userId === undefined || userId === '') {
    return '-';
  }
  const user = userList.value.find(
    (item) => String(item.id) === String(userId),
  );
  return user?.nickname || user?.username || String(userId);
}
