<script setup lang="ts">
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { ref, watch } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';
import { formatDateTime } from '@vben/utils';

import { Button, ConfigProvider, Modal, Spin } from 'antdv-next';

import {
  getTableModelVersion,
  getTableModelVersions,
} from '#/api/modeling/tablemodel';
import { useObjectRequest } from '#/hooks/use-object-request';

import emptyRecords from '../assets/empty-records.png';
import VersionCompareModal from './version-compare-modal.vue';

import '../detail.css';
import '../modal.css';
import '../version.css';

const props = defineProps<{ modelId: TableModelApi.Id; modelName?: string }>();

const { tokens } = useAntdDesignTokens();

const loading = ref(false);
const failed = ref(false);
const versions = ref<TableModelApi.Version[]>([]);
const detailOpen = ref(false);
const detailLoading = ref(false);
const detail = ref<TableModelApi.VersionDetail>();
const compareModal = ref<InstanceType<typeof VersionCompareModal>>();
const guard = useObjectRequest(() => String(props.modelId));
const detailGuard = useObjectRequest(() => String(props.modelId));

async function load() {
  const current = guard.begin();
  loading.value = true;
  failed.value = false;
  try {
    const list = await getTableModelVersions(props.modelId);
    if (current()) versions.value = list;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function summaryText(version: TableModelApi.Version) {
  const summary = version.changeSummary;
  if (!summary || summary.initial) return '初始版本';
  const parts = [
    summary.added ? `新增 ${summary.added}` : '',
    summary.removed ? `删除 ${summary.removed}` : '',
    summary.modified ? `修改 ${summary.modified}` : '',
    summary.headerChanged ? `表信息 ${summary.headerChanged}` : '',
  ].filter(Boolean);
  return parts.length > 0 ? `字段${parts.join('，')}` : '无结构变化';
}

async function view(version: TableModelApi.Version) {
  detailOpen.value = true;
  detail.value = undefined;
  const current = detailGuard.begin();
  detailLoading.value = true;
  try {
    const result = await getTableModelVersion(version.id);
    if (current()) detail.value = result;
  } finally {
    if (current()) detailLoading.value = false;
  }
}

function describe(field: NonNullable<TableModelApi.VersionDetail['fields']>[number]) {
  if (field.dataPrecision !== undefined && field.dataPrecision !== null)
    return `${field.dataType}(${field.dataPrecision},${field.dataScale ?? 0})`;
  if (field.dataLength !== undefined && field.dataLength !== null)
    return `${field.dataType}(${field.dataLength})`;
  return field.dataType || '-';
}

function compareWithPrevious(index: number) {
  const version = versions.value[index];
  const previous = versions.value[index + 1];
  if (!version || !previous) return;
  void compareModal.value?.open(props.modelId, props.modelName, {
    fromVersionId: previous.id,
    toVersionId: version.id,
  });
}

function compareWithCurrent(version: TableModelApi.Version) {
  void compareModal.value?.open(props.modelId, props.modelName, {
    fromVersionId: version.id,
  });
}

watch(() => String(props.modelId), load, { immediate: true });
</script>

<template>
  <Spin :spinning="loading">
    <div class="qd-logic-detail-table-scroll">
      <table class="qd-logic-detail-table qd-version-table">
        <thead>
          <tr>
            <th>版本号</th>
            <th>字段数</th>
            <th>变更说明</th>
            <th>发布人</th>
            <th>发布时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(version, index) in versions" :key="String(version.id)">
            <td>
              <span class="qd-logic-detail-tag">{{ version.versionLabel }}</span>
              <span v-if="index === 0" class="qd-diff-tag qd-diff-tag--added" style="margin-left: 6px">
                最新
              </span>
            </td>
            <td>{{ version.fieldCount ?? 0 }}</td>
            <td>{{ summaryText(version) }}</td>
            <td>{{ version.publishedByName || (version.publishedBy ?? '-') }}</td>
            <td>{{ version.publishedTime ? formatDateTime(version.publishedTime) : '-' }}</td>
            <td>
              <Button type="link" @click="view(version)">查看</Button>
              <Button
                v-if="index < versions.length - 1"
                type="link"
                @click="compareWithPrevious(index)"
              >
                与上一版比对
              </Button>
              <Button type="link" @click="compareWithCurrent(version)">
                与当前设计比对
              </Button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && versions.length === 0" class="qd-logic-detail-empty">
        <img :src="emptyRecords" alt="" />
        <span>{{ failed ? '版本加载失败' : '还没有发布过版本，模型每次发布会生成一个版本' }}</span>
        <Button v-if="failed" type="link" @click="load">重试</Button>
      </div>
    </div>
  </Spin>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Modal
      v-model:open="detailOpen"
      :title="detail ? `${detail.versionLabel} 版本快照` : '版本快照'"
      class="qd-logic-modal"
      :width="960"
      :footer="null"
      :style="{ top: '68px' }"
      destroy-on-hidden
    >
      <Spin :spinning="detailLoading">
        <div class="qd-modal-scroll">
          <template v-if="detail">
            <div class="qd-diff-summary">
              <span>表名：{{ detail.modelSnapshot?.tableNameNotation ?? '-' }}</span>
              <span>中文名称：{{ detail.modelSnapshot?.name ?? '-' }}</span>
              <span>发布时间：{{ detail.publishedTime ? formatDateTime(detail.publishedTime) : '-' }}</span>
            </div>
            <div class="qd-summary-table-wrap">
              <table class="qd-summary-table">
                <thead>
                  <tr>
                    <th>编号</th>
                    <th>字段英文名称</th>
                    <th>字段中文名称</th>
                    <th>数据类型</th>
                    <th>主键</th>
                    <th>可为空</th>
                    <th>描述</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(field, index) in detail.fields ?? []" :key="field.fieldUid || index">
                    <td>{{ index + 1 }}</td>
                    <td>{{ field.fieldName }}</td>
                    <td>{{ field.cnName }}</td>
                    <td>{{ describe(field) }}</td>
                    <td>{{ field.pkFlag ? '是' : '否' }}</td>
                    <td>{{ field.nullableFlag === false ? '否' : '是' }}</td>
                    <td>{{ field.comment || '' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button @click="detailOpen = false">关闭</Button>
      </footer>
    </Modal>
  </ConfigProvider>
  <VersionCompareModal ref="compareModal" />
</template>
