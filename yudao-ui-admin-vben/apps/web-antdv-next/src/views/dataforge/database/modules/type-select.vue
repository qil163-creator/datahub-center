<script lang="ts" setup>
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Button, Input, message, Spin } from 'antdv-next';

import { ConnectionIcon } from '#/components/connection-icon';

import { useDatabaseTypeSelect } from '../composables/use-database-type-select';
import TypeSelectCard from './type-select-card.vue';
import TypeSelectSidebar from './type-select-sidebar.vue';

defineOptions({ name: 'DatabaseTypeSelectModal' });

const emit = defineEmits<{
  closed: [];
  select: [type: FoundationDatabaseTypeApi.DatabaseType];
}>();

const {
  categories,
  activeCategory,
  selectedType,
  searchKeyword,
  viewMode,
  loading,
  filteredTypes,
  init,
  reset,
  selectCategory,
  selectType,
} = useDatabaseTypeSelect();

const [Modal, modalApi] = useVbenModal({
  showCancelButton: false,
  showConfirmButton: false,
  fullscreenButton: false,
  contentClass: 'p-0',
  footerClass: 'w-full justify-between px-5',
  destroyOnClose: true,
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      reset();
      emit('closed');
      return;
    }
    modalApi.lock();
    try {
      await init();
    } catch {
      message.error('加载连接类型失败');
    } finally {
      modalApi.unlock();
    }
  },
});

async function handleSelectCategory(categoryCode: string) {
  try {
    await selectCategory(categoryCode);
  } catch {
    message.error('加载连接类型失败');
  }
}

function handleNext() {
  if (!selectedType.value) {
    message.warning('请先选择连接类型');
    return;
  }
  emit('select', selectedType.value);
  modalApi.close();
}
</script>

<template>
  <Modal class="w-[960px]" title="修改连接类型">
    <div class="type-select">
      <div class="type-select__toolbar">
        <div class="type-select__views">
          <button
            type="button"
            class="type-select__view-btn"
            :class="{ 'type-select__view-btn--active': viewMode === 'grid' }"
            @click="viewMode = 'grid'"
          >
            <IconifyIcon class="size-4" icon="lucide:layout-grid" />
          </button>
          <button
            type="button"
            class="type-select__view-btn"
            :class="{ 'type-select__view-btn--active': viewMode === 'list' }"
            @click="viewMode = 'list'"
          >
            <IconifyIcon class="size-4" icon="lucide:list" />
          </button>
        </div>
        <Input
          v-model:value="searchKeyword"
          allow-clear
          class="type-select__search"
          placeholder="搜索全部连接类型"
        >
          <template #prefix>
            <IconifyIcon
              class="size-4 text-muted-foreground"
              icon="lucide:search"
            />
          </template>
        </Input>
      </div>

      <div class="type-select__body">
        <TypeSelectSidebar
          :active-code="activeCategory"
          :categories="categories"
          @select="handleSelectCategory"
        />
        <div class="type-select__content">
          <Spin :spinning="loading">
            <div
              v-if="filteredTypes.length > 0"
              :class="
                viewMode === 'list' ? 'type-select__list' : 'type-select__grid'
              "
            >
              <TypeSelectCard
                v-for="item in filteredTypes"
                :key="item.id"
                :item="item"
                :selected="selectedType?.id === item.id"
                :view-mode="viewMode"
                @select="selectType"
              />
            </div>
            <div v-else class="type-select__empty">暂无连接类型</div>
          </Spin>
        </div>
      </div>
    </div>

    <template #prepend-footer>
      <div class="type-select__selected">
        <template v-if="selectedType">
          <span>已选择:</span>
          <ConnectionIcon
            :icon-path="selectedType.iconPath"
            :type-code="selectedType.typeCode || selectedType.productId"
            alt=""
            class="size-[18px] shrink-0"
          />
          <span>{{ selectedType.typeName }}</span>
        </template>
      </div>
    </template>
    <template #footer>
      <Button type="primary" :disabled="!selectedType" @click="handleNext">
        下一步 &gt;
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.type-select {
  display: flex;
  flex-direction: column;
  height: 560px;
  background: hsl(var(--muted) / 35%);
}

.type-select__toolbar {
  position: relative;
  display: flex;
  flex-shrink: 0;
  gap: 16px;
  align-items: center;
  justify-content: center;
  height: 56px;
  padding: 0 20px;
  background: hsl(var(--background));
  border-bottom: 1px solid hsl(var(--border));
}

.type-select__views {
  position: absolute;
  left: 20px;
  display: flex;
  gap: 4px;
}

.type-select__view-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 6px;
}

.type-select__view-btn--active,
.type-select__view-btn:hover {
  color: hsl(var(--foreground));
  background: hsl(var(--accent));
}

.type-select__search {
  width: 360px;
}

.type-select__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.type-select__content {
  flex: 1;
  padding: 16px 20px;
  overflow: auto;
}

.type-select__grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.type-select__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.type-select__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 280px;
  color: hsl(var(--muted-foreground));
}

.type-select__selected {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 14px;
  color: hsl(var(--foreground));
}
</style>
