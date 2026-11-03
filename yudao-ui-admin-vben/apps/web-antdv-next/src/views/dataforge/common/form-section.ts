import type { VbenFormSchema } from '#/adapter/form';

export {
  createAdvancedToggle,
  DATAFORGE_DETAIL_MODAL_CLASS,
  DATAFORGE_FORM_LABEL_WIDTH,
  DATAFORGE_FORM_MODAL_CLASS,
  useDataforgeForm,
} from './form-layout';

/**
 * 表单分组标题：在 useVbenForm 的两列栅格中占满一行，用于把长表单切成「基本信息 / 连接配置 / …」等区块。
 * 与 dataforge/driver 的写法一致，抽出供接入侧各模块复用。
 */
export function createSectionDivider(
  fieldName: string,
  title: string,
  extra?: Partial<VbenFormSchema>,
): VbenFormSchema {
  return {
    component: 'Divider',
    fieldName,
    hideLabel: true,
    formItemClass: 'col-span-2',
    componentProps: {
      titlePlacement: 'start',
      class: '!my-1',
    },
    renderComponentContent: () => ({
      default: () => [title],
    }),
    ...extra,
  };
}

/** 隐藏的主键字段：编辑时用于携带 id，不渲染 */
export function createHiddenIdField(fieldName = 'id'): VbenFormSchema {
  return {
    fieldName,
    component: 'Input',
    dependencies: {
      triggerFields: [''],
      resolve: () => ({ show: false }),
    },
  };
}
