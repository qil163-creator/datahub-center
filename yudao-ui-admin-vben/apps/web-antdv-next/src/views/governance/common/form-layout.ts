/** 与数据建模新增/修改弹窗一致的两列表单布局 */
export const GOVERNANCE_MODAL_FORM = {
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

export const GOVERNANCE_FORM_MODAL_CLASS = 'w-[800px]';
export const GOVERNANCE_ANTD_FORM_LABEL_COL = { style: { width: '130px' } };
export const GOVERNANCE_ANTD_FORM_WRAPPER_COL = { style: { flex: '1 1 0' } };

export function clearReactiveObject(target: Record<string, unknown>) {
  Object.assign(
    target,
    Object.fromEntries(Object.keys(target).map((key) => [key, undefined])),
  );
}
