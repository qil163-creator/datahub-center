import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaTagApi } from '#/api/governance/metatag';

/** 新增/修改表单 */
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
      label: '标签名称',
      rules: 'required',
      component: 'Input',
      componentProps: { placeholder: '请输入标签名称', maxlength: 64 },
    },
    {
      fieldName: 'category',
      label: '分类',
      component: 'Input',
      componentProps: { placeholder: '如 治理 / 安全 / 业务', maxlength: 64 },
    },
    {
      fieldName: 'color',
      label: '颜色',
      component: 'Input',
      componentProps: { placeholder: '#1677ff' },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: { rows: 3, maxlength: 512 },
    },
  ];
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '标签名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入标签名称' },
    },
    {
      fieldName: 'category',
      label: '分类',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入分类' },
    },
  ];
}

export function useGridColumns(): VxeTableGridOptions<GovernanceMetaTagApi.Tag>['columns'] {
  return [
    { field: 'id', title: '编号', width: 80 },
    {
      field: 'name',
      title: '标签名称',
      minWidth: 140,
      slots: { default: 'name' },
    },
    { field: 'category', title: '分类', width: 120 },
    { field: 'refCount', title: '引用次数', width: 100 },
    { field: 'remark', title: '备注', minWidth: 180 },
    {
      field: 'createTime',
      title: '创建时间',
      width: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 160,
      fixed: 'right',
      showOverflow: false,
      className: 'metatag-action-column',
      slots: { default: 'actions' },
    },
  ];
}
