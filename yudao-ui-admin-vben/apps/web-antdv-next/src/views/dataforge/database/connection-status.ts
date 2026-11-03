/** 连通状态：0未检测 1在线 2离线 */
export const DATABASE_CONNECTION_STATUS = {
  UNTESTED: 0,
  ONLINE: 1,
  OFFLINE: 2,
} as const;

export function connectionStatusLabel(status?: null | number) {
  switch (status) {
    case DATABASE_CONNECTION_STATUS.OFFLINE: {
      return '离线';
    }
    case DATABASE_CONNECTION_STATUS.ONLINE: {
      return '在线';
    }
    default: {
      return '未检测';
    }
  }
}

export function connectionStatusColor(status?: null | number) {
  switch (status) {
    case DATABASE_CONNECTION_STATUS.OFFLINE: {
      return 'red';
    }
    case DATABASE_CONNECTION_STATUS.ONLINE: {
      return 'green';
    }
    default: {
      return 'default';
    }
  }
}
