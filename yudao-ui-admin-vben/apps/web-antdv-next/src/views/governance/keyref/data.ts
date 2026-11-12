import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetySecurityApi } from '#/api/governance/security';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { z } from '#/adapter/form';

export const KEY_REF_QUERY = 'governance:key-ref:query';
export const KEY_REF_CREATE = 'governance:key-ref:create';
export const KEY_REF_UPDATE = 'governance:key-ref:update';

/** 密钥引用编码：字母开头，字母数字下划线或短横线，≤64（与后端一致）。 */
export const KEY_CODE_PATTERN = /^[A-Za-z][\w-]{0,63}$/;

const KEY_CODE_MESSAGE = '须字母开头，仅含字母、数字、_ 或 -';

/** 新建表单校验。 */
export function keyRefFormError(
  keyCode: string,
  keyName: string,
): string | undefined {
  if (!KEY_CODE_PATTERN.test(keyCode.trim())) {
    return KEY_CODE_MESSAGE;
  }
  if (!keyName.trim()) return '请输入名称';
  if (keyName.trim().length > 128) return '名称不能超过 128 个字符';
  return undefined;
}

export interface KeyRefQuery {
  keyword?: string;
  status?: number;
}

/** 后端只提供全量列表（数量很少），搜索在前端完成。 */
export function filterKeyRefs(
  list: SafetySecurityApi.KeyRef[],
  query: KeyRefQuery,
): SafetySecurityApi.KeyRef[] {
  const keyword = query.keyword?.trim().toLowerCase();
  return list.filter((item) => {
    if (
      query.status !== undefined &&
      query.status !== null &&
      item.status !== query.status
    ) {
      return false;
    }
    if (!keyword) return true;
    return (
      item.keyCode.toLowerCase().includes(keyword) ||
      item.keyName.toLowerCase().includes(keyword)
    );
  });
}

/** 新建的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'keyCode',
      label: '引用编码',
      component: 'Input',
      componentProps: {
        maxlength: 64,
        placeholder: '如 pii_default，创建后不可修改',
      },
      rules: z
        .string()
        .trim()
        .min(1, '请输入引用编码')
        .regex(KEY_CODE_PATTERN, KEY_CODE_MESSAGE),
    },
    {
      fieldName: 'keyName',
      label: '名称',
      component: 'Input',
      componentProps: {
        maxlength: 128,
        placeholder: '请输入名称',
      },
      rules: z
        .string()
        .trim()
        .min(1, '请输入名称')
        .max(128, '名称不能超过 128 个字符'),
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        maxlength: 512,
        placeholder: '请输入备注',
        rows: 3,
        showCount: true,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'keyword',
      label: '编码/名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入引用编码或名称',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(
  onStatusChange?: (
    newStatus: number,
    row: SafetySecurityApi.KeyRef,
  ) => PromiseLike<boolean | undefined>,
  canUpdate = true,
): VxeTableGridOptions<SafetySecurityApi.KeyRef>['columns'] {
  return [
    {
      field: 'keyCode',
      title: '引用编码',
      minWidth: 180,
      align: 'left',
    },
    {
      field: 'keyName',
      title: '名称',
      minWidth: 180,
      align: 'left',
    },
    {
      field: 'keyType',
      title: '类型',
      minWidth: 160,
      formatter: ({ row }) =>
        [row.provider, row.keyType].filter(Boolean).join(' · ') || '-',
    },
    {
      field: 'keyVersion',
      title: '主密钥版本',
      minWidth: 110,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 100,
      cellRender: {
        attrs: { beforeChange: onStatusChange },
        name: 'CellSwitch',
        props: {
          checkedValue: CommonStatusEnum.ENABLE,
          disabled: !canUpdate,
          unCheckedValue: CommonStatusEnum.DISABLE,
        },
      },
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 200,
      align: 'left',
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
  ];
}
