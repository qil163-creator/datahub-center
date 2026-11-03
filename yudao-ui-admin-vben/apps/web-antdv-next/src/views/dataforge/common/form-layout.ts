import type { VbenFormProps, VbenFormSchema } from '#/adapter/form';

import { useVbenForm } from '#/adapter/form';

/** 数据建模基座：编辑弹窗 800 / 只读详情 960，横排标签 130，两列。 */
export const DATAFORGE_FORM_LABEL_WIDTH = 130;
export const DATAFORGE_FORM_MODAL_CLASS = 'w-[800px]';
export const DATAFORGE_DETAIL_MODAL_CLASS = 'w-[960px]';

/**
 * 复杂条件表单的次级规范：与 useVbenForm 同一套视觉（横排、130、两列），
 * 分组用 createSectionDivider，高级项用 createAdvancedToggle 收起。
 */
export function useDataforgeForm(options: VbenFormProps) {
  const { commonConfig, layout, showDefaultActions, wrapperClass, ...rest } =
    options;
  return useVbenForm({
    ...rest,
    commonConfig: {
      componentProps: { class: 'w-full' },
      formItemClass: 'col-span-1',
      labelWidth: DATAFORGE_FORM_LABEL_WIDTH,
      ...commonConfig,
    },
    layout: layout ?? 'horizontal',
    showDefaultActions: showDefaultActions ?? false,
    wrapperClass: wrapperClass ?? 'grid-cols-2',
  });
}

/** 默认收起的高级项开关，证书 / 重试等跟在后面并用同一 trigger 控制 show */
export function createAdvancedToggle(
  fieldName: string,
  label = '显示高级选项',
  extra?: Partial<VbenFormSchema>,
): VbenFormSchema {
  return {
    fieldName,
    label,
    component: 'Switch',
    defaultValue: false,
    help: '证书、重试等不常用项默认收起',
    ...extra,
  };
}
