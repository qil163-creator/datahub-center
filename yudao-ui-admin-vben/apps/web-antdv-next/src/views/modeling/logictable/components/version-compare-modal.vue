<script setup lang="ts">
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, ref } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';
import { formatDate } from '@vben/utils';

import { Button, ConfigProvider, Modal, Select, Spin } from 'antdv-next';

import {
  compareTableModelVersions,
  getTableModelVersions,
} from '#/api/modeling/tablemodel';
import { useObjectRequest } from '#/hooks/use-object-request';

import emptyRecords from '../assets/empty-records.png';
import ModelDiffView from './model-diff-view.vue';

import '../modal.css';
import '../version.css';

const { tokens } = useAntdDesignTokens();

/** 目标选择“当前设计”时使用的占位值；不会作为编号提交。 */
const CURRENT = '__current__';

const visible = ref(false);
const loading = ref(false);
const modelId = ref<TableModelApi.Id>();
const modelName = ref('');
const versions = ref<TableModelApi.Version[]>([]);
const fromId = ref<string>();
const toId = ref<string>(CURRENT);
const diff = ref<TableModelApi.Diff>();
const guard = useObjectRequest(() => String(modelId.value ?? ''));

const versionOptions = computed(() =>
  versions.value.map((version) => ({
    label: `${version.versionLabel}（${version.publishedTime ? formatDate(version.publishedTime, 'YYYY-MM-DD HH:mm') : '-'}）`,
    value: String(version.id),
  })),
);
const targetOptions = computed(() => [
  { label: '当前设计', value: CURRENT },
  ...versionOptions.value,
]);

async function open(
  id: TableModelApi.Id,
  name = '',
  preset: { fromVersionId?: TableModelApi.Id; toVersionId?: TableModelApi.Id } = {},
) {
  modelId.value = id;
  modelName.value = name;
  versions.value = [];
  diff.value = undefined;
  fromId.value = undefined;
  toId.value = CURRENT;
  visible.value = true;
  const current = guard.begin();
  loading.value = true;
  try {
    const list = await getTableModelVersions(id);
    if (!current()) return;
    versions.value = list;
    if (preset.fromVersionId !== undefined) {
      fromId.value = String(preset.fromVersionId);
      toId.value = preset.toVersionId === undefined ? CURRENT : String(preset.toVersionId);
    } else if (list.length >= 2) {
      // 默认比较最近两次发布；只有一个版本时与当前设计比较。
      fromId.value = String(list[1]!.id);
      toId.value = String(list[0]!.id);
    } else if (list.length === 1) {
      fromId.value = String(list[0]!.id);
    }
  } finally {
    if (current()) loading.value = false;
  }
  if (current() && fromId.value !== undefined) await compare();
}

async function compare() {
  if (modelId.value === undefined || fromId.value === undefined) return;
  const current = guard.begin();
  loading.value = true;
  diff.value = undefined;
  try {
    const result = await compareTableModelVersions({
      modelId: modelId.value,
      fromVersionId: fromId.value,
      toVersionId: toId.value === CURRENT ? undefined : toId.value,
    });
    if (current()) diff.value = result;
  } finally {
    if (current()) loading.value = false;
  }
}

function close() {
  guard.begin();
  visible.value = false;
}

defineExpose({ open });
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Modal
      v-model:open="visible"
      :title="modelName ? `版本比对 - ${modelName}` : '版本比对'"
      class="qd-logic-modal"
      :width="1000"
      :footer="null"
      :style="{ top: '68px' }"
      destroy-on-hidden
      @cancel="close"
    >
      <Spin :spinning="loading">
        <div class="qd-modal-scroll">
          <template v-if="versions.length > 0">
            <div class="qd-version-picker">
              <span>基准版本</span>
              <Select
                v-model:value="fromId"
                :options="versionOptions"
                aria-label="基准版本"
                @change="compare"
              />
              <span>比较对象</span>
              <Select
                v-model:value="toId"
                :options="targetOptions"
                aria-label="比较对象"
                @change="compare"
              />
            </div>
            <ModelDiffView v-if="diff" :diff="diff" />
          </template>
          <div v-else-if="!loading" class="qd-summary-empty">
            <img :src="emptyRecords" alt="" />
            <p>该模型还没有发布过版本，发布后即可比对</p>
          </div>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button @click="close">关闭</Button>
      </footer>
    </Modal>
  </ConfigProvider>
</template>
