import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardTermApi } from '#/api/governance/standardterm';
import type { SystemUserApi } from '#/api/system/user';
import type { StandardTreeNode } from '#/views/governance/standardcommon/data';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';

import { z } from '#/adapter/form';
import { getCategoryDatametaList } from '#/api/governance/categorydatameta';
import { getStandardElementPage } from '#/api/governance/standardelement';
import { getStandardTermCategories } from '#/api/governance/standardterm';
import { getSimpleUserList } from '#/api/system/user';
import { standardCategoryTree } from '#/views/governance/standardcommon/data';

/** 用户显示缓存仅在当前登录身份内复用。 */
let userList: SystemUserApi.User[] = [];
let userListScope = '';
let userListSequence = 0;
function currentUserScope() {
  const access = useAccessStore();
  const user = useUserStore();
  return JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]);
}
export async function loadTermUserList() {
  const scope = currentUserScope();
  if (scope !== userListScope) {
    userList = [];
    userListScope = scope;
    userListSequence++;
  }
  if (userList.length === 0) {
    const sequence = ++userListSequence;
    const result = await getSimpleUserList();
    if (scope === currentUserScope() && sequence === userListSequence) {
      userList = result;
    }
  }
  return scope === currentUserScope() ? userList : [];
}

export function formatTermUser(userId?: number | string) {
  if (userId === null || userId === undefined || userId === '') return '-';
  return (
    (userListScope === currentUserScope()
      ? userList.find((item) => String(item.id) === String(userId))?.nickname
      : undefined) || String(userId)
  );
}

interface CategoryOption {
  id: string;
  categoryName: string;
  children: CategoryOption[];
}

function toCategoryOptions(nodes: StandardTreeNode[]): CategoryOption[] {
  return nodes.map((node) => ({
    id: node.key,
    categoryName: node.title,
    children: toCategoryOptions(node.children ?? []),
  }));
}

/** 加载数据元类目树 */
async function loadCategoryTree() {
  const data = await getCategoryDatametaList({});
  return toCategoryOptions(standardCategoryTree(data)[0]?.children ?? []);
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
      label: '术语名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入术语名称',
      },
    },
    {
      fieldName: 'engName',
      label: '英文名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入英文名称',
      },
    },
    {
      fieldName: 'alias',
      label: '别名/同义词',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '多个用逗号分隔，如：顾客,买家',
      },
    },
    {
      fieldName: 'categoryId',
      label: '所属数据元类目',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: loadCategoryTree,
        labelField: 'categoryName',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择所属数据元类目',
        treeDefaultExpandAll: true,
        showSearch: true,
        treeNodeFilterProp: 'label',
      },
    },
    {
      fieldName: 'termCategoryId',
      label: '所属术语类目',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: async () => {
          const categories = await getStandardTermCategories();
          return toCategoryOptions(
            standardCategoryTree(categories)[0]?.children ?? [],
          );
        },
        labelField: 'categoryName',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择所属术语类目',
        treeDefaultExpandAll: true,
        showSearch: true,
        treeNodeFilterProp: 'label',
      },
    },
    {
      fieldName: 'definition',
      label: '业务定义',
      rules: 'required',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入业务定义（统一业务口径）',
        rows: 3,
      },
    },
    {
      fieldName: 'relatedElementId',
      label: '关联数据元',
      component: 'ApiSelect',
      componentProps: {
        allowClear: true,
        api: async () => {
          const data = await getStandardElementPage({
            pageNo: 1,
            pageSize: 200,
          });
          return data.list ?? [];
        },
        labelField: 'name',
        valueField: 'id',
        placeholder: '请选择关联的标准数据元',
        showSearch: true,
        optionFilterProp: 'label',
      },
    },
    {
      fieldName: 'ownerUserId',
      label: '负责人',
      component: 'ApiSelect',
      componentProps: {
        allowClear: true,
        api: loadTermUserList,
        labelField: 'nickname',
        valueField: 'id',
        placeholder: '请选择负责人（Owner）',
        showSearch: true,
      },
    },
    {
      fieldName: 'stewardUserId',
      label: '数据管家',
      component: 'ApiSelect',
      componentProps: {
        allowClear: true,
        api: loadTermUserList,
        labelField: 'nickname',
        valueField: 'id',
        placeholder: '请选择数据管家（Steward）',
        showSearch: true,
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
      rules: z.number().default(CommonStatusEnum.ENABLE),
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        allowClear: true,
        placeholder: '请输入备注',
        rows: 2,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '术语名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入术语名称',
      },
    },
    {
      fieldName: 'engName',
      label: '英文名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入英文名称',
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
export function useGridColumns(): VxeTableGridOptions<GovernanceStandardTermApi.StandardTerm>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    { field: 'id', title: '编号', minWidth: 70 },
    { field: 'name', title: '术语名称', minWidth: 130 },
    { field: 'engName', title: '英文名称', minWidth: 120 },
    { field: 'termCategoryId', title: '术语类目', minWidth: 130, slots: { default: 'category' } },
    { field: 'alias', title: '别名/同义词', minWidth: 140 },
    { field: 'definition', title: '业务定义', minWidth: 240 },
    {
      field: 'ownerUserId',
      title: '负责人',
      minWidth: 100,
      formatter: ({ cellValue }) => formatTermUser(cellValue),
    },
    {
      field: 'stewardUserId',
      title: '数据管家',
      minWidth: 100,
      formatter: ({ cellValue }) => formatTermUser(cellValue),
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 90,
      align: 'center',
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.COMMON_STATUS },
      },
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 160,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 160,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
