import { computed, ref, watch } from 'vue';

import { useUserStore } from '@vben/stores';

export interface CustomizableColumn {
  key: string;
  label: string;
  width?: number;
  minWidth?: number;
  align?: 'center' | 'left' | 'right';
  fixed?: 'left' | 'right';
  visible?: boolean;
  locked?: boolean;
}

type SavedColumn = Pick<CustomizableColumn, 'align' | 'fixed' | 'key' | 'visible' | 'width'>;

export function useTableCustomization<T extends CustomizableColumn>(
  storageKey: string,
  defaults: () => T[],
) {
  const open = ref(false);
  const user = useUserStore();
  const storageId = computed(() => `${storageKey}:${String(user.userInfo?.userId ?? 'anonymous')}`);
  const defaultColumns = () => defaults().map((column) => ({ ...column, visible: true }));
  function read(): T[] {
    const initial = defaultColumns();
    try {
      const stored: unknown = JSON.parse(localStorage.getItem(storageId.value) || 'null');
      if (!Array.isArray(stored)) return initial;
      const saved = stored as SavedColumn[];
      const byKey = new Map(initial.map((column) => [column.key, column]));
      const result: T[] = [];
      for (const item of saved) {
        if (!item || typeof item.key !== 'string') continue;
        const column = byKey.get(item.key);
        if (!column) continue;
        byKey.delete(item.key);
        result.push({
          ...column,
          visible: column.locked ? true : item.visible !== false,
          width: typeof item.width === 'number' && Number.isFinite(item.width) && item.width >= 40 && item.width <= 2000 ? item.width : column.width,
          align: ['center', 'left', 'right'].includes(item.align ?? '') ? item.align : column.align,
          fixed: item.fixed === 'left' || item.fixed === 'right' ? item.fixed : undefined,
        });
      }
      result.push(...byKey.values());
      return result.some((item) => item.visible !== false) ? result : initial;
    } catch {
      return initial;
    }
  }
  const columns = ref<T[]>(read());
  watch(storageId, () => {
    columns.value = read();
    open.value = false;
  });
  const visibleColumns = computed(() => {
    const visible = columns.value.filter((column) => column.visible !== false);
    return [
      ...visible.filter((column) => column.fixed === 'left'),
      ...visible.filter((column) => !column.fixed),
      ...visible.filter((column) => column.fixed === 'right'),
    ] as T[];
  });
  function save(next: T[]) {
    columns.value = next.map((column) => ({ ...column }));
    try {
      localStorage.setItem(storageId.value, JSON.stringify(next.map(({ key, visible, width, fixed, align }) => ({ key, visible, width, fixed, align }))));
    } catch {
      // 浏览器存储不可用时仍保留当前页面的设置。
    }
  }
  function apply(next: CustomizableColumn[]) {
    const original = new Map(defaults().map((column) => [column.key, column]));
    save(next.map((column) => {
      const fallback = original.get(column.key)!;
      const width = typeof column.width === 'number' && Number.isFinite(column.width) && column.width >= 40 && column.width <= 2000
        ? column.width
        : fallback.width;
      return { ...fallback, ...column, width };
    }) as T[]);
    open.value = false;
  }
  function reset() {
    save(defaultColumns());
    open.value = false;
  }
  return { columns, visibleColumns, open, apply, reset };
}
