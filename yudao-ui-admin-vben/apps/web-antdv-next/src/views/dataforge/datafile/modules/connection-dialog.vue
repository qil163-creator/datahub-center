<script setup lang="ts">
import type { DataforgeDataFileApi } from '#/api/dataforge/datafile';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Input, Modal, Popover, Spin } from 'antdv-next';

const props = defineProps<{
  connections: DataforgeDataFileApi.Connection[];
  error: boolean;
  loading: boolean;
  open: boolean;
  selected?: DataforgeDataFileApi.Connection;
}>();
const emit = defineEmits<{
  reload: [];
  select: [value: DataforgeDataFileApi.Connection];
  'update:open': [value: boolean];
}>();
const draft = ref<DataforgeDataFileApi.Connection>();
const keyword = ref('');
const activeType = ref('');
const activeCategory = ref(false);
const expanded = ref(false);
const matching = computed(() =>
  props.connections.filter((item) =>
    item.name
      .toLocaleLowerCase()
      .includes(keyword.value.trim().toLocaleLowerCase()),
  ),
);
const types = computed(() => [
  ...new Set(matching.value.map((item) => item.type)),
]);
const filtered = computed(() =>
  matching.value.filter(
    (item) => !activeType.value || item.type === activeType.value,
  ),
);
watch(
  () => props.open,
  (open) => {
    if (!open) return;
    draft.value = props.selected;
    keyword.value = '';
    activeType.value = '';
    expanded.value = false;
  },
);
function confirm() {
  if (
    !draft.value ||
    !props.connections.some(
      (item) => String(item.id) === String(draft.value?.id),
    )
  )
    return;
  emit('select', draft.value);
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :open="open"
    title="切换连接"
    :width="1200"
    class="file-connection-dialog"
    :ok-button-props="{ disabled: !draft || loading }"
    @cancel="emit('update:open', false)"
    @ok="confirm"
  >
    <Alert
      v-if="error"
      type="error"
      message="连接列表加载失败，请重试。"
      show-icon
    >
      <template #action>
<Button size="small" @click="emit('reload')"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>重试</Button>
</template>
    </Alert>
    <div class="connection-form">
      <label>数据库连接</label>
      <Popover
        v-model:open="expanded"
        trigger="click"
        placement="bottomLeft"
        :arrow="false"
        :styles="{ container: { padding: '12px' } }"
      >
        <button
          class="connection-select"
          type="button"
          aria-label="选择数据库连接"
          :aria-expanded="expanded"
          :disabled="loading"
        >
          <span :class="{ placeholder: !draft }">{{
            draft?.name || '请选择数据库连接'
          }}</span>
          <IconifyIcon
            :icon="expanded ? 'lucide:chevron-up' : 'lucide:chevron-down'"
          />
        </button>
        <template #content>
          <div class="connection-popup">
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="请输入数据连接名称"
              aria-label="搜索数据连接"
            >
              <template #prefix><IconifyIcon icon="lucide:search" /></template>
            </Input>
            <Spin :spinning="loading">
              <div class="connection-columns">
                <nav aria-label="连接分类">
                  <button
                    type="button"
                    :class="{ active: !activeCategory }"
                    @click="
                      activeCategory = false;
                      activeType = '';
                    "
                  >
                    <IconifyIcon icon="lucide:layout-grid" /><span>全部</span><small>({{ matching.length }})</small>
                  </button>
                  <button
                    type="button"
                    :class="{ active: activeCategory }"
                    @click="
                      activeCategory = true;
                      activeType = '';
                    "
                  >
                    <IconifyIcon icon="lucide:hard-drive" /><span>文件/对象存储</span><small>({{ matching.length }})</small>
                  </button>
                </nav>
                <nav aria-label="连接类型">
                  <button
                    type="button"
                    :class="{ active: !activeType }"
                    @click="activeType = ''"
                  >
                    <span
                      class="selection-dot"
                      :class="{ checked: !activeType }"
                    ></span><span>全部</span><small>({{ matching.length }})</small>
                  </button>
                  <button
                    v-for="type in types"
                    :key="type"
                    type="button"
                    :class="{ active: activeType === type }"
                    @click="activeType = type"
                  >
                    <span
                      class="selection-dot"
                      :class="{ checked: activeType === type }"
                    ></span><span>{{ type }}</span><small>({{
                        matching.filter((item) => item.type === type).length
                      }})</small>
                  </button>
                  <p>共 {{ types.length }} 种连接类型</p>
                </nav>
                <nav aria-label="数据连接">
                  <button
                    v-for="item in filtered"
                    :key="String(item.id)"
                    type="button"
                    :title="item.name"
                    :class="{ active: String(draft?.id) === String(item.id) }"
                    @click="
                      draft = item;
                      expanded = false;
                    "
                  >
                    <span
                      class="selection-dot"
                      :class="{
                        checked: String(draft?.id) === String(item.id),
                      }"
                    ></span><span>{{ item.name }}</span>
                  </button>
                  <div v-if="filtered.length === 0" class="connection-empty">
                    暂无匹配的数据连接
                  </div>
                </nav>
              </div>
            </Spin>
          </div>
        </template>
      </Popover>
      <label>数据库类型</label><Input :value="draft?.type" disabled />
      <label>数据库地址</label><Input :value="draft?.address" disabled />
    </div>
  </Modal>
</template>

<style scoped>
.connection-form {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 16px 12px;
  align-items: center;
  padding: 20px 20px 32px;
}

.connection-form label {
  text-align: right;
}

.connection-select {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 32px;
  padding: 4px 11px;
  text-align: left;
  border: 1px solid #dcdfe6;
  border-radius: calc(var(--radius) - 2px);
}

.connection-select span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.placeholder {
  color: #b5bac4;
}

.connection-popup {
  width: min(740px, calc(100vw - 60px));
  color: #535965;
}

.connection-popup :deep(.ant-input-affix-wrapper) {
  height: 32px;
  margin-bottom: 20px;
  border-radius: calc(var(--radius) - 2px);
}

.connection-columns {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr 1.25fr;
  min-height: 340px;
  border: 1px solid #f0f2f5;
}

.connection-columns nav {
  position: relative;
  min-width: 0;
  padding-bottom: 30px;
  border-right: 1px solid #f0f2f5;
}

.connection-columns nav:last-child {
  border: 0;
}

.connection-columns button {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  height: 44px;
  padding: 8px 12px;
  font-size: 14px;
  text-align: left;
}

.connection-columns button > span:not(.selection-dot) {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.connection-columns small {
  flex-shrink: 0;
  font-size: 12px;
  color: #90939b;
}

.connection-columns button.active {
  color: hsl(var(--primary));
  background: color-mix(in srgb, hsl(var(--primary)) 10%, hsl(var(--card)));
}

.connection-columns button:hover {
  background: hsl(var(--accent));
}

.connection-columns p {
  position: absolute;
  bottom: 6px;
  padding: 0 12px;
  margin: 0;
  font-size: 12px;
  color: #90939b;
}

.selection-dot {
  flex-shrink: 0;
  width: 13px;
  height: 13px;
  border: 1px solid #dcdfe6;
  border-radius: 50%;
}

.selection-dot.checked {
  border: 4px solid #4965ff;
}

.connection-empty {
  padding: 30px 8px;
  font-size: 12px;
  color: #90939b;
  text-align: center;
}

@media (max-width: 767px) {
  .connection-form {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
    padding: 12px 0;
  }

  .connection-form label {
    text-align: left;
  }

  .connection-columns {
    grid-template-columns: 1fr 1fr;
    min-height: 300px;
  }

  .connection-columns nav:first-child {
    display: flex;
    grid-column: span 2;
    padding-bottom: 0;
    border-bottom: 1px solid #f0f2f5;
  }

  .connection-columns button {
    gap: 6px;
    padding: 8px;
  }
}
</style>
