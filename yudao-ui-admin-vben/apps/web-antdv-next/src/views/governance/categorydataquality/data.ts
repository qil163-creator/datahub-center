import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationCategoryDataQualityApi } from '#/api/governance/categorydataquality';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { getCategoryDataQualityList } from '#/api/governance/categorydataquality';

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
      label: '上级数据质量类目',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: async () => {
          const data = await getCategoryDataQualityList({});
          data.unshift({
            id: 0,
            categoryName: '顶级数据质量类目',
          } as FoundationCategoryDataQualityApi.CategoryDataQuality);
          return handleTree(data);
        },
        labelField: 'categoryName',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择上级数据质量类目',
        treeDefaultExpandAll: true,
      },
      rules: 'selectRequired',
    },
    {
      fieldName: 'categoryName',
      label: '类目名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入类目名称',
      },
    },
    {
      fieldName: 'sort',
      label: '排序',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入排序',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: 'required',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入备注',
        rows: 3,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'categoryName',
      label: '类目名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入类目名称',
      },
    },
    {
      fieldName: 'sort',
      label: '排序',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入排序',
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
    row: FoundationCategoryDataQualityApi.CategoryDataQuality,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<FoundationCategoryDataQualityApi.CategoryDataQuality>['columns'] {
  return [
    {
      field: 'id',
      title: '主键',
      minWidth: 120,
    },
    {
      field: 'categoryName',
      title: '类目名称',
      minWidth: 120,
      align: 'left',
      headerAlign: 'center',
      treeNode: true,
    },
    {
      field: 'sort',
      title: '排序',
      minWidth: 120,
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
      field: 'remark',
      title: '备注',
      minWidth: 120,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 120,
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
