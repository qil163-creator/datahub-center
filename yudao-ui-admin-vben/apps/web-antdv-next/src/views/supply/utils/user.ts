import type { SystemUserApi } from '#/api/system/user';

import { getSimpleUserList } from '#/api/system/user';

import { sameId } from './id';

/** 用户缓存（创建人/更新人昵称用） */
let userList: SystemUserApi.User[] = [];
getSimpleUserList().then((data) => (userList = data));

export function getUserNickname(userId?: null | number | string) {
  if (userId === undefined || userId === null || userId === '') {
    return '-';
  }
  return (
    userList.find((user) => sameId(user.id, userId))?.nickname || String(userId)
  );
}
