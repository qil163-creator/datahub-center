import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { CommonStatusEnum } from '@vben/constants';
import { formatDateTime } from '@vben/utils';

import { getUserNickname } from './user';

export function formatEnableStatus(status?: number) {
  if (status === CommonStatusEnum.ENABLE) {
    return '启用';
  }
  if (status === CommonStatusEnum.DISABLE) {
    return '停用';
  }
  return '-';
}

/** 把 JSON 字符串或对象格式化为缩进文本；非法 JSON 原样返回 */
export function prettyJson(value?: null | object | string) {
  if (value === null || value === undefined || value === '') {
    return '';
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '';
  }
}

export function formatJsonText(value?: null | object | string) {
  return prettyJson(value) || '-';
}

function formatAuditTime(value?: Date | null | number | string) {
  return value === null || value === undefined
    ? formatDateTime(undefined)
    : formatDateTime(value);
}

export function auditDetailFields(record?: object): DetailFieldItem[] {
  const row = record as
    | undefined
    | {
        createTime?: Date | null | number | string;
        creator?: null | number | string;
        updater?: null | number | string;
        updateTime?: Date | null | number | string;
      };
  return [
    {
      key: 'creator',
      label: '创建人',
      value: getUserNickname(row?.creator),
    },
    {
      key: 'createTime',
      label: '创建时间',
      value: formatAuditTime(row?.createTime),
    },
    {
      key: 'updater',
      label: '更新人',
      value: getUserNickname(row?.updater),
    },
    {
      key: 'updateTime',
      label: '更新时间',
      value: formatAuditTime(row?.updateTime),
    },
  ];
}
