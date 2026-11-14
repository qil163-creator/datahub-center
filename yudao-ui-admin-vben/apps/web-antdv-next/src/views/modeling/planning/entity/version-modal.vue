<script setup lang="ts">
import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { computed, ref } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';
import { formatDateTime } from '@vben/utils';

import { Button, ConfigProvider, Modal, Select, Spin } from 'antdv-next';

import {
  compareBusinessEntityVersions,
  getBusinessEntityVersions,
} from '#/api/modeling/business-entity';
import { useObjectRequest } from '#/hooks/use-object-request';
import emptyRecords from '#/views/modeling/logictable/assets/empty-records.png';

import '#/views/modeling/logictable/modal.css';
import '#/views/modeling/logictable/version.css';

const { tokens } = useAntdDesignTokens();

/** 目标选择“当前设计”时使用的占位值；不会作为编号提交。 */
const CURRENT = '__current__';

const visible = ref(false);
const loading = ref(false);
const comparing = ref(false);
const entityId = ref<BusinessEntityApi.Id>();
const entityName = ref('');
const versions = ref<BusinessEntityApi.Version[]>([]);
const fromId = ref<string>();
const toId = ref<string>(CURRENT);
const result = ref<BusinessEntityApi.VersionCompare>();
const guard = useObjectRequest(() => String(entityId.value ?? ''));

const versionOptions = computed(() =>
  versions.value.map((version) => ({
    label: `${version.versionLabel}（${version.publishedTime ? formatDateTime(version.publishedTime) : '-'}）`,
    value: String(version.id),
  })),
);
const targetOptions = computed(() => [
  { label: '当前设计', value: CURRENT },
  ...versionOptions.value,
]);

function summaryText(version: BusinessEntityApi.Version) {
  const summary = version.changeSummary;
  if (!summary || summary.initial) return '初始版本';
  const parts = [
    summary.attributeChanged ? `属性 ${summary.attributeChanged} 项` : '',
    summary.relationAdded ? `新增关系 ${summary.relationAdded}` : '',
    summary.relationRemoved ? `移除关系 ${summary.relationRemoved}` : '',
  ].filter(Boolean);
  return parts.length > 0 ? parts.join('，') : '无变化';
}

async function open(id: BusinessEntityApi.Id, name = '') {
  entityId.value = id;
  entityName.value = name;
  versions.value = [];
  result.value = undefined;
  fromId.value = undefined;
  toId.value = CURRENT;
  visible.value = true;
  const current = guard.begin();
  loading.value = true;
  try {
    const list = await getBusinessEntityVersions(id);
    if (!current()) return;
    versions.value = list;
    if (list.length >= 2) {
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
  if (entityId.value === undefined || fromId.value === undefined) return;
  const current = guard.begin();
  comparing.value = true;
  result.value = undefined;
  try {
    const diff = await compareBusinessEntityVersions({
      entityId: entityId.value,
      fromVersionId: fromId.value,
      toVersionId: toId.value === CURRENT ? undefined : toId.value,
    });
    if (current()) result.value = diff;
  } finally {
    if (current()) comparing.value = false;
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
      :title="entityName ? `版本 - ${entityName}` : '版本'"
      class="qd-logic-modal"
      :width="1000"
      :footer="null"
      :style="{ top: '68px' }"
      destroy-on-hidden
      @cancel="close"
    >
      <Spin :spinning="loading || comparing">
        <div class="qd-modal-scroll">
          <template v-if="versions.length > 0">
            <h3 class="qd-modal-section">版本记录</h3>
            <div class="qd-summary-table-wrap">
              <table class="qd-summary-table">
                <thead>
                  <tr>
                    <th>版本号</th>
                    <th>变更说明</th>
                    <th>上线人</th>
                    <th>上线时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(version, index) in versions" :key="String(version.id)">
                    <td>
                      <span class="qd-summary-tag">{{ version.versionLabel }}</span>
                      <span v-if="index === 0" class="qd-diff-tag qd-diff-tag--added" style="margin-left: 6px">
                        最新
                      </span>
                    </td>
                    <td>{{ summaryText(version) }}</td>
                    <td>{{ version.publishedByName || (version.publishedBy ?? '-') }}</td>
                    <td>{{ version.publishedTime ? formatDateTime(version.publishedTime) : '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <h3 class="qd-modal-section">版本比对</h3>
            <div class="qd-version-picker">
              <span>基准版本</span>
              <Select v-model:value="fromId" :options="versionOptions" aria-label="基准版本" @change="compare" />
              <span>比较对象</span>
              <Select v-model:value="toId" :options="targetOptions" aria-label="比较对象" @change="compare" />
            </div>
            <template v-if="result">
              <div class="qd-diff-summary">
                <span>{{ result.fromLabel }} → {{ result.toLabel }}</span>
                <span class="qd-diff-tag qd-diff-tag--modified">属性变化 {{ result.attributeChanges.length }}</span>
                <span class="qd-diff-tag qd-diff-tag--added">关系变化 {{ result.relationChanges.length }}</span>
              </div>
              <div class="qd-summary-table-wrap">
                <table class="qd-summary-table">
                  <thead>
                    <tr><th>属性</th><th>变化前</th><th>变化后</th></tr>
                  </thead>
                  <tbody>
                    <tr v-for="change in result.attributeChanges" :key="change.key">
                      <td>{{ change.label }}</td>
                      <td>{{ change.before || '-' }}</td>
                      <td>{{ change.after || '-' }}</td>
                    </tr>
                    <tr
                      v-for="(change, index) in result.relationChanges"
                      :key="`${change.relationType}-${String(change.entityId)}-${index}`"
                    >
                      <td>{{ change.relationLabel }}</td>
                      <td>
                        <span v-if="change.changeType === 'REMOVED'" class="qd-diff-tag qd-diff-tag--removed">
                          {{ change.name }}（{{ change.code }}）
                        </span>
                        <template v-else>-</template>
                      </td>
                      <td>
                        <span v-if="change.changeType === 'ADDED'" class="qd-diff-tag qd-diff-tag--added">
                          {{ change.name }}（{{ change.code }}）
                        </span>
                        <template v-else>-</template>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div
                  v-if="result.attributeChanges.length === 0 && result.relationChanges.length === 0"
                  class="qd-summary-empty"
                >
                  <img :src="emptyRecords" alt="" />
                  <p>两者没有差异</p>
                </div>
              </div>
            </template>
          </template>
          <div v-else-if="!loading" class="qd-summary-empty">
            <img :src="emptyRecords" alt="" />
            <p>该实体还没有上线过，上线后会生成版本</p>
          </div>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button @click="close">关闭</Button>
      </footer>
    </Modal>
  </ConfigProvider>
</template>
