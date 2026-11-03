<script setup lang="ts">
import type { ModelingModelApi } from '#/api/modeling/model';

import { ref } from 'vue';

import { Modal } from 'antdv-next';

defineOptions({ name: 'MaterializedFieldDetailModal' });

const open = ref(false);
const field = ref<ModelingModelApi.ModelField>();

function text(value: unknown) {
  return value === null || value === undefined || value === ''
    ? '-'
    : String(value);
}

function show(row: ModelingModelApi.ModelField) {
  field.value = { ...row };
  open.value = true;
}

defineExpose({ open: show });
</script>

<template>
  <Modal
    v-model:open="open"
    class="qd-materialized-field-modal"
    title="逻辑模型属性信息详情"
    :width="800"
    :footer="null"
    destroy-on-hidden
    @after-close="field = undefined"
  >
    <div v-if="field" class="qd-materialized-field-grid">
      <label>编号：</label>
      <div>{{ text(field.fieldUid) }}</div>
      <label>关联数据标准</label>
      <div>{{ text(field.standardName || field.standardCode) }}</div>
      <label>中文名称</label>
      <div>{{ text(field.cnName) }}</div>
      <label>英文名称</label>
      <div>{{ text(field.fieldName) }}</div>
      <label>数据类型</label>
      <div>{{ text(field.dataType) }}</div>
      <label>属性长度</label>
      <div>{{ text(field.dataLength ?? field.dataPrecision) }}</div>
      <label>小数长度</label>
      <div>{{ text(field.dataScale) }}</div>
      <label>默认值</label>
      <div>{{ text(field.defaultValue) }}</div>
      <label>是否主键</label>
      <div>{{ field.pkFlag ? '是' : '否' }}</div>
      <label>是否必填</label>
      <div>{{ field.nullableFlag === false ? '是' : '否' }}</div>
      <label>排序</label>
      <div>{{ text(field.sort) }}</div>
      <span></span><span></span> <label>描述</label>
      <div class="qd-materialized-field-description">
        {{ text(field.comment) }}
      </div>
      <label>创建人</label>
      <div>-</div>
      <label>创建时间</label>
      <div>-</div>
      <label>更新人</label>
      <div>-</div>
      <label>更新时间</label>
      <div>-</div>
    </div>
    <div class="qd-materialized-field-footer">
      <button type="button" class="qd-btn" @click="open = false">关闭</button>
    </div>
  </Modal>
</template>

<style>
.qd-materialized-field-modal .ant-modal-content,
.qd-materialized-field-modal .ant-modal-container {
  padding: 0;
  overflow: hidden;
  border-radius: var(--radius);
}

.qd-materialized-field-modal .ant-modal-body {
  padding: 0;
}

.qd-materialized-field-modal .ant-modal-header {
  padding: 10px 20px;
  margin: 0;
  background: #f8f8f8;
  border-radius: var(--radius) var(--radius) 0 0;
}

.qd-materialized-field-modal .ant-modal-title {
  font-size: 16px;
  line-height: 22px;
  color: #333;
}

.qd-materialized-field-modal .ant-modal-close {
  top: 10px;
  right: 14px;
  width: 22px;
  height: 22px;
  color: #909399;
}

.qd-materialized-field-grid {
  display: grid;
  grid-template-columns: 105px minmax(0, 1fr) 105px minmax(0, 1fr);
  gap: 16px 12px;
  align-items: start;
  padding: 20px 40px 20px 30px;
  font-size: 14px;
  color: #606266;
}

.qd-materialized-field-grid label {
  padding-top: 7px;
  line-height: 20px;
  text-align: right;
}

.qd-materialized-field-grid > div {
  min-width: 0;
  min-height: 34px;
  padding: 6px 10px;
  line-height: 20px;
  overflow-wrap: anywhere;
  background: #fcfcfc;
  border: 1px solid #f3f3f3;
  border-radius: 2px;
}

.qd-materialized-field-grid > .qd-materialized-field-description {
  grid-column: span 3;
  min-height: 84px;
  white-space: pre-wrap;
}

.qd-materialized-field-footer {
  display: flex;
  justify-content: flex-end;
  padding: 16px 20px;
  border-top: 1px solid #f3f3f3;
}

.qd-materialized-field-footer .qd-btn {
  height: 32px;
  padding: 6px 15px;
  font-size: 14px;
  color: #606266;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: calc(var(--radius) - 2px);
}

@media (width <= 600px) {
  .qd-materialized-field-grid {
    grid-template-columns: 90px minmax(0, 1fr);
    padding: 20px 16px;
  }

  .qd-materialized-field-grid > span {
    display: none;
  }

  .qd-materialized-field-grid > .qd-materialized-field-description {
    grid-column: auto;
  }
}
</style>
