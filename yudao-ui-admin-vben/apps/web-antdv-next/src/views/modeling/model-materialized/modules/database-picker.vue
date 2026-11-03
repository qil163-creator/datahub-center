<script setup lang="ts">
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { computed, nextTick, ref, useId, watch } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { Popover } from 'antdv-next';

import { ConnectionIcon } from '#/components/connection-icon';
import { CONNECTION_CATEGORY_ICONS } from '#/components/connection-icon/category-icons';

import { QdIcon } from '../../logictable/icons';
import allCategoryIcon from '../assets/qdata-category-all.svg';
import analyticalCategoryIcon from '../assets/qdata-category-analytical.svg';
import fileCategoryIcon from '../assets/qdata-category-file-object.svg';
import relationalCategoryIcon from '../assets/qdata-category-relational.svg';
import semiStructuredCategoryIcon from '../assets/qdata-category-semi-structured.svg';
import vectorCategoryIcon from '../assets/qdata-category-vector.svg';

const props = defineProps<{
  catalog: FoundationDatabaseTypeApi.DatabaseType[];
  databases: ModelMaterializedApi.Database[];
  disabled?: boolean;
  error?: boolean;
  loading?: boolean;
  value?: number | string;
}>();
const emit = defineEmits<{
  change: [value: number | string];
  retry: [];
  'update:value': [value: number | string];
}>();
const open = ref(false);
const keyword = ref('');
const activeCategory = ref('');
const activeType = ref('');
const panel = ref<HTMLDivElement>();
const trigger = ref<HTMLButtonElement>();
const panelId = useId();
const categoryIcons: Record<string, string> = {
  '': allCategoryIcon,
  RELATIONAL: relationalCategoryIcon,
  ANALYTICAL: analyticalCategoryIcon,
  FILE_OBJECT_STORAGE: fileCategoryIcon,
  SEMI_STRUCTURED: semiStructuredCategoryIcon,
  VECTOR: vectorCategoryIcon,
};
const selected = computed(() =>
  props.databases.find(
    (database) => String(database.id) === String(props.value),
  ),
);
const definitions = computed(
  () => new Map(props.catalog.map((type) => [String(type.id), type])),
);
const filteredDatabases = computed(() =>
  props.databases.filter((database) =>
    (database.name || String(database.id))
      .toLocaleLowerCase()
      .includes(keyword.value.trim().toLocaleLowerCase()),
  ),
);
const categoryOf = (database: ModelMaterializedApi.Database) =>
  database.categoryCode ||
  definitions.value.get(String(database.databaseType))?.categoryCode ||
  '';
const categories = computed(() => {
  const names = new Map(
    getDictOptions(DICT_TYPE.CATEGORY_CODE, 'string').map((item) => [
      String(item.value),
      item.label,
    ]),
  );
  const codes = new Set([
    ...names.keys(),
    ...props.catalog.map((type) => type.categoryCode),
    ...props.databases.map(categoryOf),
  ]);
  return [
    { code: '', name: '全部', count: filteredDatabases.value.length },
    ...[...codes].filter(Boolean).map((code) => ({
      code,
      name: names.get(code) || code,
      count: filteredDatabases.value.filter(
        (database) => categoryOf(database) === code,
      ).length,
    })),
  ];
});
const available = computed(() =>
  filteredDatabases.value.filter(
    (database) =>
      !activeCategory.value || categoryOf(database) === activeCategory.value,
  ),
);
const types = computed(() => {
  const entries = props.catalog
    .filter(
      (type) =>
        !activeCategory.value || type.categoryCode === activeCategory.value,
    )
    .map((type) => ({
      key: String(type.id),
      name: type.typeName,
      iconPath: type.iconPath,
    }));
  for (const database of available.value) {
    const key = String(
      database.databaseType ?? database.productType ?? 'unknown',
    );
    if (!entries.some((type) => type.key === key))
      entries.push({
        key,
        name: database.databaseTypeName || database.productType || '未知类型',
        iconPath: database.iconPath || '',
      });
  }
  return [{ key: '', name: '全部', iconPath: '' }, ...entries].map((type) => ({
    ...type,
    count: type.key
      ? available.value.filter(
          (database) =>
            String(
              database.databaseType ?? database.productType ?? 'unknown',
            ) === type.key,
        ).length
      : available.value.length,
  }));
});
const choices = computed(() =>
  available.value.filter(
    (database) =>
      !activeType.value ||
      String(database.databaseType ?? database.productType ?? 'unknown') ===
        activeType.value,
  ),
);
watch(open, async (visible) => {
  keyword.value = '';
  activeCategory.value = '';
  activeType.value = '';
  if (visible) {
    await nextTick();
    panel.value?.querySelector('input')?.focus();
  }
});
watch(keyword, () => {
  activeCategory.value = '';
  activeType.value = '';
});
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) open.value = false;
  },
);
function choose(database: ModelMaterializedApi.Database) {
  emit('update:value', database.id);
  emit('change', database.id);
  open.value = false;
  trigger.value?.focus();
}
function move(event: KeyboardEvent) {
  const buttons = [
    ...(event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>(
      'button:not(:disabled)',
    ),
  ];
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    buttons[
      (index + (event.key === 'ArrowDown' ? 1 : buttons.length - 1)) %
        buttons.length
    ]?.focus();
  }
}
</script>

<template>
  <Popover
    v-model:open="open"
    trigger="click"
    placement="bottomLeft"
    :disabled="disabled"
    :arrow="true"
    :destroy-on-hidden="true"
    overlay-class-name="materialized-database-popover"
  >
    <button
      ref="trigger"
      type="button"
      class="materialized-database-trigger"
      :disabled="disabled"
      :aria-controls="panelId"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @keydown.down.prevent="!disabled && (open = true)"
    >
      <span :class="{ 'is-placeholder': !selected }">{{
        selected?.name ||
        (value === undefined ? '请选择数据库连接' : `连接 ${value}`)
      }}</span><QdIcon name="arrowDown" />
    </button>
    <template #content>
      <div
        :id="panelId"
        ref="panel"
        class="materialized-database-panel"
        role="dialog"
        aria-label="选择数据库连接"
        @keydown.esc="
          open = false;
          trigger?.focus();
        "
      >
        <label class="materialized-database-search"><QdIcon name="search" /><input
            v-model="keyword"
            placeholder="请输入数据连接名称"
            aria-label="搜索数据连接"
        /></label>
        <div v-if="loading" class="materialized-database-state">加载中…</div>
        <div v-else-if="error" class="materialized-database-state">
          数据连接加载失败，<button type="button" @click="emit('retry')">
            重试
          </button>
        </div>
        <div v-else class="materialized-database-columns">
          <div
            class="materialized-database-column"
            role="group"
            aria-label="连接分类"
            @keydown="move"
          >
            <button
              v-for="category in categories"
              :key="category.code"
              type="button"
              :class="{ 'is-selected': activeCategory === category.code }"
              @click="
                activeCategory = category.code;
                activeType = '';
              "
            >
              <img
                v-if="categoryIcons[category.code]"
                :src="categoryIcons[category.code]"
                alt=""
              /><svg
                v-else
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                aria-hidden="true"
              >
                <path
                  :d="
                    CONNECTION_CATEGORY_ICONS[category.code] ||
                    CONNECTION_CATEGORY_ICONS.RELATIONAL
                  "
                /></svg><span>{{ category.name }}</span><small>({{ category.count }})</small>
            </button>
          </div>
          <div
            class="materialized-database-column"
            role="group"
            aria-label="数据库类型"
            @keydown="move"
          >
            <button
              v-for="type in types"
              :key="type.key"
              type="button"
              :class="{ 'is-selected': activeType === type.key }"
              :disabled="type.count === 0"
              @click="activeType = type.key"
            >
              <i
                class="materialized-radio"
                :class="{ 'is-selected': activeType === type.key }"
              ></i><ConnectionIcon
                v-if="type.key"
                :icon-path="type.iconPath"
                alt=""
              /><span>{{ type.name }}</span><small>({{ type.count }})</small>
            </button>
            <p class="materialized-database-type-count">
              共 有 {{ Math.max(0, types.length - 1) }} 种连接类型
            </p>
          </div>
          <div
            class="materialized-database-column"
            role="group"
            aria-label="数据连接"
            @keydown="move"
          >
            <button
              v-for="database in choices"
              :key="String(database.id)"
              type="button"
              :class="{ 'is-selected': String(database.id) === String(value) }"
              @click="choose(database)"
            >
              <i
                class="materialized-radio"
                :class="{
                  'is-selected': String(database.id) === String(value),
                }"
              ></i><ConnectionIcon
                :icon-path="
                  database.iconPath ||
                  definitions.get(String(database.databaseType))?.iconPath
                "
                alt=""
              /><span :title="database.name">{{
                database.name || database.id
              }}</span>
            </button>
            <p
              v-if="choices.length === 0"
              class="materialized-database-type-count"
            >
              暂无数据连接
            </p>
          </div>
        </div>
      </div>
    </template>
  </Popover>
</template>

<style>
.materialized-database-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 32px;
  padding: 0 12px;
  font: inherit;
  color: #606266;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: calc(var(--radius) - 2px);
}

.materialized-database-trigger:disabled {
  cursor: not-allowed;
  background: #f5f7fa;
}

.materialized-database-trigger .qd-icon {
  width: 14px;
  height: 14px;
  color: #c0c4cc;
}

.materialized-database-trigger .is-placeholder {
  color: #a8abb2;
}

.materialized-database-popover .ant-popover-container,
.materialized-database-popover .ant-popover-inner {
  padding: 12px;
  border-radius: var(--radius);
}

.materialized-database-panel {
  width: min(750px, calc(100vw - 48px));
  font:
    14px 'Helvetica Neue',
    'Microsoft YaHei',
    sans-serif;
  color: #333;
}

.materialized-database-search {
  display: flex;
  gap: 8px;
  align-items: center;
  height: 32px;
  padding: 0 10px;
  margin-bottom: 20px;
  border: 1px solid #dcdfe6;
  border-radius: calc(var(--radius) - 2px);
}

.materialized-database-search .qd-icon {
  width: 14px;
  height: 14px;
  color: #a8abb2;
}

.materialized-database-search input {
  flex: 1;
  width: 0;
  font: inherit;
  outline: 0;
  border: 0;
}

.materialized-database-search input::placeholder {
  color: #a8abb2;
}

.materialized-database-columns {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr 1.25fr;
  height: 346px;
}

.materialized-database-column {
  min-width: 0;
  overflow-y: auto;
  scrollbar-width: thin;
}

.materialized-database-column + .materialized-database-column {
  border-left: 1px solid #ebeef5;
}

.materialized-database-column button {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  min-height: 46px;
  padding: 8px 10px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 0;
}

.materialized-database-column button:hover {
  background: hsl(var(--accent));
}

.materialized-database-column button.is-selected {
  color: hsl(var(--primary));
  background: color-mix(in srgb, hsl(var(--primary)) 10%, hsl(var(--card)));
}

.materialized-database-column button:disabled {
  color: #909399;
  cursor: not-allowed;
}

.materialized-database-column button > svg,
.materialized-database-column button > img {
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
}

.materialized-database-column button > span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.materialized-database-column button > small {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.materialized-radio {
  flex: 0 0 14px;
  width: 14px;
  height: 14px;
  border: 1px solid #dcdfe6;
  border-radius: 50%;
}

.materialized-radio.is-selected {
  border: 5px solid #4c65ff;
}

.materialized-database-type-count {
  padding: 8px 10px;
  font-size: 12px;
  color: #909399;
}

.materialized-database-state {
  padding: 40px 20px;
  text-align: center;
}

.materialized-database-state button {
  color: #4c65ff;
}

.materialized-database-panel button:focus-visible,
.materialized-database-trigger:focus-visible {
  outline: 2px solid #4c65ff;
  outline-offset: -2px;
}

@media (max-width: 600px) {
  .materialized-database-columns {
    grid-template-columns: 1fr 1fr 1.2fr;
  }

  .materialized-database-column button {
    gap: 4px;
    padding: 8px 5px;
    font-size: 14px;
  }

  .materialized-database-column button > small {
    font-size: 12px;
  }
}
</style>
