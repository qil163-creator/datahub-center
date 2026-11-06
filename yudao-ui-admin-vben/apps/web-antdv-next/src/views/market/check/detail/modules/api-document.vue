<script lang="ts" setup>
import type { MarketCheckApi } from '#/api/asset/market/check';

import { computed } from 'vue';

import { Button, Table, Tag, Tooltip } from 'antdv-next';

import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

import { snapshotCellText } from '../snapshot-filter';

type DocumentField = MarketCheckApi.DocumentField;
const props = defineProps<{
  allowInvocationCheck?: boolean;
  canInvoke?: boolean;
  document?: MarketCheckApi.ApiDocument;
}>();
const emit = defineEmits<{ invoke: [] }>();
const parameterColumns = [
  { title: '序号', key: 'index', width: 80 },
  { title: '参数名称', dataIndex: 'name', key: 'name', width: 230 },
  { title: '是否允许为空', key: 'nullable', width: 150 },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    width: 280,
  },
  { title: '参数类型', dataIndex: 'type', key: 'type', width: 160 },
  { title: '示例值', dataIndex: 'example', key: 'example', width: 160 },
  {
    title: '默认值',
    dataIndex: 'defaultValue',
    key: 'defaultValue',
    width: 160,
  },
];
const responseColumns = [
  { title: '序号', key: 'index', width: 80 },
  { title: '字段名称', dataIndex: 'name', key: 'name', width: 260 },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    width: 420,
  },
  { title: '数据类型', dataIndex: 'type', key: 'type', width: 200 },
  { title: '示例值', dataIndex: 'example', key: 'example', width: 200 },
];
const returned = computed(() => {
  const value = props.document?.returnData;
  if (value === undefined || value === null) return '';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
});
function text(row: DocumentField, key: unknown) {
  return snapshotCellText(row[key as keyof DocumentField]) || '-';
}
</script>

<template>
  <div class="check-document">
    <div class="check-document__heading">
      <h2>基本信息</h2>
      <Tooltip
        :title="
          canInvoke || allowInvocationCheck
            ? undefined
            : '当前工单未提供可调用接口'
        "
      >
        <span><Button
            type="primary"
            size="small"
            :disabled="!canInvoke && !allowInvocationCheck"
            @click="emit('invoke')"
            >接口调用</Button></span>
      </Tooltip>
    </div>
    <div class="check-document__metadata">
      <label>服务名称</label><span>{{ document?.name || '-' }}</span>
      <label>请求方式</label><span><Tag class="check-document__tag" :bordered="false">{{
          document?.method || '-'
        }}</Tag></span>
      <label>返回结果类型</label><span><Tag class="check-document__tag" :bordered="false">{{
          document?.resultType || '-'
        }}</Tag></span>
      <label>接口路径</label><span>{{ document?.path || '-' }}</span>
    </div>
    <h2>请求参数</h2>
    <Table
      :columns="parameterColumns"
      :data-source="document?.requestParams ?? []"
      :pagination="false"
      :scroll="{ x: 1220 }"
      row-key="name"
      size="small"
    >
      <template #bodyCell="{ column, record, index }">
        <span v-if="column.key === 'index'">{{ index + 1 }}</span>
        <span
          v-else-if="column.key === 'nullable'"
        >{{ record.required === true ? '否' : record.required === false ? '是' : '-' }}</span>
        <TextEllipsisCell
          v-else-if="column.key === 'description'"
          :content="text(record as DocumentField, column.key)"
          :line="2"
          style="min-height: 0"
          center
        />
        <span v-else>{{ text(record as DocumentField, column.key) }}</span>
      </template>
    </Table>
    <h2>返回字段</h2>
    <Table
      :columns="responseColumns"
      :data-source="document?.responseFields ?? []"
      :pagination="false"
      :scroll="{ x: 1160 }"
      row-key="name"
      size="small"
    >
      <template #bodyCell="{ column, record, index }">
        <span v-if="column.key === 'index'">{{ index + 1 }}</span>
        <TextEllipsisCell
          v-else-if="column.key === 'description'"
          :content="text(record as DocumentField, column.key)"
          :line="2"
          style="min-height: 0"
          center
        />
        <span v-else>{{ text(record as DocumentField, column.key) }}</span>
      </template>
    </Table>
    <h2>返回数据</h2>
    <pre v-if="returned" class="check-document__returned">{{ returned }}</pre>
    <div v-else class="check-document__empty">暂无数据</div>
  </div>
</template>

<style scoped>
.check-document {
  padding-top: 20px;
}

.check-document h2 {
  position: relative;
  padding-left: 13px;
  margin: 16px 0 14px;
  font-size: 16px;
  font-weight: 400;
  line-height: 20px;
  color: #303743;
}

.check-document h2::before {
  position: absolute;
  top: 3px;
  left: 0;
  width: 5px;
  height: 15px;
  content: '';
  background: #4967ff;
  border-radius: 2px;
}

.check-document__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.check-document__heading h2 {
  margin: 0;
}

.check-document__metadata {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr) 180px minmax(0, 1fr);
  border-top: 1px solid hsl(var(--muted));
  border-left: 1px solid hsl(var(--muted));
}

.check-document__metadata > * {
  display: flex;
  align-items: center;
  min-height: 40px;
  padding: 8px 12px;
  font-size: 14px;
  overflow-wrap: anywhere;
  border-right: 1px solid hsl(var(--muted));
  border-bottom: 1px solid hsl(var(--muted));
}

.check-document__metadata > label {
  color: #5d6470;
  background: hsl(var(--muted));
}

.check-document__tag {
  margin: 0;
  color: #4266ff;
  background: #f0f6fd;
  border-radius: calc(var(--radius) - 2px);
}

.check-document__empty {
  display: grid;
  place-items: center;
  min-height: 240px;
  color: hsl(var(--muted-foreground));
  background: #f7f8fa;
  border: 1px solid #eef0f4;
  border-radius: var(--radius);
  box-shadow: 0 2px 4px #0000000d;
}

.check-document__returned {
  min-height: 240px;
  padding: 20px;
  overflow: auto;
  white-space: pre-wrap;
  background: #f7f8fa;
  border: 1px solid #eef0f4;
  border-radius: var(--radius);
}

@media (width <= 768px) {
  .check-document__metadata {
    grid-template-columns: 110px minmax(0, 1fr);
  }
}
</style>
