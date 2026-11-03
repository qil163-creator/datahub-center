import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { ModelingBizCategoryApi } from '#/api/modeling/bizcategory';
import type { SystemUserApi } from '#/api/system/user';

import { shallowRef } from 'vue';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { getBizCategoryList } from '#/api/modeling/bizcategory';
import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import { getSimpleUserList } from '#/api/system/user';

/** 用户缓存（负责人/创建人昵称、电话带出用） */
const userList = shallowRef<SystemUserApi.User[]>([]);
getSimpleUserList().then((data) => {
  userList.value = data;
});

export function getUserNickname(userId?: null | number | string) {
  if (userId === null || userId === undefined || userId === '') {
    return '-';
  }
  return (
    userList.value.find((user) => String(user.id) === String(userId))
      ?.nickname || String(userId)
  );
}

export function getUserMobile(userId?: null | number) {
  return (
    userList.value.find((user) => String(user.id) === String(userId))?.mobile ??
    ''
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
      fieldName: 'parentId',
      label: '上级分类',
      rules: 'selectRequired',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: false,
        api: async () => {
          const data = await getBizCategoryList({});
          return [{ id: 0, name: '顶级节点', children: handleTree(data) }];
        },
        labelField: 'name',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择上级分类',
        treeDefaultExpandAll: true,
      },
    },
    {
      fieldName: 'name',
      label: '业务分类名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入业务分类名称',
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
      fieldName: 'dataDomainIds',
      label: '关联数据域',
      rules: 'selectRequired',
      component: 'ApiSelect',
      componentProps: {
        mode: 'multiple',
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
        maxTagCount: 'responsive',
        placeholder: '请选择关联数据域',
        api: async () => {
          const list = await getDataDomainSimpleList();
          return (list || []).filter(
            (item) => item.status === CommonStatusEnum.ENABLE,
          );
        },
        labelField: 'name',
        valueField: 'id',
      },
    },
    {
      fieldName: 'leaderUserId',
      label: '负责人',
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
      fieldName: 'responsibilityScope',
      label: '责任范围',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        rows: 3,
        maxlength: 2000,
        showCount: true,
        placeholder: '说明本分类的责任范围与边界，不作为授权依据',
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
      label: '分类名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入业务分类名称',
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
    row: ModelingBizCategoryApi.BizCategory,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<ModelingBizCategoryApi.BizCategory>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      width: 80,
    },
    {
      field: 'name',
      title: '业务分类名称',
      minWidth: 170,
      treeNode: true,
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
      minWidth: 110,
    },
    {
      field: 'dataDomains',
      title: '关联数据域',
      minWidth: 220,
      align: 'left',
      showOverflow: false,
      slots: { default: 'dataDomains' },
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
      width: 260,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
