import type { PropType } from 'vue';

import type { ActionItem } from './typing';

import { defineComponent, h } from 'vue';

/**
 * 页面测试用 TableAction 替身：平铺按钮标记 data-row-action，「更多」项标记 data-more-action，
 * 按 ifShow 过滤并保留 disabled，图标以 svg[data-icon] 输出，便于断言而不依赖下拉浮层。
 */
export const TableActionStub = defineComponent({
  name: 'TableActionStub',
  props: {
    actions: { type: Array as PropType<ActionItem[]>, default: () => [] },
    dropDownActions: { type: Array as PropType<ActionItem[]>, default: () => [] },
  },
  setup(props) {
    const visible = (action: ActionItem) =>
      typeof action.ifShow === 'function'
        ? action.ifShow(action)
        : action.ifShow !== false;
    const render = (action: ActionItem, marker: string) =>
      h(
        'button',
        {
          type: 'button',
          [marker]: action.label,
          disabled: action.disabled === true || undefined,
          'data-danger': action.danger ? '' : undefined,
          onClick: () => {
            if (action.disabled !== true) action.onClick?.();
          },
        },
        [
          action.icon ? h('svg', { 'data-icon': action.icon }) : null,
          action.label,
        ],
      );
    return () =>
      h('div', { class: 'table-action-stub' }, [
        ...props.actions
          .filter((action) => visible(action))
          .map((action) => render(action, 'data-row-action')),
        ...props.dropDownActions
          .filter((action) => visible(action))
          .map((action) => render(action, 'data-more-action')),
      ]);
  },
});
