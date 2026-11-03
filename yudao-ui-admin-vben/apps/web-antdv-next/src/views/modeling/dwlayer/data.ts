import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDwLayerApi } from '#/api/modeling/dwlayer';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { getDwLayerList } from '#/api/modeling/dwlayer';
import { getSimpleUserList } from '#/api/system/user';

/** 节点类型 */
export const DW_LAYER_NODE_TYPE = {
  CATEGORY: 'CATEGORY',
  LAYER: 'LAYER',
} as const;

export type DwLayerCreateMode = 'category' | 'layer';

/** 一级分类默认值 */
export function getCategoryCreateDefaults(): Partial<FoundationDwLayerApi.DwLayer> {
  return {
    parentId: 0,
    ancestors: '0',
    levelNo: 1,
    nodeType: DW_LAYER_NODE_TYPE.CATEGORY,
    sortOrder: 1,
    isBuiltin: 0,
    status: CommonStatusEnum.ENABLE,
  };
}

/** 二级分层默认值 */
export function getLayerCreateDefaults(
  parent: FoundationDwLayerApi.DwLayer,
): Partial<FoundationDwLayerApi.DwLayer> {
  return {
    parentId: parent.id,
    ancestors: `0,${parent.id}`,
    levelNo: 2,
    nodeType: DW_LAYER_NODE_TYPE.LAYER,
    sortOrder: 1,
    isBuiltin: 0,
    status: CommonStatusEnum.ENABLE,
  };
}

function isLayerNode(values: Record<string, any>) {
  return values.nodeType === DW_LAYER_NODE_TYPE.LAYER;
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
      fieldName: 'createMode',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'ancestors',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'levelNo',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'nodeType',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'parentLayerName',
      label: '上级分类',
      component: 'Input',
      componentProps: {
        disabled: true,
        placeholder: '上级分类',
      },
      dependencies: {
        triggerFields: ['createMode', 'id'],
        show: (values) => values.createMode === 'layer' && !values.id,
      },
    },
    {
      fieldName: 'parentId',
      label: '上级分类',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: false,
        api: async () => {
          const data = await getDwLayerList({});
          return handleTree(
            data.filter(
              (item) => item.nodeType === DW_LAYER_NODE_TYPE.CATEGORY,
            ),
          );
        },
        labelField: 'layerName',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择上级分类',
        treeDefaultExpandAll: true,
      },
      rules: 'selectRequired',
      dependencies: {
        triggerFields: ['id', 'nodeType', 'createMode'],
        show: (values) => !!values.id && isLayerNode(values),
      },
    },
    {
      fieldName: 'layerName',
      label: '分层名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入分层名称',
      },
    },
    {
      fieldName: 'layerCode',
      label: '分层编码',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入分层编码，如 ODS、DWD',
      },
      dependencies: {
        triggerFields: ['nodeType'],
        show: isLayerNode,
      },
    },
    {
      fieldName: 'tablePrefix',
      label: '默认表前缀',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入默认表前缀，如 ods_',
      },
      dependencies: {
        triggerFields: ['nodeType'],
        show: isLayerNode,
      },
    },
    {
      fieldName: 'defaultTableType',
      label: '默认表类型',
      rules: 'selectRequired',
      component: 'Select',
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.DEFAULT_TABLE_TYPE, 'string'),
        placeholder: '请选择默认表类型',
      }),
      dependencies: {
        triggerFields: ['nodeType'],
        show: isLayerNode,
      },
    },
    {
      fieldName: 'description',
      label: '说明',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入说明',
        rows: 3,
      },
    },

    {
      fieldName: 'ownerUserId',
      label: '负责人',
      component: 'ApiSelect',
      componentProps: {
        api: getSimpleUserList,
        labelField: 'nickname',
        valueField: 'id',
        placeholder: '请选择负责人',
        allowClear: true,
      },
    },
    {
      fieldName: 'sortOrder',
      label: '排序',
      rules: 'required',
      component: 'InputNumber',
      componentProps: {
        class: '!w-full',
        min: 0,
        placeholder: '请输入排序',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: 'required',
      component: 'RadioGroup',
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      }),
    },
    {
      fieldName: 'isBuiltin',
      label: '是否内置',
      component: 'RadioGroup',
      defaultValue: 0,
      componentProps: () => ({
        disabled: true,
        options: getDictOptions(DICT_TYPE.IS_BUILTIN, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      }),
      dependencies: {
        triggerFields: ['id'],
        show: (values) => !!values.id,
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'layerName',
      label: '分层名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入分层名称',
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
    row: FoundationDwLayerApi.DwLayer,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<FoundationDwLayerApi.DwLayer>['columns'] {
  return [
    // {
    //   field: 'id',
    //   title: '主键',
    //   minWidth: 120,
    // },
    {
      field: 'layerName',
      title: '分层名称',
      minWidth: 120,
      treeNode: true,
    },
    // {
    //   field: 'nodeType',
    //   title: '节点类型',
    //   minWidth: 120,
    // },
    {
      field: 'description',
      title: '说明',
      minWidth: 200,
      align: 'left',
      showOverflow: false,
      slots: { default: 'description' },
    },
    {
      field: 'isBuiltin',
      title: '是否内置',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.IS_BUILTIN },
      },
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
      field: 'ownerName',
      title: '负责人',
      minWidth: 120,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'layerCode',
      title: '分层编码',
      minWidth: 120,
    },

    {
      field: 'tablePrefix',
      title: '默认表前缀',
      minWidth: 120,
    },
    {
      field: 'defaultTableType',
      title: '默认表类型',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.DEFAULT_TABLE_TYPE },
      },
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 120,
      formatter: 'formatDateTime',
    },
    // {
    //   field: 'remark',
    //   title: '备注',
    //   minWidth: 120,
    // },
    {
      title: '操作',
      width: 260,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
