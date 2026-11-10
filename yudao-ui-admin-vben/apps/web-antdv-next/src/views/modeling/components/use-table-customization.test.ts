import type { CustomizableColumn } from './use-table-customization';

import { nextTick, reactive } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useTableCustomization } from './use-table-customization';
const state = vi.hoisted(() => ({ user: { userInfo: { userId: '1' } } }));
vi.mock('@vben/stores', () => ({ useUserStore: () => state.user }));

const defaults = (): CustomizableColumn[] => [
  { key: 'name', label: '名称', width: 120, align: 'left' as const },
  { key: 'action', label: '操作', width: 180, align: 'center' as const, fixed: 'right' as const },
];

beforeEach(() => {
  localStorage.clear();
  state.user = reactive({ userInfo: { userId: '1' } });
});

describe('表格个性化配置', () => {
  it('保存列顺序和配置，空宽度回退，并按用户隔离', async () => {
    const settings = useTableCustomization('test-table', defaults);
    settings.apply([
      { key: 'action', label: '操作', width: 180, align: 'right', fixed: 'left' },
      { key: 'name', label: '名称', width: null as unknown as number, visible: false },
    ]);
    expect(settings.visibleColumns.value.map((column) => column.key)).toEqual(['action']);
    expect(settings.columns.value[1]?.width).toBe(120);
    const reloaded = useTableCustomization('test-table', defaults);
    expect(reloaded.visibleColumns.value[0]?.fixed).toBe('left');
    expect(reloaded.columns.value[1]?.visible).toBe(false);
    state.user.userInfo.userId = '2';
    await nextTick();
    expect(reloaded.visibleColumns.value.map((column) => column.key)).toEqual(['name', 'action']);
    state.user.userInfo.userId = '1';
    await nextTick();
    reloaded.reset();
    expect(reloaded.visibleColumns.value.map((column) => column.key)).toEqual(['name', 'action']);
    expect(reloaded.visibleColumns.value[1]?.fixed).toBe('right');
  });
});
