<script lang="ts" setup>
import type {
  DatabaseChoice,
  DatabaseTypeGroup,
} from './database-select-options';

import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { computed, nextTick, ref, useId, useTemplateRef, watch } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { useElementSize } from '@vueuse/core';
import { Input, Popover } from 'antdv-next';

import { ConnectionIcon } from '#/components/connection-icon';
import { CONNECTION_CATEGORY_ICONS } from '#/components/connection-icon/category-icons';

import { buildDatabaseSelectGroups } from './database-select-options';

defineOptions({ inheritAttrs: false });
const props = withDefaults(
  defineProps<{
    catalog: FoundationDatabaseTypeApi.DatabaseType[];
    databases: SupplyDataApiApi.DatabaseSimple[];
    disabled?: boolean;
    error?: string;
    loading?: boolean;
    value?: null | SupplyDataApiApi.Id;
  }>(),
  { disabled: false, error: '', loading: false, value: undefined },
);
const emit = defineEmits<{
  change: [
    value: SupplyDataApiApi.Id | undefined,
    database?: SupplyDataApiApi.DatabaseSimple,
  ];
  retry: [];
  'update:value': [value: SupplyDataApiApi.Id | undefined];
}>();
const open = ref(false);
const keyword = ref('');
const ALL = '__ALL__';
const activeCategory = ref(ALL);
const activeType = ref(ALL);
const panel = useTemplateRef<HTMLDivElement>('panel');
const control = useTemplateRef<HTMLDivElement>('control');
const { width: controlWidth } = useElementSize(control, undefined, {
  box: 'border-box',
});
const trigger = useTemplateRef<HTMLButtonElement>('trigger');
const panelId = useId();
const hasValue = computed(
  () => props.value !== undefined && props.value !== null,
);
const categories = computed(() =>
  getDictOptions(DICT_TYPE.CATEGORY_CODE, 'string').map((item) => ({
    code: String(item.value),
    name: item.label,
  })),
);
const allGroups = computed(() =>
  buildDatabaseSelectGroups(props.databases, props.catalog, categories.value),
);
const groups = computed(() =>
  buildDatabaseSelectGroups(
    props.databases,
    props.catalog,
    categories.value,
    keyword.value,
  ),
);
const categoryRows = computed(() => [
  { code: ALL, name: '全部', count: total.value },
  ...groups.value,
]);
const types = computed(() =>
  groups.value
    .filter(
      (group) =>
        activeCategory.value === ALL || group.code === activeCategory.value,
    )
    .flatMap((group) =>
      group.types.map((type) => ({
        ...type,
        key: `${group.code}:${type.key}`,
      })),
    ),
);
const typeRows = computed<DatabaseTypeGroup[]>(() => [
  {
    key: ALL,
    name: '全部',
    databases: types.value.flatMap((type) => type.databases),
  },
  ...types.value,
]);
const choices = computed(
  () =>
    typeRows.value.find((type) => type.key === activeType.value)?.databases ??
    [],
);
const selected = computed(() =>
  hasValue.value
    ? allGroups.value
        .flatMap((group) => group.types.flatMap((type) => type.databases))
        .find((database) => database.key === String(props.value))
    : undefined,
);
const total = computed(() =>
  groups.value.reduce((count, group) => count + group.count, 0),
);
const selectedLabel = computed(() => {
  if (selected.value) return selected.value.name;
  if (!hasValue.value) return '请选择数据源';
  return `连接 ${props.value}${props.loading ? '（加载中）' : '（不可用）'}`;
});

function alignColumns() {
  const current = categoryRows.value.find(
    (group) => group.code === activeCategory.value,
  );
  if (!current || (current.count === 0 && total.value > 0))
    activeCategory.value = ALL;
  const currentType = typeRows.value.find(
    (type) => type.key === activeType.value,
  );
  if (
    !currentType ||
    (activeType.value !== ALL && currentType.databases.length === 0)
  )
    activeType.value = ALL;
}

function followSelected() {
  activeCategory.value = ALL;
  activeType.value = ALL;
  if (!hasValue.value) return;
  for (const group of allGroups.value) {
    const type = group.types.find((item) =>
      item.databases.some((database) => database.key === String(props.value)),
    );
    if (type) {
      activeCategory.value = group.code;
      activeType.value = `${group.code}:${type.key}`;
      return;
    }
  }
}

watch(groups, alignColumns, { immediate: true });
watch([allGroups, () => props.value], () => {
  if (open.value && !keyword.value.trim()) followSelected();
});
watch(keyword, () => {
  activeCategory.value = ALL;
  activeType.value = ALL;
});
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) open.value = false;
  },
);
watch(open, async (visible) => {
  keyword.value = '';
  if (visible) {
    followSelected();
    await nextTick();
    panel.value?.querySelector('input')?.focus();
  }
});

function selectCategory(code: string) {
  activeCategory.value = code;
  activeType.value = ALL;
  alignColumns();
}

function selectType(type: DatabaseTypeGroup) {
  if (type.key === ALL || type.databases.length > 0)
    activeType.value = type.key;
}

function selectDatabase(database: DatabaseChoice) {
  if (props.disabled || props.loading || props.error) return;
  if (String(props.value) !== database.key) {
    emit('update:value', database.id);
    emit('change', database.id, database.source);
  }
  open.value = false;
  trigger.value?.focus();
}

function clear() {
  if (props.disabled) return;
  emit('update:value', undefined);
  emit('change', undefined);
  open.value = false;
  trigger.value?.focus();
}

function closeAndFocus() {
  open.value = false;
  trigger.value?.focus();
}

function handleTriggerKeydown(event: KeyboardEvent) {
  if (event.key !== 'ArrowDown' || props.disabled) return;
  event.preventDefault();
  open.value = true;
}

function handleListKeydown(event: KeyboardEvent) {
  const column = event.currentTarget as HTMLElement;
  const buttons = [
    ...column.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'),
  ];
  const current = buttons.indexOf(document.activeElement as HTMLButtonElement);
  let target: HTMLButtonElement | undefined;
  if (event.key === 'ArrowDown')
    target = buttons[(current + 1) % buttons.length];
  if (event.key === 'ArrowUp')
    target = buttons[(current - 1 + buttons.length) % buttons.length];
  if (event.key === 'Home') target = buttons[0];
  if (event.key === 'End') target = buttons.at(-1);
  if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    const sibling =
      event.key === 'ArrowRight'
        ? column.nextElementSibling
        : column.previousElementSibling;
    target =
      sibling?.querySelector<HTMLButtonElement>(
        'button[tabindex="0"]:not(:disabled)',
      ) ??
      sibling?.querySelector<HTMLButtonElement>('button:not(:disabled)') ??
      undefined;
  }
  if (target) {
    event.preventDefault();
    target.focus();
  }
}

function focusFirstCategory(event: KeyboardEvent) {
  if (event.key !== 'ArrowDown') return;
  const first = panel.value?.querySelector<HTMLButtonElement>(
    '[data-category-code]:not(:disabled)',
  );
  if (first) {
    event.preventDefault();
    first.focus();
  }
}
</script>

<template>
  <div
    v-bind="$attrs"
    class="database-select"
    data-testid="dataapi-database-picker"
  >
    <Popover
      v-model:open="open"
      :arrow="false"
      placement="bottomLeft"
      :trigger="props.disabled ? [] : ['click']"
      :styles="{ container: { padding: 0 } }"
    >
      <div
        ref="control"
        class="database-select__control"
        :class="{ 'is-disabled': props.disabled }"
      >
        <button
          ref="trigger"
          type="button"
          role="combobox"
          aria-label="数据源"
          aria-haspopup="dialog"
          :aria-expanded="open"
          :aria-controls="panelId"
          :disabled="props.disabled"
          :title="selectedLabel"
          class="database-select__trigger"
          @keydown="handleTriggerKeydown"
        >
          <ConnectionIcon
            v-if="selected"
            :icon-path="selected.iconPath"
            :type-code="selected.typeCode"
            alt=""
            class="size-4 shrink-0"
          />
          <span :class="{ 'is-placeholder': !hasValue }">{{
            selectedLabel
          }}</span>
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            aria-hidden="true"
            class="database-select__chevron"
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        </button>
        <button
          v-if="hasValue"
          type="button"
          :disabled="props.disabled"
          aria-label="清除数据源"
          class="database-select__clear"
          @click.stop="clear"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            aria-hidden="true"
          >
            <path d="m6 6 12 12M6 18 18 6" />
          </svg>
        </button>
      </div>
      <template #content>
        <div
          :id="panelId"
          ref="panel"
          role="dialog"
          aria-label="选择数据源"
          class="database-select__panel"
          :style="{
            '--database-picker-trigger-width':
              controlWidth > 0 ? `${controlWidth}px` : undefined,
          }"
          data-testid="dataapi-database-picker-panel"
          @keydown.esc.stop.prevent="closeAndFocus"
        >
          <div class="database-select__search">
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="请输入数据连接名称"
              aria-label="搜索连接名称"
              :disabled="props.loading || !!props.error"
              @keydown="focusFirstCategory"
            >
              <template #prefix>
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  aria-hidden="true"
                  class="database-select__icon"
                >
                  <circle cx="10.5" cy="10.5" r="6.5" />
                  <path d="m16 16 4 4" />
                </svg>
              </template>
            </Input>
          </div>
          <div
            v-if="props.loading"
            role="status"
            class="database-select__message"
          >
            正在加载数据源…
          </div>
          <template v-else-if="props.error"></template>
          <div v-else class="database-select__columns">
            <section
              role="listbox"
              aria-label="连接分类"
              class="database-select__column"
              data-picker-column="category"
              @keydown="handleListKeydown"
            >
              <div class="database-select__heading">连接分类</div>
              <button
                v-for="category in categoryRows"
                :key="category.code"
                type="button"
                role="option"
                :aria-selected="activeCategory === category.code"
                :aria-label="`${category.name}，${category.count} 个连接`"
                :data-category-code="category.code"
                :disabled="category.code !== ALL && category.count === 0"
                :tabindex="activeCategory === category.code ? 0 : -1"
                :title="category.name"
                class="database-select__option"
                @click="selectCategory(category.code)"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  aria-hidden="true"
                  class="database-select__icon"
                >
                  <path
                    :d="
                      CONNECTION_CATEGORY_ICONS[category.code] ||
                      'M3 3h6v6H3V3Zm12 0h6v6h-6V3ZM3 15h6v6H3v-6Zm12 0h6v6h-6v-6Z'
                    "
                  />
                </svg>
                <span class="database-select__name">{{ category.name }}</span>
                <span class="database-select__count">{{ category.count }}</span>
                <span aria-hidden="true">›</span>
              </button>
            </section>
            <section
              role="listbox"
              aria-label="连接类型"
              class="database-select__column"
              data-picker-column="type"
              @keydown="handleListKeydown"
            >
              <div class="database-select__heading">连接类型</div>
              <button
                v-for="type in typeRows"
                :key="type.key"
                type="button"
                role="option"
                :aria-selected="activeType === type.key"
                :aria-label="`${type.name}，${type.databases.length} 个连接`"
                :data-type-id="type.id ?? type.key"
                :disabled="type.key !== ALL && type.databases.length === 0"
                :tabindex="activeType === type.key ? 0 : -1"
                :title="type.name"
                class="database-select__option"
                @click="selectType(type)"
              >
                <ConnectionIcon
                  :icon-path="type.iconPath"
                  :type-code="type.typeCode"
                  alt=""
                  class="size-4 shrink-0"
                />
                <span class="database-select__name">{{ type.name }}</span>
                <span class="database-select__count">{{
                  type.databases.length
                }}</span>
                <span aria-hidden="true">›</span>
              </button>
              <p v-if="types.length === 0" class="database-select__empty">
                此分类暂无可用类型
              </p>
            </section>
            <section
              role="listbox"
              aria-label="数据连接"
              class="database-select__column"
              data-picker-column="database"
              @keydown="handleListKeydown"
            >
              <div class="database-select__heading">数据连接</div>
              <button
                v-for="(database, index) in choices"
                :key="database.key"
                type="button"
                role="option"
                :aria-selected="selected?.key === database.key"
                :data-database-id="database.key"
                :tabindex="
                  selected?.key === database.key ||
                  (!choices.some((item) => item.key === selected?.key) &&
                    index === 0)
                    ? 0
                    : -1
                "
                :title="database.name"
                class="database-select__option"
                @click="selectDatabase(database)"
              >
                <ConnectionIcon
                  :icon-path="database.iconPath"
                  :type-code="database.typeCode"
                  alt=""
                  class="size-4 shrink-0"
                />
                <span class="database-select__name">{{ database.name }}</span>
                <span
                  v-if="selected?.key === database.key"
                  aria-hidden="true"
                  class="database-select__check"
                  >✓</span>
              </button>
              <p v-if="choices.length === 0" class="database-select__empty">
                {{ keyword.trim() ? '没有匹配的连接' : '暂无可选连接' }}
              </p>
            </section>
          </div>
          <div class="database-select__footer" role="status">
            共 {{ total }} 个连接
          </div>
        </div>
      </template>
    </Popover>
  </div>
</template>

<style scoped>
.database-select {
  width: 100%;
  min-width: 0;
}

.database-select__control {
  display: flex;
  align-items: center;
  width: 100%;
  height: 32px;
  background: hsl(var(--background));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.database-select__control:focus-within {
  border-color: hsl(var(--primary));
  box-shadow: 0 0 0 2px hsl(var(--primary) / 12%);
}

.database-select__trigger {
  display: flex;
  flex: 1;
  gap: 8px;
  align-items: center;
  min-width: 0;
  height: 100%;
  padding: 0 10px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.database-select__trigger > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.database-select__trigger:focus-visible {
  outline: none;
}

.database-select__icon {
  flex: none;
  width: 16px;
  height: 16px;
  stroke-width: 1.6;
}

.database-select__chevron {
  flex: none;
  width: 14px;
  height: 14px;
  margin-left: auto;
  color: hsl(var(--muted-foreground));
}

.database-select__clear {
  flex: none;
  width: 24px;
  height: 100%;
  padding: 5px;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  background: transparent;
  border: 0;
}

.database-select__clear svg {
  width: 14px;
  height: 14px;
}

.database-select__panel {
  width: min(780px, calc(100vw - 32px));
  color: hsl(var(--foreground));
  background: hsl(var(--background));
  border-radius: 6px;
}

.database-select__search {
  padding: 12px;
  border-bottom: 1px solid hsl(var(--border));
}

.database-select__columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1.2fr);
}

.database-select__column {
  min-width: 0;
  height: 316px;
  padding: 6px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.database-select__column + .database-select__column {
  border-left: 1px solid hsl(var(--border));
}

.database-select__heading {
  padding: 5px 8px 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.database-select__option {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-width: 0;
  min-height: 32px;
  padding: 6px 8px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 4px;
}

.database-select__option:hover:not(:disabled),
.database-select__option:focus-visible {
  outline: 2px solid hsl(var(--primary) / 40%);
  outline-offset: -2px;
  background: hsl(var(--muted));
}

.database-select__option[aria-selected='true'] {
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
}

.database-select__option:disabled {
  color: hsl(var(--muted-foreground));
  cursor: not-allowed;
  opacity: 0.55;
}

.database-select__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.database-select__count {
  flex: none;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.database-select__check {
  margin-left: auto;
  font-weight: 600;
}

.database-select__empty {
  padding: 16px 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.database-select__message {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  min-height: 180px;
  padding: 20px;
  overflow-wrap: anywhere;
}

.database-select__retry {
  color: hsl(var(--primary));
  cursor: pointer;
  background: transparent;
  border: 0;
}

.database-select__footer {
  padding: 8px 12px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  border-top: 1px solid hsl(var(--border));
}

.is-placeholder {
  color: hsl(var(--muted-foreground));
}

.is-disabled {
  cursor: not-allowed;
  background: hsl(var(--muted) / 40%);
  opacity: 0.65;
}

.is-disabled button {
  cursor: not-allowed;
}

@media (width <= 600px) {
  .database-select__panel {
    width: min(
      var(--database-picker-trigger-width, calc(100vw - 32px)),
      calc(100vw - 32px)
    );
    max-height: min(640px, calc(100dvh - 80px));
    overflow-y: auto;
  }

  .database-select__columns {
    grid-template-columns: minmax(0, 1fr);
  }

  .database-select__column {
    height: auto;
    max-height: 150px;
  }

  .database-select__column + .database-select__column {
    border-top: 1px solid hsl(var(--border));
    border-left: 0;
  }
}
</style>
