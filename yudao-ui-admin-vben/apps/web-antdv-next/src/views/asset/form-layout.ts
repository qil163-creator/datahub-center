/** 与数据建模新增/修改弹窗一致的两列表单布局 */
export const ASSET_MODAL_FORM = {
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    formItemClass: 'col-span-1',
    labelWidth: 130,
  },
  wrapperClass: 'grid-cols-2',
  layout: 'horizontal' as const,
  showDefaultActions: false,
};
