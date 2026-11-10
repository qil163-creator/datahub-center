/* eslint-disable vue/one-component-per-file -- 用简化网格隔离页面业务逻辑测试 */
import { defineComponent, h } from 'vue';

export const ACTION_ICON = { ADD: 'add', DELETE: 'delete' };
export const NativeVxeButton = defineComponent({
  setup: (_, { slots }) => () => h('button', slots.default?.()),
});
export const TableAction = defineComponent({
  props: { actions: { type: Array, default: () => [] } },
  setup: (props) => () => h('div', props.actions.map((action: any) =>
    action.ifShow === false ? null : h('button', { disabled: action.disabled, onClick: action.onClick }, action.label),
  )),
});
export function useVbenVxeGrid(options: any) {
  let columns = options.gridOptions.columns;
  const Grid = defineComponent({
    props: { tableData: { type: Array, default: () => [] } },
    setup: (props, { slots }) => () => h('div', [
      slots['toolbar-tools']?.(),
      ...props.tableData.flatMap((row: any) => columns.filter((column: any) => column.field).map((column: any) => slots.body?.({ row, column }))),
      slots.pager?.(),
    ]),
  });
  return [Grid, {
    setLoading: () => {},
    setGridOptions: (next: any) => { columns = next.columns; },
    grid: { clearCheckboxRow: () => {} },
  }];
}
