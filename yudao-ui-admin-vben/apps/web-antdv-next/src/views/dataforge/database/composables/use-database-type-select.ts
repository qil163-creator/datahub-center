import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';

import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';

import { isConnectionTypeSelectable } from '../connection-capabilities';

export interface DatabaseTypeCategory {
  code: string;
  name: string;
}

export type DatabaseTypeViewMode = 'grid' | 'list';

export function isDatabaseTypeSelectable(
  type: FoundationDatabaseTypeApi.DatabaseType,
) {
  return isConnectionTypeSelectable(type);
}

/** 新建连接时展示全部类型，支持分类浏览和全局搜索。 */
export function useDatabaseTypeSelect(
  options: { navigation?: 'anchor' | 'filter' } = {},
) {
  const access = useAccessStore();
  const activeCategory = ref('');
  const typeList = ref<FoundationDatabaseTypeApi.DatabaseType[]>([]);
  const typesLoaded = ref(false);
  const selectedType = ref<FoundationDatabaseTypeApi.DatabaseType>();
  const searchKeyword = ref('');
  const viewMode = ref<DatabaseTypeViewMode>('grid');
  const loading = ref(false);
  let requestGeneration = 0;

  const categories = computed<DatabaseTypeCategory[]>(() => {
    if (!typesLoaded.value) return [];
    const labels = new Map(
      getDictOptions(DICT_TYPE.CATEGORY_CODE, 'string').map((item) => [
        String(item.value),
        item.label,
      ]),
    );
    const codes = new Set(labels.keys());
    for (const type of typeList.value) {
      if (type.categoryCode) codes.add(type.categoryCode);
    }
    return [
      { code: '', name: '全部' },
      ...[...codes]
        .filter(Boolean)
        .map((code) => ({ code, name: labels.get(code) || code })),
    ];
  });

  const filteredTypes = computed(() => {
    const keyword = searchKeyword.value.trim().toLowerCase();
    if (!keyword) {
      return activeCategory.value && options.navigation !== 'anchor'
        ? typeList.value.filter(
            (item) => item.categoryCode === activeCategory.value,
          )
        : typeList.value;
    }
    return typeList.value.filter((item) => {
      return (
        item.typeName?.toLowerCase().includes(keyword) ||
        item.typeCode?.toLowerCase().includes(keyword) ||
        item.productId?.toLowerCase().includes(keyword) ||
        item.description?.toLowerCase().includes(keyword)
      );
    });
  });

  async function loadTypes() {
    const generation = ++requestGeneration;
    loading.value = true;
    try {
      const list = await getDatabaseTypeSimpleList();
      if (generation === requestGeneration) {
        typeList.value = list;
        typesLoaded.value = true;
      }
    } finally {
      if (generation === requestGeneration) loading.value = false;
    }
  }

  function selectCategory(categoryCode: string) {
    if (
      activeCategory.value === categoryCode &&
      options.navigation !== 'anchor'
    ) {
      return;
    }
    activeCategory.value = categoryCode;
    searchKeyword.value = '';
  }

  function selectType(type: FoundationDatabaseTypeApi.DatabaseType) {
    if (!isDatabaseTypeSelectable(type)) return;
    selectedType.value = type;
  }

  async function init() {
    activeCategory.value = '';
    typeList.value = [];
    typesLoaded.value = false;
    searchKeyword.value = '';
    selectedType.value = undefined;
    viewMode.value = 'grid';

    await loadTypes();
  }

  function reset() {
    ++requestGeneration;
    loading.value = false;
    activeCategory.value = '';
    typeList.value = [];
    typesLoaded.value = false;
    selectedType.value = undefined;
    searchKeyword.value = '';
    viewMode.value = 'grid';
  }

  watch(() => [access.accessToken, access.refreshToken], reset);
  onBeforeUnmount(reset);

  return {
    categories,
    activeCategory,
    typeList,
    selectedType,
    searchKeyword,
    viewMode,
    loading,
    filteredTypes,
    init,
    reset,
    selectCategory,
    selectType,
  };
}
