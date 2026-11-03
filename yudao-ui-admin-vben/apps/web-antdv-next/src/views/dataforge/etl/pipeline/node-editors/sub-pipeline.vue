<script setup lang="ts">
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
/* eslint-disable vue/no-mutating-props -- 编辑器只操作设计器提供的配置副本。 */
import type {
  SubflowContract,
  SubflowPipeline,
  SubflowPort,
} from '#/api/dataforge/etl/subflow';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Input, Select, Tag } from 'antdv-next';

import {
  getSubflowContract,
  getSubflowPipelinePage,
  getSubflowVersionPage,
} from '#/api/dataforge/etl/subflow';

import FieldContractSummary from '../field-contract-summary.vue';
import { parameterConstraintText } from '../parameters';

import './editor-style.css';

const props = defineProps<{ config: Record<string, any> }>();
const access = useAccessStore();
const user = useUserStore();
const owner = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
const initialOwner = owner();
const version = ref(String(props.config.versionId ?? ''));
const search = ref('');
const pipelineId = ref<string>();
const pipelines = ref<SubflowPipeline[]>([]);
const versions = ref<DataforgeEtlApi.Version[]>([]);
const pipelinePage = ref(1);
const versionPage = ref(1);
const pipelineTotal = ref(0);
const versionTotal = ref(0);
const pipelineLoading = ref(false);
const versionLoading = ref(false);
const loading = ref(false);
const error = ref('');
const candidate = ref<SubflowContract>();
let active = true;
const currentVisible = ref(true);
let sequence = 0;
let pipelineSequence = 0;
let versionSequence = 0;
const pipelineOptions = computed(() =>
  pipelines.value.map((item) => ({ value: String(item.id), label: item.name })),
);
const versionOptions = computed(() =>
  versions.value.map((item) => ({
    value: String(item.id),
    label: `修订 ${item.revision} · 版本 ${item.id}`,
  })),
);
const changedVersion = computed(
  () =>
    !!candidate.value &&
    String(candidate.value.versionId) !== String(props.config.versionId ?? ''),
);
const candidatePorts = computed(() =>
  candidate.value
    ? [
        { label: '输入', ports: candidate.value.inputs },
        { label: '输出', ports: candidate.value.outputs },
      ]
    : [],
);
const currentPorts = computed<{ label: string; ports: SubflowPort[] }[]>(() => [
  { label: '输入', ports: props.config.inputs ?? [] },
  { label: '输出', ports: props.config.outputs ?? [] },
]);
function invalidateContract() {
  ++sequence;
  loading.value = false;
  error.value = '';
  candidate.value = undefined;
}
function invalidate() {
  invalidateContract();
  ++pipelineSequence;
  ++versionSequence;
  pipelineLoading.value = false;
  versionLoading.value = false;
}
function resetIdentity() {
  invalidate();
  currentVisible.value = false;
  search.value = '';
  version.value = '';
  pipelineId.value = undefined;
  pipelines.value = [];
  versions.value = [];
  pipelineTotal.value = 0;
  versionTotal.value = 0;
}
async function searchPipelines(page = 1) {
  const current = ++pipelineSequence;
  const context = owner();
  pipelineLoading.value = true;
  error.value = '';
  pipelinePage.value = page;
  try {
    const result = await getSubflowPipelinePage({
      name: search.value.trim() || undefined,
      pageNo: page,
      pageSize: 20,
    });
    if (current !== pipelineSequence || !active || context !== owner()) return;
    pipelines.value = result.list;
    pipelineTotal.value = result.total;
  } catch {
    if (current === pipelineSequence && active)
      error.value = '子流程列表加载失败，请重试。';
  } finally {
    if (current === pipelineSequence) pipelineLoading.value = false;
  }
}
async function loadVersions(page = 1) {
  if (!pipelineId.value) return;
  const current = ++versionSequence;
  const id = pipelineId.value;
  const context = owner();
  versionLoading.value = true;
  error.value = '';
  versionPage.value = page;
  try {
    const result = await getSubflowVersionPage({
      pipelineId: id,
      pageNo: page,
      pageSize: 20,
    });
    if (
      current !== versionSequence ||
      !active ||
      context !== owner() ||
      id !== pipelineId.value
    )
      return;
    versions.value = result.list.filter((item) => item.eventType === 'PUBLISH');
    versionTotal.value = result.total;
  } catch {
    if (current === versionSequence && active)
      error.value = '发布版本加载失败，请重试。';
  } finally {
    if (current === versionSequence) versionLoading.value = false;
  }
}
async function load() {
  if (!/^[1-9]\d*$/.test(version.value.trim())) {
    error.value = '请选择发布版本，或输入历史发布版本 ID。';
    return;
  }
  const current = ++sequence;
  const requested = version.value.trim();
  const context = owner();
  loading.value = true;
  error.value = '';
  candidate.value = undefined;
  try {
    const contract = await getSubflowContract(requested);
    if (current !== sequence || !active || context !== owner()) return;
    if (String(contract.versionId) !== requested)
      throw new Error('Version mismatch');
    candidate.value = contract;
  } catch {
    if (current === sequence && active)
      error.value = '无法读取该发布版本或契约无效，现有引用保持不变。';
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function apply() {
  const contract = candidate.value;
  if (!contract || loading.value || !active) return;
  const old = owner() === initialOwner ? (props.config.parameters ?? {}) : {};
  Object.assign(props.config, {
    pipelineId: String(contract.pipelineId),
    versionId: String(contract.versionId),
    contentHash: contract.contentHash,
    inputs: contract.inputs,
    outputs: contract.outputs,
    parameters: Object.fromEntries(
      contract.parameters.map((key) => [
        key,
        typeof old[key] === 'string' ? old[key] : '',
      ]),
    ),
  });
  currentVisible.value = true;
}
function parameterLabel(name: string) {
  const definition = candidate.value?.parameterDefinitions?.find(
    (item) => item.name === name,
  );
  return definition ? parameterConstraintText(definition) : '显式字符串绑定';
}
watch(version, invalidateContract, { flush: 'sync' });
watch(search, () => {
  ++pipelineSequence;
  pipelines.value = [];
  pipelineTotal.value = 0;
  pipelineLoading.value = false;
});
watch(pipelineId, () => {
  invalidateContract();
  ++versionSequence;
  versions.value = [];
  versionTotal.value = 0;
  version.value = '';
  void loadVersions();
});
watch(owner, resetIdentity, { flush: 'sync' });
onBeforeUnmount(() => {
  active = false;
  invalidate();
});
</script>
<template>
  <div class="etl-editor">
    <Alert
      type="info"
      show-icon
      message="引用固定发布版本的纯转换子流程。每个输入连接一次，所有输出均需连接；最多嵌套 3 层，展开后全图最多 32 个节点。后续发布不会自动更新当前引用。"
    />
    <div class="form-item">
      <label class="form-item__label">查找子流程</label>
      <div class="flex gap-2">
        <Input
          v-model:value="search"
          placeholder="按流程名称搜索"
          @press-enter="searchPipelines()"
        /><Button :loading="pipelineLoading" @click="searchPipelines()">
搜索
</Button>
      </div>
      <Select
        v-model:value="pipelineId"
        :options="pipelineOptions"
        :loading="pipelineLoading"
        placeholder="选择含发布子流程版本的流程"
      />
      <div v-if="pipelineTotal > 20" class="flex gap-2">
        <Button
          :disabled="pipelinePage <= 1 || pipelineLoading"
          @click="searchPipelines(pipelinePage - 1)"
          >
上一页流程
</Button><span>第 {{ pipelinePage }} 页</span><Button
          :disabled="pipelinePage * 20 >= pipelineTotal || pipelineLoading"
          @click="searchPipelines(pipelinePage + 1)"
          >
下一页流程
</Button>
      </div>
    </div>
    <div v-if="pipelineId" class="form-item">
      <label class="form-item__label">已发布版本</label>
      <Select
        v-model:value="version"
        :options="versionOptions"
        :loading="versionLoading"
        placeholder="选择固定发布版本"
      />
      <Button
        v-if="!versions.length"
        :loading="versionLoading"
        @click="loadVersions()"
        >
<template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
刷新版本
</Button>
      <div v-if="versionTotal > 20" class="flex gap-2">
        <Button
          :disabled="versionPage <= 1 || versionLoading"
          @click="loadVersions(versionPage - 1)"
          >
上一页版本
</Button><span>第 {{ versionPage }} 页</span><Button
          :disabled="versionPage * 20 >= versionTotal || versionLoading"
          @click="loadVersions(versionPage + 1)"
          >
下一页版本
</Button>
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label">发布版本 ID（可手动填写）</label>
      <div class="flex gap-2">
        <Input v-model:value="version" placeholder="历史发布版本 ID" /><Button
          :loading="loading"
          @click="load"
          >
<template #icon><IconifyIcon icon="lucide:file-input" /></template>
读取契约
</Button>
      </div>
    </div>
    <Alert v-if="error" type="error" :message="error" />
    <div v-if="candidate" class="space-y-2">
      <strong>待应用：流程 {{ candidate.pipelineId }} / 版本
        {{ candidate.versionId }}</strong>
      <p class="break-all">内容摘要：{{ candidate.contentHash }}</p>
      <div v-for="group in candidatePorts" :key="group.label">
        <div v-for="port in group.ports" :key="port.name" class="my-2">
          <p>{{ group.label }} {{ port.name }}</p>
          <p v-for="field in port.fields" :key="field.name" class="ml-2">
            {{ field.name }} · <FieldContractSummary :field="field" />
          </p>
          <p v-if="!port.fields.length" class="ml-2">无声明字段</p>
        </div>
      </div>
      <p v-for="name in candidate.parameters" :key="name">
        参数 {{ name }} · {{ parameterLabel(name) }}
      </p>
      <Alert
        v-if="changedVersion && config.versionId"
        type="warning"
        message="应用将替换当前固定引用，请核对端口、字段与参数，并重新连接已失效的端口。"
      />
      <Button type="primary" @click="apply"><template #icon><IconifyIcon icon="lucide:pin" /></template>使用此固定版本</Button>
    </div>
    <template v-if="currentVisible && config.versionId">
      <p>
        当前引用：流程 {{ config.pipelineId }} / 版本 {{ config.versionId }}
      </p>
      <div v-for="group in currentPorts" :key="group.label">
        <div v-for="port in group.ports" :key="port.name" class="my-2">
          <Tag :color="group.label === '输入' ? 'blue' : 'green'">{{ group.label }} {{ port.name }}</Tag>
          <p v-for="field in port.fields" :key="field.name" class="ml-2">
            {{ field.name }} · <FieldContractSummary :field="field" />
          </p>
          <p v-if="!port.fields.length" class="ml-2">无声明字段</p>
        </div>
      </div>
      <div
        v-for="(_, key) in config.parameters ?? {}"
        :key="key"
        class="form-item"
      >
        <label class="form-item__label">参数 {{ key }}</label><Input
          v-model:value="config.parameters[key]"
          placeholder="显式绑定值，支持父流程参数占位符"
        />
      </div>
      <p class="form-item__tip">
        子流程不继承参数默认值；每个参数均需显式绑定。
      </p>
    </template>
  </div>
</template>
