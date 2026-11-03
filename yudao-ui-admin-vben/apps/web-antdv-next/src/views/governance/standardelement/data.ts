import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { StandardDocApi } from '#/api/governance/doc';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { SystemUserApi } from '#/api/system/user';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';

import { getDocSimpleList } from '#/api/governance/doc';
import { getStandardElementCategories } from '#/api/governance/standardelement';
import { getSimpleUserList } from '#/api/system/user';
import { getRangePickerDefaultProps } from '#/utils';

import { createStandardDefinitionSchema } from '../standardcommon/definition-schema';

/** 生命周期状态选项（DRAFT-草稿 PUBLISHED-已发布 DEPRECATED-已废止） */
export const LIFECYCLE_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT', color: 'default' },
  { label: '已发布', value: 'PUBLISHED', color: 'success' },
  { label: '已废止', value: 'DEPRECATED', color: 'error' },
];

/** 生命周期状态映射显示文本 */
export function formatLifecycleStatus(value?: string) {
  return (
    LIFECYCLE_STATUS_OPTIONS.find((item) => item.value === value)?.label ||
    value ||
    '-'
  );
}

/** 关联数据：标准数据元类目列表，用于类目 id 映射名称 */
let categoryList: Awaited<ReturnType<typeof getStandardElementCategories>> = [];

/** 关联数据：标准文件登记，用于标准登记 id 映射名称 */
let docList: StandardDocApi.SimpleDoc[] = [];

/** 关联数据：用户列表，用于负责人 id 映射名称 */
let userList: SystemUserApi.User[] = [];
let nameCachesLoaded = false;
let cacheScope = '';
let cacheRevision = 0;

/** 列表查询前预加载名称映射；force 为 true 时强制刷新，避免新增类目/标准后映射过期 */
export async function loadElementNameCaches(force = false) {
  const access = useAccessStore();
  const scope = `${access.accessToken}/${access.refreshToken}`;
  if (cacheScope !== scope) {
    cacheScope = scope;
    nameCachesLoaded = false;
    categoryList = [];
    docList = [];
    userList = [];
  }
  if (nameCachesLoaded && !force) {
    return;
  }
  const revision = ++cacheRevision;
  const [categories, docs, users] = await Promise.allSettled([
    getStandardElementCategories(),
    getDocSimpleList(),
    getSimpleUserList(),
  ]);
  if (cacheScope !== scope || revision !== cacheRevision) return;
  categoryList = categories.status === 'fulfilled' ? categories.value : [];
  docList = docs.status === 'fulfilled' ? docs.value : [];
  userList = users.status === 'fulfilled' ? users.value : [];
  nameCachesLoaded = true;
}

/** 类目 id 映射名称 */
export function formatCategoryName(categoryId?: number | string) {
  return (
    categoryList.find((item) => String(item.id) === String(categoryId))
      ?.categoryName ||
    categoryId ||
    '-'
  );
}

/** 标准登记 id 映射标准名称 */
export function formatDocumentName(documentId?: number | string) {
  if (documentId === null || documentId === undefined || documentId === '') {
    return '-';
  }
  return findDoc(documentId)?.nameCn || documentId;
}

/** 标准登记 id 映射标准编号 */
export function formatDocumentStdCode(documentId?: number | string) {
  if (documentId === null || documentId === undefined || documentId === '') {
    return '-';
  }
  return findDoc(documentId)?.stdCode || '-';
}

function findDoc(documentId?: number | string) {
  return docList.find((item) => String(item.id) === String(documentId));
}

/** 负责人 id 映射昵称 */
export function formatPersonName(personCharge?: number | string) {
  if (
    personCharge === null ||
    personCharge === undefined ||
    personCharge === ''
  ) {
    return '-';
  }
  return (
    userList.find((item) => String(item.id) === String(personCharge))
      ?.nickname || personCharge
  );
}

/** 用户 id 映射手机号 */
export function formatUserMobile(userId?: number | string) {
  if (userId === null || userId === undefined || userId === '') {
    return '-';
  }
  return (
    userList.find((item) => String(item.id) === String(userId))?.mobile || '-'
  );
}

/** 保留原有扩展属性，主字段按参考页面顺序显示。 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    ...createStandardDefinitionSchema({
      categoryApi: getStandardElementCategories,
      englishField: 'engName',
    }),
    ...[
      'id',
      'code',
      'sort',
      'codeSetId',
      'revision',
      'lifecycleStatus',
      '__extended',
    ].map(
      (fieldName): VbenFormSchema => ({
        fieldName,
        component: 'Input',
        dependencies: { triggerFields: [''], resolve: () => ({ show: false }) },
      }),
    ),
    ...[
      {
        fieldName: 'dataPrecision',
        label: '数据精度',
        component: 'InputNumber',
        componentProps: { min: 0, precision: 0 },
      },
      { fieldName: 'dataFormat', label: '数据格式', component: 'Input' },
      { fieldName: 'measureUnit', label: '计量单位', component: 'Input' },
      { fieldName: 'valueRange', label: '值域范围', component: 'Input' },
      {
        fieldName: 'nullableFlag',
        label: '是否可空',
        component: 'RadioGroup',
        componentProps: {
          options: [
            { label: '可空', value: 1 },
            { label: '不可空', value: 0 },
          ],
        },
      },
      { fieldName: 'defaultValue', label: '默认值', component: 'Input' },
      {
        fieldName: 'remark',
        label: '备注',
        component: 'TextArea',
        formItemClass: 'col-span-2',
      },
    ].map(
      (schema): VbenFormSchema => ({
        ...schema,
        dependencies: {
          triggerFields: ['__extended'],
          resolve: ({ values }) => ({ show: Boolean(values.__extended) }),
        },
      }),
    ),
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'code',
      label: '数据元编码',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入数据元编码',
      },
    },
    {
      fieldName: 'name',
      label: '中文名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入中文名称',
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
      fieldName: 'lifecycleStatus',
      label: '生命周期',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: LIFECYCLE_STATUS_OPTIONS,
        placeholder: '请选择生命周期状态',
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
    {
      fieldName: 'createTime',
      label: '创建时间',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        allowClear: true,
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(
  onStatusChange?: (
    newStatus: number,
    row: GovernanceStandardElementApi.StandardElement,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<GovernanceStandardElementApi.StandardElement>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 80,
    },
    {
      field: 'code',
      title: '数据元编码',
      minWidth: 120,
    },
    {
      field: 'name',
      title: '中文名称',
      minWidth: 120,
    },
    {
      field: 'engName',
      title: '英文名称',
      minWidth: 120,
    },
    {
      field: 'categoryId',
      title: '数据元类目',
      minWidth: 140,
      formatter: ({ cellValue }) => formatCategoryName(cellValue),
    },
    {
      field: 'stdCode',
      title: '标准编码',
      minWidth: 140,
      formatter: ({ row }) => formatDocumentStdCode(row.documentId),
    },
    {
      field: 'documentId',
      title: '标准登记',
      minWidth: 160,
      formatter: ({ cellValue }) => formatDocumentName(cellValue),
    },
    {
      field: 'columnType',
      title: '字段类型',
      minWidth: 120,
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 140,
    },
    {
      field: 'lifecycleStatus',
      title: '生命周期',
      minWidth: 100,
      align: 'center',
      formatter: ({ cellValue }) => formatLifecycleStatus(cellValue),
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 100,
      align: 'center',
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
      field: 'personCharge',
      title: '负责人',
      minWidth: 120,
      formatter: ({ cellValue }) => formatPersonName(cellValue),
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 340,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}
