<script setup lang="ts">
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { computed, ref, watch } from 'vue';

import { Button, Input, Popover, Spin } from 'antdv-next';

import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';
import { ConnectionIcon } from '#/components/connection-icon';
import { CONNECTION_CATEGORY_ICONS } from '#/components/connection-icon/category-icons';
import { useObjectRequest } from '#/hooks/use-object-request';

const props = withDefaults(
  defineProps<{
    databases: FoundationDatabaseApi.DatabaseSimple[];
    disabled?: boolean;
    loading?: boolean;
    placeholder?: string;
    value?: number | string;
  }>(),
  {
    disabled: false,
    loading: false,
    placeholder: '请选择源数据库连接',
    value: undefined,
  },
);
const emit = defineEmits<{
  change: [value: string | undefined];
  'update:value': [value: string | undefined];
}>();
const open = ref(false);
const keyword = ref('');
const category = ref('');
const type = ref('');
const types = ref<FoundationDatabaseTypeApi.DatabaseType[]>([]);
const typesLoading = ref(false);
const typeError = ref(false);
const request = useObjectRequest(
  () => open.value,
  () => {
    types.value = [];
    typesLoading.value = false;
    typeError.value = false;
  },
);
const categories = [
  { code: '', name: '全部' },
  { code: 'RELATIONAL', name: '关系型数据库' },
  { code: 'ANALYTICAL', name: '分析型数据库/数据仓库' },
  { code: 'OTHER', name: '其他连接' },
];
const typeById = computed(
  () => new Map(types.value.map((item) => [String(item.id), item])),
);
function databaseType(database: FoundationDatabaseApi.DatabaseSimple) {
  return (
    typeById.value.get(String(database.databaseType)) ||
    types.value.find(
      (item) =>
        !!database.productId &&
        item.productId?.toUpperCase() === database.productId.toUpperCase(),
    )
  );
}
function categoryCode(code?: string) {
  return code === 'RELATIONAL' || code === 'ANALYTICAL' ? code : 'OTHER';
}
const connections = computed(() =>
  props.databases.map((database) => {
    const meta = databaseType(database);
    return {
      database,
      category: categoryCode(meta?.categoryCode),
      key: meta
        ? `type:${meta.id}`
        : `product:${database.productId || database.databaseTypeName || 'unknown'}`,
      name:
        meta?.typeName ||
        database.databaseTypeName ||
        database.productId ||
        '未知连接类型',
      productId: meta?.typeCode || database.productId,
      iconPath: meta?.iconPath,
    };
  }),
);
const selected = computed(() =>
  connections.value.find(
    (item) => String(item.database.id) === String(props.value),
  ),
);
const searched = computed(() => {
  const search = keyword.value.trim().toLocaleLowerCase();
  return connections.value.filter(
    (item) =>
      !search ||
      [item.database.name, item.database.id, item.name, item.productId].some(
        (value) =>
          String(value ?? '')
            .toLocaleLowerCase()
            .includes(search),
      ),
  );
});
const visibleCategories = computed(() =>
  categories.filter(
    (item) =>
      item.code !== 'OTHER' ||
      connections.value.some((connection) => connection.category === 'OTHER'),
  ),
);
const categoryConnections = computed(() =>
  searched.value.filter(
    (item) => !category.value || item.category === category.value,
  ),
);
const typeOptions = computed(() => {
  const options = new Map<
    string,
    {
      category: string;
      count: number;
      iconPath?: string;
      key: string;
      name: string;
      productId?: string;
    }
  >();
  for (const item of types.value) {
    const group = categoryCode(item.categoryCode);
    if (category.value && group !== category.value) continue;
    const key = `type:${item.id}`;
    options.set(key, {
      key,
      name: item.typeName,
      productId: item.typeCode || item.productId,
      iconPath: item.iconPath,
      category: group,
      count: 0,
    });
  }
  for (const item of categoryConnections.value) {
    const existing = options.get(item.key);
    if (existing) existing.count++;
    else options.set(item.key, { ...item, count: 1 });
  }
  return [...options.values()];
});
const visibleConnections = computed(() =>
  categoryConnections.value.filter(
    (item) => !type.value || item.key === type.value,
  ),
);
function countCategory(code: string) {
  return searched.value.filter((item) => !code || item.category === code)
    .length;
}
function selectCategory(code: string) {
  category.value = code;
  type.value = '';
}
function select(value: string | undefined) {
  if (props.disabled || props.loading) return;
  emit('update:value', value);
  emit('change', value);
  open.value = false;
}
function setOpen(value: boolean) {
  if (value && (props.disabled || props.loading)) return;
  if (value && !open.value) {
    keyword.value = '';
    category.value = selected.value?.category ?? '';
    type.value = selected.value?.key ?? '';
  }
  open.value = value;
}
async function loadTypes() {
  const current = request.begin();
  const navigation = JSON.stringify([keyword.value, category.value, type.value]);
  typesLoading.value = true;
  typeError.value = false;
  try {
    const result = await getDatabaseTypeSimpleList(undefined, {
      silentError: true,
    });
    if (current()) {
      types.value = result;
      const currentSelection = selected.value;
      if (
        currentSelection &&
        navigation ===
          JSON.stringify([keyword.value, category.value, type.value])
      ) {
        category.value = currentSelection.category;
        type.value = currentSelection.key;
      }
    }
  } catch {
    if (current()) typeError.value = true;
  } finally {
    if (current()) typesLoading.value = false;
  }
}
watch(open, (value) => {
  if (value) void loadTypes();
});
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) open.value = false;
  },
);
watch(keyword, () => {
  category.value = '';
  type.value = '';
});
</script>

<template>
  <Popover
    :open="open"
    trigger="click"
    placement="bottomLeft"
    @update:open="setOpen"
  >
    <template #content>
      <div
        class="qi-connection-picker"
        role="dialog"
        aria-label="选择数据库连接"
        @keydown.esc="setOpen(false)"
      >
        <Input
          v-model:value="keyword"
          allow-clear
          placeholder="请输入数据连接名称"
          aria-label="搜索数据连接"
        />
        <div v-if="typeError" class="qi-connection-picker__error" role="status">
          连接类型加载失败，仍可从全部连接中选择。
          <Button type="link" size="small" @click="loadTypes">重试</Button>
        </div>
        <Spin :spinning="typesLoading || loading">
          <div class="qi-connection-picker__columns">
            <nav
              class="qi-connection-picker__categories"
              aria-label="数据库连接分类"
            >
              <button
                v-for="item in visibleCategories"
                :key="item.code"
                type="button"
                :class="{ 'is-active': category === item.code }"
                :aria-pressed="category === item.code"
                @click="selectCategory(item.code)"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.7"
                  aria-hidden="true"
                >
                  <path
                    :d="
                      CONNECTION_CATEGORY_ICONS[item.code] ||
                      'M3 3h6v6H3V3Zm12 0h6v6h-6V3ZM3 15h6v6H3v-6Zm12 0h6v6h-6v-6Z'
                    "
                  />
                </svg>
                <span>{{ item.name }}</span><small>({{ countCategory(item.code) }})</small>
              </button>
            </nav>
            <div
              class="qi-connection-picker__types"
              aria-label="数据库连接类型"
            >
              <button
                type="button"
                :class="{ 'is-active': !type }"
                :aria-pressed="!type"
                @click="type = ''"
              >
                <i
                  class="qi-connection-picker__radio"
                  :class="{ 'is-checked': !type }"
                ></i><span>全部</span><small>({{ categoryConnections.length }})</small>
              </button>
              <button
                v-for="item in typeOptions"
                :key="item.key"
                type="button"
                :class="{ 'is-active': type === item.key }"
                :aria-pressed="type === item.key"
                @click="type = item.key"
              >
                <i
                  class="qi-connection-picker__radio"
                  :class="{ 'is-checked': type === item.key }"
                ></i><ConnectionIcon
                  :type-code="item.productId"
                  :icon-path="item.iconPath"
                  alt=""
                /><span>{{ item.name }}</span><small>({{ item.count }})</small>
              </button>
              <p>当前分类有 {{ typeOptions.length }} 种连接类型</p>
            </div>
            <div
              class="qi-connection-picker__connections"
              role="listbox"
              aria-label="可选数据库连接"
            >
              <button
                v-for="item in visibleConnections"
                :key="String(item.database.id)"
                type="button"
                role="option"
                :aria-selected="String(value) === String(item.database.id)"
                :class="{
                  'is-active': String(value) === String(item.database.id),
                }"
                :disabled="disabled || loading"
                @click="select(String(item.database.id))"
              >
                <i
                  class="qi-connection-picker__radio"
                  :class="{
                    'is-checked': String(value) === String(item.database.id),
                  }"
                ></i><ConnectionIcon
                  :type-code="item.productId"
                  :icon-path="item.iconPath"
                  alt=""
                /><span>{{ item.database.name || item.database.id }}</span>
              </button>
              <p
                v-if="!visibleConnections.length"
                class="qi-connection-picker__empty"
              >
                {{ keyword ? '没有找到匹配的数据连接' : '暂无数据连接' }}
              </p>
            </div>
          </div>
        </Spin>
      </div>
    </template>
    <div class="qi-connection-picker__control">
      <button
        class="qi-connection-picker__trigger"
        type="button"
        role="combobox"
        aria-haspopup="dialog"
        :aria-expanded="open"
        :disabled="disabled || loading"
        aria-label="节点数据连接"
        @keydown.down.prevent="setOpen(true)"
        @keydown.esc="setOpen(false)"
      >
        <ConnectionIcon
          v-if="selected"
          :type-code="selected.productId"
          :icon-path="selected.iconPath"
          alt=""
        />
        <span
          :class="{
            'is-placeholder':
              value === undefined || value === null || value === '',
          }"
          >{{
          selected?.database.name ||
          selected?.database.id ||
            (value === undefined || value === null || value === ''
              ? placeholder
              : `未知连接（${value}）`)
          }}</span>
        <span
          class="qi-connection-picker__chevron"
          :class="{ 'is-open': open }"
          aria-hidden="true"
        ></span>
      </button>
      <button
        v-if="value !== undefined && value !== null && value !== ''"
        class="qi-connection-picker__clear"
        type="button"
        aria-label="清除数据连接"
        :disabled="disabled || loading"
        @click.stop="select(undefined)"
      >
        ×
      </button>
    </div>
  </Popover>
</template>

<style scoped>
.qi-connection-picker {
  width: min(740px, calc(100vw - 48px));
  padding: 8px;
  color: #333;
}

.qi-connection-picker__columns {
  display: grid;
  grid-template-columns: 1fr 0.9fr 1.1fr;
  margin-top: 20px;
  overflow: hidden;
  border: 1px solid #eceef3;
  border-radius: 3px;
}

.qi-connection-picker__columns > * {
  min-width: 0;
  height: 340px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.qi-connection-picker__columns > * + * {
  border-left: 1px solid #eceef3;
}

.qi-connection-picker__columns button {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  min-height: 44px;
  padding: 10px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.qi-connection-picker__columns button:hover,
.qi-connection-picker__columns button.is-active {
  color: #2d63ff;
  background: #f3f5ff;
}

.qi-connection-picker__columns button:focus-visible,
.qi-connection-picker__trigger:focus-visible,
.qi-connection-picker__clear:focus-visible {
  outline: 2px solid #2d63ff;
  outline-offset: -2px;
}

.qi-connection-picker__columns button span {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}

.qi-connection-picker__columns small {
  margin-left: auto;
  font-size: 13px;
  color: #999;
}

.qi-connection-picker__columns svg,
.qi-connection-picker__columns img,
.qi-connection-picker__trigger img,
.qi-connection-picker__trigger svg {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
}

.qi-connection-picker__radio {
  display: inline-block;
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  background: #fff;
  border: 1px solid #dfe3ec;
  border-radius: 50%;
}

.qi-connection-picker__radio.is-checked {
  border: 5px solid #2d63ff;
}

.qi-connection-picker__types p,
.qi-connection-picker__empty {
  padding: 12px 10px;
  margin: 0;
  font-size: 12px;
  color: #999;
}

.qi-connection-picker__error {
  padding-top: 8px;
  font-size: 12px;
  color: #a66c00;
}

.qi-connection-picker__control {
  position: relative;
  width: 100%;
  min-width: 0;
}

.qi-connection-picker__trigger {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  min-height: 32px;
  padding: 4px 34px 4px 12px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #d9dce4;
  border-radius: 2px;
}

.qi-connection-picker__trigger > span:first-of-type {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qi-connection-picker__trigger .is-placeholder {
  color: #a5a9b4;
}

.qi-connection-picker__trigger:disabled,
.qi-connection-picker__clear:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.qi-connection-picker__chevron {
  position: absolute;
  right: 13px;
  width: 7px;
  height: 7px;
  border-right: 1px solid #a5a9b4;
  border-bottom: 1px solid #a5a9b4;
  transform: translateY(-2px) rotate(45deg);
}

.qi-connection-picker__chevron.is-open {
  transform: translateY(2px) rotate(225deg);
}

.qi-connection-picker__clear {
  position: absolute;
  top: 50%;
  right: 28px;
  width: 20px;
  height: 24px;
  padding: 0;
  color: #999;
  background: #fff;
  border: 0;
  transform: translateY(-50%);
}

@media (max-width: 600px) {
  .qi-connection-picker {
    width: calc(100vw - 40px);
    padding: 0;
  }

  .qi-connection-picker__columns {
    grid-template-columns: 1fr 1fr;
    margin-top: 12px;
  }

  .qi-connection-picker__columns > .qi-connection-picker__categories {
    display: flex;
    grid-column: 1 / -1;
    height: auto;
    overflow-x: auto;
    border-bottom: 1px solid #eceef3;
  }

  .qi-connection-picker__categories button {
    flex-shrink: 0;
    width: auto;
    white-space: nowrap;
  }

  .qi-connection-picker__columns button {
    gap: 6px;
    padding: 10px 8px;
  }

  .qi-connection-picker__columns > .qi-connection-picker__types {
    border-left: 0;
  }
}
</style>
