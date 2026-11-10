import type { PageResult } from '@vben/request';

import type { MetadataId } from './helpers';

import type { VbenFormProps } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { onActivated, onDeactivated, onMounted, ref, shallowRef } from 'vue';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { useObjectRequest } from '#/hooks/use-object-request';

/** 元数据列表共用分页及请求隔离；切换查询、会话和页面后旧响应不可覆盖新结果。 */
export function useMetadataGrid<Row extends { id?: MetadataId }>(
  columns: VxeTableGridOptions<Row>['columns'],
  fetchPage: (page: {
    pageNo: number;
    pageSize: number;
  }) => Promise<PageResult<Row>>,
  formOptions?: VbenFormProps,
  resetScopeFilters?: () => void,
  layout: 'content' | 'fill' = 'fill',
  nativeToolbar = false,
  subtleScrollbar = false,
) {
  const selected = shallowRef<Row[]>([]);
  const error = ref(false);
  const pageNo = ref(1);
  const pageSize = ref(10);
  let active = true;
  let mounted = false;
  const requests = useObjectRequest(
    () => '',
    () => {
      selected.value = [];
      if (resetScopeFilters) {
        pageNo.value = 1;
        resetScopeFilters();
        gridApi.formApi.setLatestSubmissionValues?.({});
        void gridApi.formApi.reset?.();
      }
      gridApi.setGridOptions({
        data: [],
        loading: false,
        pagerConfig: {
          total: 0,
          ...(resetScopeFilters ? { currentPage: 1 } : {}),
        },
      });
    },
  );
  const [Grid, gridApi] = useVbenVxeGrid<Row>({
    formOptions: formOptions
      ? { submitOnEnter: true, ...formOptions }
      : undefined,
    gridOptions: {
      columns,
      data: [],
      height: layout === 'fill' ? 'auto' : undefined,
      showOverflow: layout === 'fill' ? false : 'tooltip',
      rowConfig: { keyField: 'id', isHover: true },
      ...(subtleScrollbar ? { scrollbarConfig: { height: 6 } } : {}),
      pagerConfig: {
        currentPage: 1,
        pageSize: 10,
        pageSizes: [10, 20, 50, 100],
        total: 0,
      },
      toolbarConfig: {
        custom: true,
        ...(nativeToolbar ? {} : { customOptions: { icon: 'metadata-grid-custom-icon' } }),
        refresh: nativeToolbar,
        ...(nativeToolbar ? { refreshOptions: { queryMethod: () => load() } } : {}),
        zoom: false,
        search: nativeToolbar,
      },
    },
    gridEvents: {
      checkboxAll: ({ records }: { records: Row[] }) => {
        selected.value = records;
      },
      checkboxChange: ({ records }: { records: Row[] }) => {
        selected.value = records;
      },
      pageChange: ({
        currentPage,
        pageSize: size,
      }: {
        currentPage: number;
        pageSize: number;
      }) => {
        pageNo.value = currentPage;
        pageSize.value = size;
        void load();
      },
    },
  });

  async function load(reset = false) {
    if (!active) return;
    if (reset) pageNo.value = 1;
    const current = requests.begin();
    error.value = false;
    selected.value = [];
    gridApi.grid?.clearCheckboxRow?.();
    gridApi.setGridOptions({
      loading: true,
      pagerConfig: { currentPage: pageNo.value, pageSize: pageSize.value },
    });
    try {
      const result = await fetchPage({
        pageNo: pageNo.value,
        pageSize: pageSize.value,
      });
      if (current() && active)
        gridApi.setGridOptions({
          data: result.list ?? [],
          pagerConfig: { total: result.total ?? 0 },
        } as Parameters<typeof gridApi.setGridOptions>[0]);
    } catch {
      if (current() && active) {
        error.value = true;
        gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
      }
    } finally {
      if (current() && active) gridApi.setGridOptions({ loading: false });
    }
  }
  onMounted(() => {
    mounted = true;
    void load();
  });
  onActivated(() => {
    if (!active && mounted) {
      active = true;
      void load();
    }
  });
  onDeactivated(() => {
    active = false;
    requests.begin();
    selected.value = [];
  });
  return { Grid, gridApi, load, selected, error };
}
