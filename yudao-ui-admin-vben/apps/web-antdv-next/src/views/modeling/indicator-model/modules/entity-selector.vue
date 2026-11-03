<script setup lang="ts">
import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { computed, ref, watch } from 'vue';

import { Input, Popover, Spin } from 'antdv-next';

import eventIcon from '#/views/modeling/planning/reference-assets/entity-event.png';
import hierarchyIcon from '#/views/modeling/planning/reference-assets/entity-hierarchy.png';
import objectIcon from '#/views/modeling/planning/reference-assets/entity-object.png';
import processIcon from '#/views/modeling/planning/reference-assets/entity-process.png';

import { QdIcon } from '../../logictable/icons';

const props = withDefaults(
  defineProps<{
    clearable?: boolean;
    createdEntityIds?: BusinessEntityApi.Id[];
    currentLabel?: string;
    disabled?: boolean;
    entities: BusinessEntityApi.Entity[];
    loading?: boolean;
    modelValue?: BusinessEntityApi.Id;
    placeholder?: string;
    type?: 'atomic' | 'condition';
  }>(),
  {
    createdEntityIds: undefined,
    modelValue: undefined,
    currentLabel: '',
    disabled: false,
    loading: false,
    placeholder: '请选择业务实体',
    clearable: true,
    type: 'condition',
  },
);
const emit = defineEmits<{
  change: [value: BusinessEntityApi.Id | undefined];
  'update:modelValue': [value: BusinessEntityApi.Id | undefined];
}>();
const opened = ref(false);
const keyword = ref('');
const category = ref('');
const categories = [
  { value: '', label: '全部', icon: '' },
  { value: 'EVENT', label: '业务事件', icon: eventIcon },
  { value: 'PROCESS', label: '业务流程', icon: processIcon },
  { value: 'NORMAL', label: '普通对象', icon: objectIcon },
  { value: 'HIERARCHICAL', label: '层级对象', icon: hierarchyIcon },
];
const current = computed(() =>
  props.entities.find(
    (entity) => String(entity.id) === String(props.modelValue),
  ),
);
const label = computed(() =>
  current.value
    ? `${current.value.name}（${current.value.code}）`
    : props.currentLabel,
);
const created = computed(
  () => new Set(props.createdEntityIds?.map(String) || []),
);
const rows = computed(() =>
  props.entities.filter(
    (entity) =>
      (!category.value || entity.entitySubtype === category.value) &&
      `${entity.name} ${entity.code}`
        .toLowerCase()
        .includes(keyword.value.trim().toLowerCase()),
  ),
);
function isCreated(entity: BusinessEntityApi.Entity) {
  return props.createdEntityIds === undefined
    ? (entity.logicalModelCount || 0) > 0
    : created.value.has(String(entity.id));
}
function unavailable(entity: BusinessEntityApi.Entity) {
  return String(entity.id) !== String(props.modelValue) && !isCreated(entity);
}
function choose(entity: BusinessEntityApi.Entity) {
  if (props.disabled || unavailable(entity)) return;
  emit('update:modelValue', entity.id);
  emit('change', entity.id);
  opened.value = false;
}
function clear() {
  emit('update:modelValue', undefined);
  emit('change', undefined);
}
watch(opened, (value) => {
  if (!value) {
    keyword.value = '';
    category.value = '';
  }
});
watch(
  () => props.disabled,
  (value) => {
    if (value) opened.value = false;
  },
);
</script>

<template>
  <div class="metric-entity-selector">
    <Popover
      v-model:open="opened"
      trigger="click"
      placement="bottomLeft"
      :disabled="disabled"
      :auto-adjust-overflow="true"
      destroy-on-hidden
      :classes="{ root: 'metric-entity-popover' }"
    >
      <template #content>
        <div class="metric-entity-panel">
          <Input
            v-model:value="keyword"
            placeholder="请输入业务实体名称"
            allow-clear
          >
            <template #prefix><QdIcon name="search" /></template>
          </Input>
          <div class="metric-entity-body">
            <div class="metric-entity-categories">
              <button
                v-for="group in categories"
                :key="group.value"
                type="button"
                :class="{ active: category === group.value }"
                @click="category = group.value"
              >
                <img v-if="group.icon" :src="group.icon" alt="" /><QdIcon
                  v-else
                  name="menu"
                />{{ group.label }}
              </button>
            </div>
            <div
              class="metric-entity-rows"
              role="listbox"
              aria-label="业务实体"
            >
              <Spin :spinning="loading">
                <button
                  v-for="entity in rows"
                  :key="String(entity.id)"
                  type="button"
                  role="option"
                  :aria-selected="String(modelValue) === String(entity.id)"
                  :disabled="unavailable(entity)"
                  :class="{
                    selected: String(modelValue) === String(entity.id),
                  }"
                  @click="choose(entity)"
                >
                  <span class="metric-entity-radio"></span><span
                    class="metric-entity-label"
                    :title="`${entity.name}（${entity.code}）`"
                    >{{ entity.name }}（{{ entity.code }}）</span><span
                    class="metric-entity-created"
                    :class="{ created: isCreated(entity) }"
                    >{{
                      isCreated(entity) ? '已创建逻辑表' : '未创建逻辑表'
                    }}</span>
                </button>
                <p v-if="!loading && rows.length === 0">暂无业务实体</p>
              </Spin>
            </div>
          </div>
        </div>
      </template>
      <button
        type="button"
        class="metric-entity-trigger"
        :disabled="disabled"
        aria-haspopup="listbox"
        :aria-expanded="opened"
      >
        <span :class="{ placeholder: !label }">{{ label || placeholder }}</span><QdIcon name="arrowDown" />
      </button>
    </Popover>
    <button
      v-if="clearable && modelValue !== undefined && !disabled"
      type="button"
      class="metric-entity-clear"
      aria-label="清空业务实体"
      @click="clear"
    >
      ×
    </button>
  </div>
</template>

<style>
.metric-entity-selector {
  position: relative;
  width: 100%;
}

.metric-entity-trigger {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 32px;
  padding: 0 11px;
  font: inherit;
  color: #606266;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: calc(var(--radius) - 2px);
}

.metric-entity-trigger > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-entity-trigger .placeholder {
  color: #a8abb2;
}

.metric-entity-trigger .qd-icon {
  flex-shrink: 0;
  width: 12px;
  height: 12px;
  color: #c0c4cc;
}

.metric-entity-trigger:disabled {
  color: #a8abb2;
  cursor: not-allowed;
  background: #f5f7fa;
}

.metric-entity-clear {
  position: absolute;
  top: 8px;
  right: 27px;
  width: 16px;
  height: 16px;
  padding: 0;
  line-height: 14px;
  color: #a8abb2;
  cursor: pointer;
  background: #fff;
  border: 0;
  border-radius: 50%;
}

.metric-entity-popover .ant-popover-container {
  padding: 20px;
}

.metric-entity-panel {
  box-sizing: border-box;
  width: 462px;
  max-width: calc(100vw - 60px);
  font-size: 14px;
  color: #606266;
}

.metric-entity-panel * {
  box-sizing: border-box;
}

.metric-entity-body {
  display: flex;
  height: 248px;
  margin-top: 20px;
  border: 1px solid #f0f2f5;
}

.metric-entity-categories {
  flex: 0 0 142px;
  border-right: 1px solid #f0f2f5;
}

.metric-entity-categories button {
  display: flex;
  gap: 12px;
  align-items: center;
  width: 100%;
  height: 46px;
  padding: 0 12px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 0;
}

.metric-entity-categories button.active {
  color: hsl(var(--primary));
  background: color-mix(in srgb, hsl(var(--primary)) 10%, hsl(var(--card)));
}

.metric-entity-categories img,
.metric-entity-categories .qd-icon {
  width: 17px;
  height: 17px;
  object-fit: contain;
}

.metric-entity-rows {
  flex: 1;
  min-width: 0;
  overflow: auto;
}

.metric-entity-rows button {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-height: 46px;
  padding: 0 12px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 0;
}

.metric-entity-rows button.selected {
  color: hsl(var(--primary));
  background: color-mix(in srgb, hsl(var(--primary)) 10%, hsl(var(--card)));
}

.metric-entity-rows button:disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}

.metric-entity-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-entity-radio {
  flex: 0 0 14px;
  width: 14px;
  height: 14px;
  border: 1px solid #dcdfe6;
  border-radius: 50%;
}

.metric-entity-rows .selected .metric-entity-radio {
  border: 5px solid #4565ff;
}

.metric-entity-created {
  flex-shrink: 0;
  padding: 1px 5px;
  margin-left: auto;
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  background: #f4f4f5;
}

.metric-entity-created.created {
  color: #67c23a;
  background: #f0f9eb;
}

.metric-entity-rows p {
  padding: 45px 10px;
  color: #909399;
  text-align: center;
}

@media (width <= 600px) {
  .metric-entity-categories {
    flex-basis: 108px;
  }

  .metric-entity-categories button {
    gap: 6px;
    padding: 0 8px;
    font-size: 12px;
  }

  .metric-entity-rows button {
    gap: 5px;
    padding: 0 7px;
    font-size: 12px;
  }

  .metric-entity-created {
    padding: 1px 3px;
    font-size: 12px;
  }
}
</style>
