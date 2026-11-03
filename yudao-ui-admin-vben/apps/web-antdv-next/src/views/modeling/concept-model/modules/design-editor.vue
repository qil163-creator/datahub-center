<script setup lang="ts">
import type { ConceptModelApi } from '#/api/modeling/concept-model';

import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Checkbox,
  Empty,
  Input,
  Popconfirm,
  Select,
  TabPane,
  Tabs,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  cardinalityOptions,
  dataTypeOptions,
  designKey,
  removeDesignEntity,
} from '../design';

const props = defineProps<{ readonly?: boolean }>();
const design = defineModel<ConceptModelApi.Design>({ required: true });
const tab = ref('entities');
const selectedKey = ref('');
const selected = computed(
  () =>
    design.value.entities.find((entity) => entity.key === selectedKey.value) ??
    design.value.entities[0],
);
const entityOptions = computed(() =>
  design.value.entities.map((entity) => ({
    label: entity.name || '未命名实体',
    value: entity.key,
  })),
);

function addEntity() {
  if (props.readonly || design.value.entities.length >= 50) return;
  const key = designKey('entity');
  design.value.entities.push({
    key,
    name: '',
    code: '',
    entityType: 'OBJECT',
    description: '',
    attributes: [],
  });
  selectedKey.value = key;
}

function removeEntity(key: string) {
  if (props.readonly) return;
  removeDesignEntity(design.value, key);
}

function changeEntityType(value: unknown) {
  if (selected.value && (value === 'OBJECT' || value === 'ACTIVITY')) {
    selected.value.entityType = value;
  }
}

function addAttribute() {
  if (
    props.readonly ||
    !selected.value ||
    selected.value.attributes.length >= 30
  )
    return;
  selected.value.attributes.push({
    key: designKey('attr'),
    name: '',
    code: '',
    dataType: 'STRING',
    primary: false,
  });
}

function addRelation() {
  if (props.readonly || design.value.relations.length >= 100) return;
  design.value.relations.push({
    key: designKey('relation'),
    name: '',
    sourceEntityKey: design.value.entities[0]?.key ?? '',
    targetEntityKey: design.value.entities[1]?.key ?? '',
    cardinality: 'ONE_TO_MANY',
  });
}

function entityLabel(key: string) {
  return (
    design.value.entities.find((entity) => entity.key === key)?.name ||
    '未命名实体'
  );
}
</script>

<template>
  <Tabs v-model:active-key="tab">
    <TabPane key="entities" :tab="`实体与属性 · ${design.entities.length}`">
      <div class="flex flex-wrap items-center justify-between gap-2 pb-3">
        <span class="text-muted-foreground text-xs">定义业务实体及关键属性，每个实体最多 30 个属性</span>
        <Button
          v-if="!readonly"
          :disabled="design.entities.length >= 50"
          @click="addEntity"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          新增实体
        </Button>
      </div>
      <Empty
        v-if="design.entities.length === 0"
        description="尚未定义实体，可先保存草稿"
      />
      <div v-else class="grid gap-4 md:grid-cols-[200px_minmax(0,1fr)]">
        <nav aria-label="实体列表" class="flex flex-col gap-2">
          <button
            v-for="(entity, index) in design.entities"
            :key="entity.key"
            type="button"
            class="rounded border px-3 py-2 text-left"
            :class="
              selected?.key === entity.key
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-border'
            "
            @click="selectedKey = entity.key"
          >
            <span class="block truncate font-medium">{{
              entity.name || `实体 ${index + 1}`
            }}</span>
            <span class="text-muted-foreground text-xs">{{ entity.attributes.length }} 个属性</span>
          </button>
        </nav>
        <section
          v-if="selected"
          class="min-w-0 rounded border border-border p-4"
        >
          <div class="mb-3 flex justify-between gap-2">
            <h3 class="font-medium">实体定义</h3>
            <Popconfirm
              v-if="!readonly"
              title="删除实体时，其属性和关联关系也将移除，是否继续？"
              @confirm="removeEntity(selected.key)"
            >
              <Button type="link" danger size="small">
                <template #icon><IconifyIcon icon="lucide:trash-2" /></template>删除实体
              </Button>
            </Popconfirm>
          </div>
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="grid gap-1 text-sm">实体名称
              <Input
                v-model:value="selected.name"
                :disabled="readonly"
                :maxlength="100"
                placeholder="例如：客户"
            /></label>
            <label class="grid gap-1 text-sm">实体编码
              <Input
                v-model:value="selected.code"
                :disabled="readonly"
                :maxlength="64"
                placeholder="例如：customer"
            /></label>
            <label class="grid gap-1 text-sm sm:col-span-2">业务说明
              <TextArea
                v-model:value="selected.description"
                :disabled="readonly"
                :maxlength="500"
                :rows="2"
                placeholder="描述实体的业务含义"
            /></label>
            <label class="grid gap-1 text-sm">实体类型
              <Select
                :value="selected.entityType ?? 'OBJECT'"
                :disabled="readonly"
                :options="[
                  { label: '业务对象', value: 'OBJECT' },
                  { label: '业务活动', value: 'ACTIVITY' },
                ]"
                @update:value="changeEntityType"
              />
            </label>
          </div>
          <div class="my-3 flex items-center justify-between">
            <h4 class="font-medium">属性 · {{ selected.attributes.length }}</h4>
            <Button
              v-if="!readonly"
              size="small"
              :disabled="selected.attributes.length >= 30"
              @click="addAttribute"
            >
              <template #icon><IconifyIcon icon="lucide:plus" /></template>
              新增属性
            </Button>
          </div>
          <p
            v-if="selected.attributes.length === 0"
            class="text-muted-foreground py-5 text-center text-sm"
          >
            尚未定义属性
          </p>
          <div v-else class="space-y-3">
            <div
              v-for="(attribute, index) in selected.attributes"
              :key="attribute.key"
              class="rounded bg-muted/40 p-3"
            >
              <div class="mb-2 flex items-center justify-between text-sm">
                <span>属性 {{ index + 1 }}</span>
                <Button
                  v-if="!readonly"
                  type="link"
                  danger
                  size="small"
                  @click="selected.attributes.splice(index, 1)"
                >
                  <template #icon>
                    <IconifyIcon icon="lucide:trash-2" />
                  </template>
                  移除
                </Button>
              </div>
              <div class="grid gap-2 sm:grid-cols-3">
                <Input
                  v-model:value="attribute.name"
                  :disabled="readonly"
                  :maxlength="100"
                  placeholder="属性名称"
                  aria-label="属性名称"
                />
                <Input
                  v-model:value="attribute.code"
                  :disabled="readonly"
                  :maxlength="64"
                  placeholder="英文编码"
                  aria-label="属性英文编码"
                />
                <Select
                  v-model:value="attribute.dataType"
                  :disabled="readonly"
                  :options="dataTypeOptions"
                  aria-label="属性业务类型"
                />
                <Input
                  v-model:value="attribute.description"
                  :disabled="readonly"
                  :maxlength="500"
                  placeholder="属性说明（选填）"
                  aria-label="属性说明"
                  class="sm:col-span-2"
                />
                <Checkbox
                  :checked="attribute.primary === true"
                  @update:checked="
                    (checked: boolean) => (attribute.primary = checked)
                  "
                  :disabled="readonly"
                >
                  业务标识属性
                </Checkbox>
              </div>
            </div>
          </div>
        </section>
      </div>
    </TabPane>
    <TabPane key="relations" :tab="`业务关系 · ${design.relations.length}`">
      <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
        <span class="text-muted-foreground text-xs">关系从起点指向终点，例如：客户 → 下单 → 订单</span>
        <Button
          v-if="!readonly"
          :disabled="
            design.entities.length < 2 || design.relations.length >= 100
          "
          @click="addRelation"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          新增关系
        </Button>
      </div>
      <Empty
        v-if="design.relations.length === 0"
        :description="
          design.entities.length < 2
            ? '至少定义两个实体后，可建立业务关系'
            : '尚未定义业务关系'
        "
      />
      <div v-else class="space-y-3">
        <section
          v-for="(relation, index) in design.relations"
          :key="relation.key"
          class="rounded border border-border p-4"
        >
          <div class="mb-2 flex items-center justify-between">
            <span class="font-medium">关系 {{ index + 1 }}</span><Button
              v-if="!readonly"
              type="link"
              danger
              size="small"
              @click="design.relations.splice(index, 1)"
            >
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              移除
            </Button>
          </div>
          <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <label class="grid gap-1 text-sm">起点实体
              <Select
                v-model:value="relation.sourceEntityKey"
                :disabled="readonly"
                :options="entityOptions"
            /></label>
            <label class="grid gap-1 text-sm">关系名称
              <Input
                v-model:value="relation.name"
                :disabled="readonly"
                :maxlength="100"
                placeholder="例如：下单"
            /></label>
            <label class="grid gap-1 text-sm">终点实体
              <Select
                v-model:value="relation.targetEntityKey"
                :disabled="readonly"
                :options="entityOptions"
            /></label>
            <label class="grid gap-1 text-sm">关系基数
              <Select
                v-model:value="relation.cardinality"
                :disabled="readonly"
                :options="cardinalityOptions"
            /></label>
            <label class="grid gap-1 text-sm sm:col-span-2 lg:col-span-4">业务说明
              <Input
                v-model:value="relation.description"
                :disabled="readonly"
                :maxlength="500"
                placeholder="描述此关系的业务约束（选填）"
            /></label>
          </div>
        </section>
      </div>
    </TabPane>
    <TabPane key="preview" tab="关系预览">
      <Empty
        v-if="design.entities.length === 0"
        description="添加实体后查看设计预览"
      />
      <template v-else>
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <article
            v-for="entity in design.entities"
            :key="entity.key"
            class="overflow-hidden rounded border border-border"
          >
            <header class="bg-primary/10 px-4 py-3">
              <h4 class="font-medium text-primary">
                {{ entity.name || '未命名实体' }}
              </h4>
              <span class="text-muted-foreground text-xs">{{
                entity.code || '未填写编码'
              }}</span>
            </header>
            <ul class="space-y-2 p-4 text-sm">
              <li
                v-for="attribute in entity.attributes"
                :key="attribute.key"
                class="flex justify-between gap-2"
              >
                <span>{{ attribute.primary ? '◆ ' : ''
                  }}{{ attribute.name || '未命名属性' }}</span><span class="text-muted-foreground">{{
                  dataTypeOptions.find(
                    (item) => item.value === attribute.dataType,
                  )?.label || '—'
                }}</span>
              </li>
              <li
                v-if="entity.attributes.length === 0"
                class="text-muted-foreground"
              >
                暂无属性
              </li>
            </ul>
          </article>
        </div>
        <div class="mt-5 space-y-2">
          <h4 class="font-medium">业务关系</h4>
          <p
            v-if="design.relations.length === 0"
            class="text-muted-foreground text-sm"
          >
            暂无关系
          </p>
          <div
            v-for="relation in design.relations"
            :key="relation.key"
            class="flex flex-wrap items-center gap-3 rounded bg-muted/50 px-4 py-3 text-sm"
          >
            <strong>{{ entityLabel(relation.sourceEntityKey) }}</strong><span aria-hidden="true">→</span><span>{{ relation.name || '未命名关系' }}</span><span aria-hidden="true">→</span><strong>{{ entityLabel(relation.targetEntityKey) }}</strong><Tag color="blue">
              {{
                cardinalityOptions.find(
                  (item) => item.value === relation.cardinality,
                )?.label
              }}
            </Tag>
          </div>
        </div>
      </template>
    </TabPane>
  </Tabs>
</template>
