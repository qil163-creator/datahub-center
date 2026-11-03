<script lang="ts" setup>
import type { DatabaseTypeCategory } from '../composables/use-database-type-select';

import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { computed, nextTick, ref } from 'vue';

import { Input } from 'antdv-next';

import { ConnectionIcon } from '#/components/connection-icon';
import { CONNECTION_CATEGORY_ICONS as categoryIcons } from '#/components/connection-icon/category-icons';

import { isDatabaseTypeSelectable } from '../composables/use-database-type-select';

const props = withDefaults(
  defineProps<{
    activeCategory: string;
    allTypes: FoundationDatabaseTypeApi.DatabaseType[];
    categories: DatabaseTypeCategory[];
    searchKeyword: string;
    selectedType?: FoundationDatabaseTypeApi.DatabaseType;
    types: FoundationDatabaseTypeApi.DatabaseType[];
  }>(),
  { selectedType: undefined },
);
const emit = defineEmits<{
  activeChange: [code: string];
  navigate: [code: string];
  select: [type: FoundationDatabaseTypeApi.DatabaseType];
  'update:searchKeyword': [keyword: string];
}>();
const content = ref<HTMLElement>();
const pendingAnchor = ref<null | string>(null);
const groups = computed(() => {
  const result = props.categories
    .filter((category) => category.code)
    .map((category) => ({
      ...category,
      types: props.types.filter((type) => type.categoryCode === category.code),
    }));
  const unclassified = props.types.filter((type) => !type.categoryCode);
  if (unclassified.length > 0)
    result.push({ code: '__other__', name: '其他连接', types: unclassified });
  return result.filter((group) => group.types.length > 0);
});

function countCategory(code: string) {
  return code
    ? props.allTypes.filter((type) => type.categoryCode === code).length
    : props.allTypes.length;
}

async function navigate(code: string) {
  emit('navigate', code);
  await nextTick();
  const container = content.value;
  if (!container) return;
  const group = [
    ...container.querySelectorAll<HTMLElement>('[data-connection-category]'),
  ].find((element) => element.dataset.connectionCategory === code);
  const top =
    code && group
      ? container.scrollTop +
        group.getBoundingClientRect().top -
        container.getBoundingClientRect().top
      : 0;
  pendingAnchor.value = code;
  container.scrollTo({
    top,
    behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
      ? 'auto'
      : 'smooth',
  });
  if (Math.abs(container.scrollTop - top) < 2) pendingAnchor.value = null;
}

function syncActiveCategory() {
  const container = content.value;
  if (!container) return;
  const sections = [
    ...container.querySelectorAll<HTMLElement>('[data-connection-category]'),
  ];
  const top = container.getBoundingClientRect().top;
  if (pendingAnchor.value !== null) {
    const target = sections.find(
      (element) => element.dataset.connectionCategory === pendingAnchor.value,
    );
    const arrived =
      pendingAnchor.value === ''
        ? container.scrollTop < 2
        : !!target && Math.abs(target.getBoundingClientRect().top - top) < 4;
    const atBottom =
      container.scrollTop >=
      container.scrollHeight - container.clientHeight - 2;
    if (arrived || atBottom) pendingAnchor.value = null;
    return;
  }
  if (container.scrollTop < 2) return;
  let active = sections[0]?.dataset.connectionCategory;
  for (const section of sections) {
    if (section.getBoundingClientRect().top <= top + 60)
      active = section.dataset.connectionCategory;
  }
  if (active && active !== props.activeCategory) emit('activeChange', active);
}
</script>

<template>
  <div class="connection-catalog">
    <nav class="connection-catalog__categories" aria-label="连接分类">
      <button
        v-for="category in categories"
        :key="category.code"
        type="button"
        class="connection-catalog__category"
        :class="{
          'connection-catalog__category--active':
            activeCategory === category.code,
        }"
        :aria-current="activeCategory === category.code ? 'true' : undefined"
        :disabled="!!category.code && countCategory(category.code) === 0"
        @click="navigate(category.code)"
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
              category.code
                ? categoryIcons[category.code] || categoryIcons.RELATIONAL
                : 'M3 3h6v6H3V3Zm12 0h6v6h-6V3ZM3 15h6v6H3v-6Zm12 0h6v6h-6v-6Z'
            "
          />
        </svg>
        <span class="connection-catalog__category-name">{{
          category.code === 'ANALYTICAL' ? '分析性数据库' : category.name
        }}</span>
        <span class="connection-catalog__count">({{ countCategory(category.code) }})</span>
      </button>
    </nav>
    <div class="connection-catalog__main">
      <div class="connection-catalog__search">
        <Input
          :value="searchKeyword"
          allow-clear
          aria-label="搜索全部连接类型"
          placeholder="搜索数据源类型，如 MySQL、Oracle"
          @update:value="emit('update:searchKeyword', $event)"
        >
          <template #prefix>
            <svg
              class="connection-catalog__search-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
              aria-hidden="true"
            >
              <circle cx="10.5" cy="10.5" r="6.5" />
              <path d="m16 16 5 5" />
            </svg>
          </template>
        </Input>
      </div>
      <div
        ref="content"
        class="connection-catalog__groups"
        aria-label="数据连接类型"
        @scroll.passive="syncActiveCategory"
        @scrollend="pendingAnchor = null"
        @wheel.passive="pendingAnchor = null"
        @touchstart.passive="pendingAnchor = null"
      >
        <section
          v-for="group in groups"
          :key="group.code"
          :data-connection-category="group.code"
          class="connection-catalog__group"
        >
          <h3>{{ group.name }}</h3>
          <div class="connection-catalog__cards">
            <button
              v-for="type in group.types"
              :key="type.id"
              :data-type-id="String(type.id)"
              type="button"
              class="connection-catalog__card"
              :class="{
                'connection-catalog__card--selected':
                  String(selectedType?.id) === String(type.id),
              }"
              :aria-pressed="String(selectedType?.id) === String(type.id)"
              :disabled="!isDatabaseTypeSelectable(type)"
              :title="
                isDatabaseTypeSelectable(type)
                  ? type.typeName
                  : '当前部署未提供此连接类型的元数据能力'
              "
              @click="emit('select', type)"
            >
              <ConnectionIcon
                :icon-path="type.iconPath"
                :type-code="type.typeCode || type.productId"
                alt=""
                class="size-[34px] shrink-0"
              />
              <span class="connection-catalog__text">
                <span class="connection-catalog__name">{{
                  type.typeName
                }}</span>
                <span
                  v-if="type.description"
                  class="connection-catalog__description"
                  >{{ type.description }}</span>
              </span>
            </button>
          </div>
        </section>
        <div v-if="groups.length === 0" class="connection-catalog__empty">
          {{
            searchKeyword
              ? '没有找到匹配的连接类型，请尝试其他关键词。'
              : '暂无连接类型。'
          }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.connection-catalog {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--connection-border, #e8ebf1);
}

.connection-catalog__categories {
  flex-shrink: 0;
  width: 282px;
  padding: 16px 14px;
  overflow-y: auto;
  border-right: 1px solid var(--connection-border, #e8ebf1);
}

.connection-catalog__category {
  display: flex;
  gap: 12px;
  align-items: center;
  width: 100%;
  min-height: 42px;
  padding: 10px 12px;
  margin-bottom: 3px;
  text-align: left;
  background: transparent;
  border-radius: 3px;
}

.connection-catalog__category > svg {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
}

.connection-catalog__category-name {
  flex: 1;
}

.connection-catalog__count {
  font-variant-numeric: tabular-nums;
  color: hsl(var(--muted-foreground));
}

.connection-catalog__category--active {
  color: #4964ff;
  background: var(--connection-selected, #f2f4ff);
}

.connection-catalog__category:disabled {
  color: hsl(var(--muted-foreground));
  cursor: default;
}

.connection-catalog__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.connection-catalog__search {
  display: flex;
  flex-shrink: 0;
  justify-content: flex-end;
  padding: 16px 20px 4px;
}

.connection-catalog__search :deep(.ant-input-affix-wrapper) {
  width: min(100%, 360px);
  border-radius: 2px;
}

.connection-catalog__search-icon {
  width: 17px;
  height: 17px;
  margin-right: 4px;
  color: hsl(var(--muted-foreground));
}

.connection-catalog__groups {
  flex: 1;
  min-height: 0;
  padding: 8px 20px 24px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.connection-catalog__group + .connection-catalog__group {
  margin-top: 28px;
}

.connection-catalog__group h3 {
  margin: 0 0 16px;
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
}

.connection-catalog__cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.connection-catalog__card {
  display: flex;
  gap: 15px;
  align-items: center;
  min-width: 0;
  min-height: 96px;
  padding: 16px;
  text-align: left;
  background: hsl(var(--background));
  border: 1px solid var(--connection-border, #e8ebf1);
  border-radius: 3px;
}

.connection-catalog__card:hover:not(:disabled),
.connection-catalog__card--selected {
  border-color: #4964ff;
  box-shadow: 0 2px 10px rgb(73 100 255 / 12%);
}

.connection-catalog__card:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.connection-catalog__category:focus-visible,
.connection-catalog__card:focus-visible {
  outline: 2px solid #4964ff;
  outline-offset: 2px;
}

.connection-catalog__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.connection-catalog__name {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  line-height: 21px;
  white-space: nowrap;
}

.connection-catalog__description {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  font-size: 12px;
  line-height: 19px;
  color: hsl(var(--muted-foreground));
  -webkit-box-orient: vertical;
}

.connection-catalog__empty {
  padding: 56px 20px;
  color: hsl(var(--muted-foreground));
  text-align: center;
}

@media (max-width: 1050px) {
  .connection-catalog__categories {
    width: 208px;
    padding: 12px 8px;
  }

  .connection-catalog__category {
    gap: 8px;
    padding-right: 8px;
    padding-left: 8px;
  }

  .connection-catalog__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .connection-catalog {
    flex-direction: column;
  }

  .connection-catalog__categories {
    display: flex;
    gap: 4px;
    width: 100%;
    padding: 8px;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid var(--connection-border, #e8ebf1);
  }

  .connection-catalog__category {
    flex-shrink: 0;
    gap: 6px;
    width: auto;
    min-height: 36px;
    margin: 0;
    white-space: nowrap;
  }

  .connection-catalog__category > svg {
    width: 16px;
    height: 16px;
  }

  .connection-catalog__search {
    padding: 12px 12px 4px;
  }

  .connection-catalog__groups {
    padding: 8px 12px 16px;
  }

  .connection-catalog__cards {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }

  .connection-catalog__group h3 {
    margin-bottom: 10px;
    font-size: 16px;
  }

  .connection-catalog__group + .connection-catalog__group {
    margin-top: 20px;
  }
}
</style>
