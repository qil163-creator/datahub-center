import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { SystemUserApi } from '#/api/system/user';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { getSimpleUserList } from '#/api/system/user';

/** 用户缓存（负责人/创建人昵称、电话带出用） */
let userList: SystemUserApi.User[] = [];
getSimpleUserList().then((data) => (userList = data));

export function getUserNickname(userId?: null | number | string) {
  if (userId === null || userId === undefined || userId === '') {
    return '-';
  }
  return (
    userList.find((user) => String(user.id) === String(userId))?.nickname ||
    String(userId)
  );
}

export function getUserMobile(userId?: null | number | string) {
  return (
    userList.find((user) => String(user.id) === String(userId))?.mobile ?? ''
  );
}

/** 新增/修改的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'id',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'name',
      label: '数据分域名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入数据分域名称',
      },
    },
    {
      fieldName: 'code',
      label: '英文缩写',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入英文缩写',
      },
    },
    {
      fieldName: 'leaderUserId',
      label: '负责人',
      rules: 'selectRequired',
      component: 'ApiSelect',
      componentProps: {
        api: getSimpleUserList,
        labelField: 'nickname',
        valueField: 'id',
        placeholder: '请选择负责人',
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
      },
    },
    {
      fieldName: 'leaderPhone',
      label: '负责人电话',
      component: 'Input',
      componentProps: {
        disabled: true,
        placeholder: '负责人联系电话，随负责人自动带出',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      defaultValue: CommonStatusEnum.ENABLE,
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入描述',
        rows: 4,
        maxlength: 500,
        showCount: true,
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入备注',
        rows: 4,
        maxlength: 500,
        showCount: true,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '分域名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入数据分域名称',
      },
    },
    {
      fieldName: 'code',
      label: '英文缩写',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入英文缩写',
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
    row: ModelingDataDomainApi.DataDomain,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<ModelingDataDomainApi.DataDomain>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      width: 80,
    },
    {
      field: 'name',
      title: '数据分域名称',
      minWidth: 150,
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 200,
      align: 'left',
      showOverflow: false,
      slots: { default: 'description' },
    },
    {
      field: 'code',
      title: '英文缩写',
      minWidth: 100,
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 90,
      cellRender: {
        attrs: { beforeChange: onStatusChange },
        name: 'CellSwitch',
        props: {
          checkedValue: CommonStatusEnum.ENABLE,
          unCheckedValue: CommonStatusEnum.DISABLE,
        },
      },
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 240,
      align: 'left',
      showOverflow: false,
      slots: { default: 'remark' },
    },
    {
      field: 'leaderUserId',
      title: '负责人',
      minWidth: 100,
      formatter: ({ cellValue }) => getUserNickname(cellValue),
    },
    {
      field: 'leaderPhone',
      title: '负责人电话',
      minWidth: 120,
    },
    {
      field: 'creator',
      title: '创建人',
      minWidth: 100,
      formatter: ({ cellValue }) => getUserNickname(cellValue),
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 140,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 200,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
