import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyCategoryApi } from '#/api/governance/category';
import type { FoundationCategoryDataApi } from '#/api/governance/categorydata';
import type { SafetyWhitelistApi } from '#/api/governance/whitelist';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { formatDateTime, handleTree } from '@vben/utils';

import { getCategoryPage } from '#/api/governance/category';
import { getCategoryDataList } from '#/api/governance/categorydata';
import { getSimpleDeptList } from '#/api/system/dept';
import { getSimpleRoleList } from '#/api/system/role';
import { getSimpleUserList } from '#/api/system/user';
import { getRangePickerDefaultProps } from '#/utils';

interface TreeOption {
  label: string;
  value: number | string;
  selectable?: boolean;
  children?: TreeOption[];
}

/** 审批状态 */
export const APPROVAL_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT', color: 'default' },
  { label: '审批中', value: 'PENDING', color: 'processing' },
  { label: '已通过', value: 'APPROVED', color: 'success' },
  { label: '已驳回', value: 'REJECTED', color: 'error' },
];

/** 审批动作（审批记录用） */
export const APPROVAL_ACTION_OPTIONS = [
  { label: '提交审批', value: 'SUBMIT', color: 'processing' },
  { label: '审批通过', value: 'APPROVE', color: 'success' },
  { label: '审批驳回', value: 'REJECT', color: 'error' },
];

export function approvalOptionLabel(
  options: { label: string; value: string }[],
  value?: null | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

export function approvalOptionColor(
  options: { color?: string; label: string; value: string }[],
  value?: null | string,
) {
  return options.find((item) => item.value === value)?.color ?? 'default';
}

/** 关联数据：数据分类列表，用于 objectId 映射分类名称 */
let categoryList: SafetyCategoryApi.Category[] = [];
/** 关联数据：数据分类类目列表，用于 objectType=CATALOG 时映射类目名称 */
let catalogList: FoundationCategoryDataApi.CategoryData[] = [];
let objectNameCachesLoaded = false;

/** 加载分类/类目缓存，列表查询前调用，保证 formatter 渲染时数据已就绪 */
export async function loadObjectNameCaches() {
  if (objectNameCachesLoaded) {
    return;
  }
  const [categoryData, catalogData] = await Promise.all([
    getCategoryPage({ pageNo: 1, pageSize: 100 }),
    getCategoryDataList({}),
  ]);
  categoryList = categoryData.list;
  catalogList = catalogData;
  objectNameCachesLoaded = true;
}

/** 豁免对象名称：按豁免范围映射类目名或分类名 */
export function formatWhitelistObjectName(row: SafetyWhitelistApi.Whitelist) {
  if (row.objectType === 'CATALOG') {
    const catalog = catalogList.find(
      (item) => String(item.id) === String(row.objectId),
    );
    return catalog ? `${catalog.categoryName}（含下级）` : '-';
  }
  const category = categoryList.find(
    (item) => String(item.id) === String(row.objectId),
  );
  if (!category) {
    return '-';
  }
  return category.shortName
    ? `${category.name}（${category.shortName}）`
    : category.name;
}

/** 加载数据分类树：类目为不可选的父节点，数据分类为可选叶子 */
async function loadCategoryTreeOptions(): Promise<TreeOption[]> {
  const [catalogList, categoryData] = await Promise.all([
    getCategoryDataList({}),
    getCategoryPage({ pageNo: 1, pageSize: 100 }),
  ]);
  // 数据分类按所属类目分组
  const categoriesByCatalog = new Map<string, TreeOption[]>();
  categoryData.list.forEach((item) => {
    const key = String(item.categoryId);
    if (!categoriesByCatalog.has(key)) {
      categoriesByCatalog.set(key, []);
    }
    categoriesByCatalog.get(key)!.push({
      label: item.shortName ? `${item.name}（${item.shortName}）` : item.name,
      value: item.id,
    });
  });
  const catalogTree = handleTree(
    catalogList.map((item) => ({
      ...item,
      parentId:
        item.parentId === null || item.parentId === undefined
          ? 0
          : Number(item.parentId),
    })),
  );
  // 类目节点也可选：选中类目表示豁免范围覆盖其下所有子类目与分类
  const convert = (nodes: any[]): TreeOption[] =>
    nodes.map((node) => ({
      label: node.categoryName,
      value: `catalog-${node.id}`,
      children: [
        ...convert(node.children ?? []),
        ...(categoriesByCatalog.get(String(node.id)) ?? []),
      ],
    }));
  return convert(catalogTree);
}

/** 加载"部门-用户"树：部门为不可选的父节点，用户为可选叶子 */
async function loadUserTreeOptions(): Promise<TreeOption[]> {
  const [deptList, userList] = await Promise.all([
    getSimpleDeptList(),
    getSimpleUserList(),
  ]);
  const deptIdSet = new Set(deptList.map((dept) => String(dept.id)));
  const usersByDept = new Map<string, TreeOption[]>();
  const orphanUsers: TreeOption[] = [];
  userList.forEach((user: any) => {
    const node: TreeOption = { label: user.nickname, value: user.id };
    const key = String(user.deptId);
    if (
      user.deptId !== null &&
      user.deptId !== undefined &&
      deptIdSet.has(key)
    ) {
      if (!usersByDept.has(key)) {
        usersByDept.set(key, []);
      }
      usersByDept.get(key)!.push(node);
    } else {
      orphanUsers.push(node);
    }
  });
  const deptTree = handleTree(deptList as any[]);
  const convert = (nodes: any[]): TreeOption[] =>
    nodes.map((node) => ({
      label: node.name,
      value: `dept-${node.id}`,
      selectable: false,
      children: [
        ...convert(node.children ?? []),
        ...(usersByDept.get(String(node.id)) ?? []),
      ],
    }));
  return [...convert(deptTree), ...orphanUsers];
}

/** 加载部门树（部门本身可选） */
async function loadDeptTreeOptions() {
  const data = await getSimpleDeptList();
  return handleTree(data as any[]);
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
      label: '白名单名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入白名单名称',
      },
    },
    {
      fieldName: 'objectId',
      label: '数据分类',
      rules: 'selectRequired',
      component: 'ApiTreeSelect',
      componentProps: {
        api: loadCategoryTreeOptions,
        labelField: 'label',
        valueField: 'value',
        childrenField: 'children',
        placeholder: '请选择数据分类',
        treeDefaultExpandAll: true,
        showSearch: true,
        treeNodeFilterProp: 'label',
      },
    },
    // 豁免范围 objectType 不在表单展示，提交时按所选节点自动判定：类目为 CATALOG（含下级）、分类为 CATEGORY
    // 豁免场景暂不区分，默认全部场景生效，表单不展示（预留 QUERY/SHARE/OPEN/EXPORT 扩展）
    {
      fieldName: 'scene',
      component: 'Input',
      defaultValue: 'ALL',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'subjectType',
      label: '生效分类',
      rules: 'required',
      component: 'RadioGroup',
      defaultValue: 'USER',
      componentProps: {
        options: getDictOptions(DICT_TYPE.SUBJECT_TYPE, 'string'),
      },
    },
    {
      fieldName: 'userIds',
      label: '生效账号',
      rules: 'selectRequired',
      component: 'ApiTreeSelect',
      componentProps: {
        api: loadUserTreeOptions,
        labelField: 'label',
        valueField: 'value',
        childrenField: 'children',
        multiple: true,
        allowClear: true,
        placeholder: '请选择生效账号',
        treeDefaultExpandAll: true,
        showSearch: true,
        treeNodeFilterProp: 'label',
      },
      dependencies: {
        triggerFields: ['subjectType'],
        show: (values) => values.subjectType === 'USER',
      },
    },
    {
      fieldName: 'roleIds',
      label: '生效角色',
      rules: 'selectRequired',
      component: 'ApiSelect',
      componentProps: {
        api: getSimpleRoleList,
        labelField: 'name',
        valueField: 'id',
        mode: 'multiple',
        allowClear: true,
        placeholder: '请选择生效角色',
      },
      dependencies: {
        triggerFields: ['subjectType'],
        show: (values) => values.subjectType === 'ROLE',
      },
    },
    {
      fieldName: 'deptIds',
      label: '生效部门',
      rules: 'selectRequired',
      component: 'ApiTreeSelect',
      componentProps: {
        api: loadDeptTreeOptions,
        labelField: 'name',
        valueField: 'id',
        childrenField: 'children',
        multiple: true,
        allowClear: true,
        placeholder: '请选择生效部门',
        treeDefaultExpandAll: true,
        showSearch: true,
        treeNodeFilterProp: 'name',
      },
      dependencies: {
        triggerFields: ['subjectType'],
        show: (values) => values.subjectType === 'DEPT',
      },
    },
    {
      fieldName: 'effectiveTimeRange',
      label: '生效时段',
      rules: 'required',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        valueFormat: 'x',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: 'required',
      component: 'RadioGroup',
      defaultValue: 1,
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
      },
    },
    {
      fieldName: 'applyReason',
      label: '申请理由',
      rules: 'required',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请说明申请豁免脱敏的业务原因，供审批人参考',
        maxlength: 500,
        showCount: true,
        autoSize: { minRows: 3, maxRows: 6 },
      },
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入描述',
        maxlength: 500,
        showCount: true,
        autoSize: { minRows: 5, maxRows: 10 },
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入备注',
        maxlength: 500,
        showCount: true,
        autoSize: { minRows: 5, maxRows: 10 },
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '白名单名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入白名单名称',
      },
    },
    {
      fieldName: 'subjectType',
      label: '生效主体',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.SUBJECT_TYPE, 'string'),
        placeholder: '请选择生效主体',
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
      fieldName: 'approvalStatus',
      label: '审批状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: APPROVAL_STATUS_OPTIONS,
        placeholder: '请选择审批状态',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(
  onStatusChange?: (
    newStatus: number,
    row: SafetyWhitelistApi.Whitelist,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<SafetyWhitelistApi.Whitelist>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 120,
    },
    {
      field: 'name',
      title: '白名单名称',
      minWidth: 120,
    },
    {
      field: 'subjectType',
      title: '生效主体',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.SUBJECT_TYPE },
      },
    },
    {
      field: 'objectId',
      title: '数据分类',
      minWidth: 140,
      formatter: ({ row }) => formatWhitelistObjectName(row),
    },
    {
      field: 'effectiveTime',
      title: '生效时段',
      minWidth: 300,
      formatter: ({ row }) => {
        if (!row.effectiveTime && !row.expireTime) {
          return '-';
        }
        const start = row.effectiveTime
          ? formatDateTime(row.effectiveTime)
          : '立即生效';
        const end = row.expireTime ? formatDateTime(row.expireTime) : '永久';
        return `${start} ~ ${end}`;
      },
    },
    {
      field: 'approvalStatus',
      title: '审批状态',
      minWidth: 100,
      slots: { default: 'approvalStatus' },
    },
    {
      field: 'approver',
      title: '审批人',
      minWidth: 100,
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
          unCheckedValue: CommonStatusEnum.DISABLE,
        },
      },
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 120,
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 120,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 300,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
