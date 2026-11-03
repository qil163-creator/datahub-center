<script setup lang="ts">
import type { SelectedSystemFile } from './modules/system-file-picker.vue';

import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
} from 'antdv-next';

import {
  createBatchFile,
  deleteBatchFileUpload,
  getBatchFile,
  getBatchFileOptions,
  importSystemFile,
  updateBatchFile,
  uploadBatchFile,
} from '#/api/dataforge/batchfile';
import { useObjectRequest } from '#/hooks/use-object-request';
import { formatSize } from '#/views/dataforge/datafile/data';

import {
  activeRun,
  detachFile,
  FIELD_TYPES,
  fieldError,
  idOf,
  LIMITS,
  LIST_PATH,
  mergeTargets,
  moveFile,
  reconcileTargets,
  routeId,
  splitTargets,
  targetError,
  uploadError,
} from './model';
import EmptyState from './modules/empty-state.vue';
import ReferenceIcon from './modules/reference-icon.vue';
import SystemFilePicker from './modules/system-file-picker.vue';
import { useBatchConfirm } from './use-confirm';
import { useBatchScope } from './use-scope';

import './batch-file.css';

defineOptions({ name: 'DataforgeBatchFileWizard' });
const route = useRoute();
const router = useRouter();
const id = computed(() => routeId(route.params.id));
const editing = computed(() => route.name === 'DataforgeBatchFileEdit');
const { hasAccessByCodes } = useAccess();
const allowed = computed(() =>
  hasAccessByCodes([
    editing.value
      ? 'dataforge:batch-file:update'
      : 'dataforge:batch-file:create',
  ]),
);
const systemAllowed = computed(
  () =>
    hasAccessByCodes(['dataforge:data-file:query']) &&
    hasAccessByCodes(['dataforge:data-file:download']),
);
const options = ref<Api.Options>();
const existing = ref<Api.Detail>();
const files = ref<Api.File[]>([]);
const targets = ref<Api.Target[]>([]);
const step = ref(0);
const selectedFile = ref('');
const selectedFiles = ref<string[]>([]);
const selectedTargets = ref<number[]>([]);
const uploading = ref(false);
const saving = ref(false);
const loading = ref(false);
const failed = ref(false);
const error = ref('');
const uploadFailures = ref<string[]>([]);
const uploadName = ref('');
const systemOpen = ref(false);
const conflicts = ref<Api.File[]>([]);
const conflictOpen = ref(false);
const input = ref<HTMLInputElement>();
const newUploads = new Set<string>();
const model = reactive({
  taskName: '',
  responsibleUserId: undefined as Api.Id | undefined,
  targetDatabaseId: undefined as Api.Id | undefined,
  description: '',
  remark: '',
});
const limits = computed(() => options.value?.limits ?? LIMITS);
const fields = computed(() =>
  files.value.find((file) => idOf(file.id) === selectedFile.value),
);
const responsiblePhone = computed(
  () =>
    options.value?.users.find(
      (user) => idOf(user.id) === String(model.responsibleUserId),
    )?.mobile ??
    existing.value?.responsiblePhone ??
    '',
);
const blocked = computed(
  () =>
    !!existing.value?.online ||
    activeRun(existing.value?.lastStatus, existing.value?.lastRunResolvedTime),
);
const busy = computed(() => loading.value || uploading.value || saving.value);
const canEdit = computed(
  () => allowed.value && !blocked.value && !failed.value && !!options.value,
);
const { active, scope } = useBatchScope(() => [route.name, id.value]);
const confirm = useBatchConfirm(scope);
const request = useObjectRequest(() => scope.value);
const uploadRequest = useObjectRequest(() => scope.value);
const saveRequest = useObjectRequest(() => scope.value);
const stepNames = ['配置基础信息', '上传文件', '结构确认', '目标表配置'];
function reset() {
  step.value = 0;
  files.value = [];
  targets.value = [];
  existing.value = undefined;
  options.value = undefined;
  Object.assign(model, {
    taskName: '',
    responsibleUserId: undefined,
    targetDatabaseId: undefined,
    description: '',
    remark: '',
  });
  selectedFile.value = '';
  selectedFiles.value = [];
  selectedTargets.value = [];
  error.value = '';
  uploadFailures.value = [];
  loading.value = false;
  uploading.value = false;
  saving.value = false;
  failed.value = false;
  systemOpen.value = false;
  conflictOpen.value = false;
  newUploads.clear();
}
async function load() {
  reset();
  if (!active.value || !allowed.value) return;
  if (editing.value && !id.value) {
    error.value = '任务编号无效';
    failed.value = true;
    return;
  }
  const current = request.begin();
  loading.value = true;
  try {
    const [loadedOptions, detail] = await Promise.all([
      getBatchFileOptions(),
      editing.value && id.value ? getBatchFile(id.value) : undefined,
    ]);
    if (!current()) return;
    options.value = loadedOptions;
    if (detail) {
      existing.value = detail;
      Object.assign(model, {
        taskName: detail.taskName,
        responsibleUserId: detail.responsibleUserId,
        targetDatabaseId: detail.targetDatabaseId,
        description: detail.description ?? '',
        remark: detail.remark ?? '',
      });
      files.value = detail.files.map((file) => ({
        ...file,
        fields: file.fields.map((field) => ({ ...field })),
      }));
      targets.value = detail.targets.map((target) => ({
        ...target,
        fileIds: [...target.fileIds],
      }));
    }
    selectedFile.value = files.value[0] ? idOf(files.value[0].id) : '';
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function basicError() {
  if (!model.taskName.trim()) return '请输入任务名称';
  if (model.taskName.trim().length > 100) return '任务名称最多100个字符';
  if (model.responsibleUserId === undefined) return '请选择负责人';
  return '';
}
function validate(next: number) {
  let reason = basicError();
  if (!reason && next > 1 && files.value.length === 0)
    reason = '请至少选择一个文件并完成解析';
  if (!reason && next > 2) {
    for (const file of files.value) {
      const issue = fieldError(file.fields);
      if (issue) {
        reason = `${file.name}：${issue}`;
        selectedFile.value = idOf(file.id);
        break;
      }
    }
  }
  if (!reason && next > 3 && model.targetDatabaseId === undefined)
    reason = '请选择目标数据源';
  if (!reason && next > 3) reason = targetError(files.value, targets.value);
  error.value = reason;
  return !reason;
}
function changeStep(next: number) {
  if (busy.value || !canEdit.value) return;
  if (next > step.value && !validate(next)) return;
  if (next === 3) targets.value = reconcileTargets(files.value, targets.value);
  step.value = next;
  error.value = '';
  selectedTargets.value = [];
  if (!files.value.some((file) => idOf(file.id) === selectedFile.value))
    selectedFile.value = files.value[0] ? idOf(files.value[0].id) : '';
}
function addFile(file: Api.File) {
  files.value.push({
    ...file,
    fields: file.fields.map((field) => ({ ...field })),
  });
  newUploads.add(idOf(file.id));
  if (!selectedFile.value) selectedFile.value = idOf(file.id);
}
async function uploadLocal(event: Event) {
  const control = event.target as HTMLInputElement;
  const selected = [...(control.files ?? [])];
  control.value = '';
  if (selected.length === 0 || busy.value || !canEdit.value) return;
  const issue = uploadError(selected, files.value, limits.value);
  if (issue) {
    error.value = issue;
    return;
  }
  const current = uploadRequest.begin();
  uploading.value = true;
  uploadFailures.value = [];
  error.value = '';
  try {
    for (const file of selected) {
      if (!current()) break;
      uploadName.value = file.name;
      try {
        const result = await uploadBatchFile(file);
        if (current()) addFile(result);
      } catch {
        if (current())
          uploadFailures.value.push(
            `${file.name}：上传或解析失败，请重新选择后重试`,
          );
      }
    }
  } finally {
    if (current()) {
      uploading.value = false;
      uploadName.value = '';
    }
  }
}
async function selectSystem(selected: SelectedSystemFile[]) {
  if (
    selected.length === 0 ||
    busy.value ||
    !canEdit.value ||
    !systemAllowed.value
  )
    return;
  if (files.value.length + selected.length > limits.value.maxFiles) {
    error.value = `最多选择${limits.value.maxFiles}个文件`;
    return;
  }
  const sizes = selected.map((file) => BigInt(file.entry.size ?? 0));
  if (
    sizes.some(
      (size) => size === 0n || size > BigInt(limits.value.maxFileBytes),
    ) ||
    sizes.reduce(
      (sum, size) => sum + size,
      files.value.reduce((sum, file) => sum + BigInt(file.size), 0n),
    ) > BigInt(limits.value.maxTotalBytes)
  ) {
    error.value = '所选文件大小超过上传限制或为空文件';
    return;
  }
  const current = uploadRequest.begin();
  uploading.value = true;
  uploadFailures.value = [];
  error.value = '';
  try {
    for (const file of selected) {
      if (!current()) break;
      uploadName.value = file.entry.name;
      try {
        const result = await importSystemFile({
          connectionId: file.connectionId,
          path: file.entry.path,
        });
        if (current()) addFile(result);
      } catch {
        if (current())
          uploadFailures.value.push(
            `${file.entry.name}：读取或解析失败，请重试`,
          );
      }
    }
  } finally {
    if (current()) {
      uploading.value = false;
      uploadName.value = '';
    }
  }
}
function removeFiles(ids: string[]) {
  if (ids.length === 0 || busy.value || !canEdit.value) return;
  const openedScope = scope.value;
  confirm({
    okText: '确定',
    cancelText: '取消',
    title: '删除所选文件',
    content: '文件将从本任务配置中移除。',
    async onOk() {
      if (scope.value !== openedScope || busy.value || !canEdit.value) return;
      const current = uploadRequest.begin();
      uploading.value = true;
      try {
        for (const key of ids) {
          if (!current()) return;
          if (newUploads.has(key)) await deleteBatchFileUpload(key);
          if (!current()) return;
          newUploads.delete(key);
          files.value = files.value.filter((file) => idOf(file.id) !== key);
        }
        if (current()) {
          targets.value = reconcileTargets(files.value, targets.value);
          selectedFiles.value = [];
        }
      } finally {
        if (current()) uploading.value = false;
      }
    },
  });
}
function selectFile(key: string, checked: boolean) {
  selectedFiles.value = checked
    ? [...selectedFiles.value, key]
    : selectedFiles.value.filter((id) => id !== key);
}
function selectTarget(index: number, checked: boolean) {
  selectedTargets.value = checked
    ? [...selectedTargets.value, index]
    : selectedTargets.value.filter((id) => id !== index);
}
function merge() {
  const result = mergeTargets(
    targets.value,
    selectedTargets.value,
    files.value,
  );
  if (result.error) {
    conflicts.value = result.conflicts;
    conflictOpen.value = result.conflicts.length > 0;
    error.value = result.error;
    return;
  }
  targets.value = result.targets;
  selectedTargets.value = [];
  error.value = '';
}
function split() {
  targets.value = splitTargets(
    targets.value,
    selectedTargets.value,
    files.value,
  );
  selectedTargets.value = [];
  error.value = '';
}
function detach(group: number, fileId: Api.Id) {
  targets.value = detachFile(targets.value, group, fileId, files.value);
  selectedTargets.value = [];
}
function move(group: number, position: number, direction: -1 | 1) {
  targets.value[group] = moveFile(targets.value[group]!, position, direction);
}
function fileFor(fileId: Api.Id) {
  return files.value.find((file) => idOf(file.id) === idOf(fileId));
}
function removeField(index: number) {
  if (fields.value && fields.value.fields.length > 1)
    fields.value.fields.splice(index, 1);
}
async function save() {
  if (busy.value || !canEdit.value || !validate(4)) return;
  const current = saveRequest.begin();
  saving.value = true;
  const payload: Api.Save = {
    ...(existing.value
      ? { id: existing.value.id, revision: existing.value.revision }
      : {}),
    taskName: model.taskName.trim(),
    responsibleUserId: model.responsibleUserId!,
    description: model.description,
    remark: model.remark,
    targetDatabaseId: model.targetDatabaseId!,
    files: files.value.map((file) => ({
      id: file.id,
      fields: file.fields.map((field) => ({
        ...field,
        name: field.name.trim(),
      })),
    })),
    targets: targets.value.map((target) => ({
      name: target.name,
      fileIds: [...target.fileIds],
    })),
  };
  try {
    await (editing.value ? updateBatchFile(payload) : createBatchFile(payload));
    if (current()) {
      message.success('保存成功');
      await router.push(LIST_PATH);
    }
  } finally {
    if (current()) saving.value = false;
  }
}
function back() {
  if (!saving.value) void router.push(LIST_PATH);
}
watch(
  scope,
  () => {
    void load();
  },
  { immediate: true },
);
</script>
<template>
  <Page auto-content-height content-class="qdata-batch-file">
    <div class="bf-wizard">
      <div class="bf-step-panel">
        <nav class="bf-steps" aria-label="配置步骤">
          <div
            v-for="(name, index) in stepNames"
            :key="name"
            class="bf-step"
            :class="{ active: index === step, done: index < step }"
            :aria-current="index === step ? 'step' : undefined"
          >
            <span class="bf-step-number">{{ index + 1 }}</span>{{ name }}
          </div>
        </nav>
      </div>
      <div class="bf-wizard-body">
        <Alert
          v-if="!allowed"
          class="bf-error"
          type="warning"
          title="没有配置该任务的权限"
          show-icon
        />
        <Alert
          v-if="failed"
          class="bf-error"
          type="error"
          title="配置加载失败，请重试"
          show-icon
          >
<template #action>
<Button @click="load"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>重试</Button>
</template>
</Alert>
        <Alert
          v-if="blocked"
          class="bf-error"
          type="warning"
          title="任务上线、运行中或结果待核查时不可修改配置，请先处理任务状态"
          show-icon
        />
        <Alert
          v-if="error"
          class="bf-error"
          type="error"
          :title="error"
          show-icon
        />
        <Spin :spinning="loading">
          <fieldset
            :disabled="!canEdit || busy"
            class="m-0 min-w-0 border-0 p-0"
            :inert="!canEdit || busy || undefined"
          >
            <template v-if="step === 0">
              <h2 class="bf-section-title">基础信息</h2>
              <div class="bf-form">
                <label class="bf-form-row"><span class="bf-form-label bf-required">任务名称</span>
                  <div class="bf-form-control">
                    <Input
                      v-model:value="model.taskName"
                      placeholder="请输入任务名称"
                      :maxlength="100"
                      aria-label="任务名称"
                    /></div></label>
                <label class="bf-form-row"><span class="bf-form-label bf-required">责任人</span>
                  <div class="bf-form-control">
                    <Select
                      v-model:value="model.responsibleUserId"
                      :options="
                        options?.users.map((user) => ({
                          value: user.id,
                          label: user.nickname,
                        }))
                      "
                      show-search
                      option-filter-prop="label"
                      placeholder="请选择责任人"
                      aria-label="责任人"
                    /></div></label>
                <label class="bf-form-row"><span class="bf-form-label bf-required">责任人电话</span>
                  <div class="bf-form-control">
                    <Input
                      :value="responsiblePhone"
                      disabled
                      aria-label="责任人电话"
                    /></div></label>
                <div class="bf-form-row">
                  <span class="bf-form-label">任务状态</span>
                  <div class="bf-form-control">
                    <RadioGroup :value="false">
<Radio :value="false">未上线</Radio><Radio :value="true" disabled>已上线</Radio>
</RadioGroup>
                  </div>
                </div>
                <label
                  v-for="entry in [
                    { key: 'description' as const, title: '描述' },
                    { key: 'remark' as const, title: '备注' },
                  ]"
                  :key="entry.key"
                  class="bf-form-row bf-full-row"
                  ><span class="bf-form-label">{{ entry.title }}</span>
                  <div class="bf-form-control bf-textarea">
                    <TextArea
                      v-model:value="model[entry.key]"
                      :maxlength="256"
                      :placeholder="`请输入${entry.title}`"
                      :aria-label="entry.title"
                    /><span class="bf-textarea-count">{{ model[entry.key].length }} / 256字符</span>
                  </div></label>
              </div>
            </template>
            <template v-if="step === 1">
              <h2 class="bf-section-title">
                上传文件
                <small>请选择系统文件或从本地上传文件，完成文件解析后进入结构确认。</small>
              </h2>
              <div class="bf-actions mb-3">
                <Button
                  class="bf-light-button"
                  :disabled="!systemAllowed"
                  @click="systemOpen = true"
                  >
<template #icon><IconifyIcon icon="lucide:folder-open" /></template>
选择系统文件
</Button><Button class="bf-light-button" @click="input?.click()">
<template #icon><IconifyIcon icon="lucide:upload" /></template>
本地上传
</Button><Button
                  danger
                  :disabled="!selectedFiles.length"
                  @click="removeFiles(selectedFiles)"
                  >
<template #icon><IconifyIcon icon="lucide:trash-2" /></template>
批量删除
</Button><input
                  ref="input"
                  class="bf-file-input"
                  type="file"
                  accept=".csv,.xls,.xlsx"
                  multiple
                  aria-label="本地上传文件"
                  @change="uploadLocal"
                />
              </div>
              <div class="bf-table-wrap">
                <table class="bf-table bf-fixed-actions-table bf-fixed-actions--files">
                  <thead>
                    <tr>
                      <th>
                        <Checkbox
                          :checked="
                            !!files.length &&
                            selectedFiles.length === files.length
                          "
                          :indeterminate="
                            selectedFiles.length > 0 &&
                            selectedFiles.length < files.length
                          "
                          aria-label="全选文件"
                          @change="
                            selectedFiles = $event.target.checked
                              ? files.map((file) => idOf(file.id))
                              : []
                          "
                        />
                      </th>
                      <th>编号</th>
                      <th>文件名称</th>
                      <th>文件类型</th>
                      <th>文件来源</th>
                      <th>所在路径</th>
                      <th>文件大小</th>
                      <th>解析状态</th>
                      <th class="bf-sticky-actions">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(file, index) in files" :key="idOf(file.id)">
                      <td>
                        <Checkbox
                          :checked="selectedFiles.includes(idOf(file.id))"
                          :aria-label="`选择${file.name}`"
                          @change="
                            selectFile(idOf(file.id), $event.target.checked)
                          "
                        />
                      </td>
                      <td>{{ index + 1 }}</td>
                      <td>{{ file.name }}</td>
                      <td>
                        <span class="bf-tag">{{ file.type }}</span>
                      </td>
                      <td>
                        {{ file.source === 'LOCAL' ? '本地上传' : '系统文件' }}
                      </td>
                      <td>{{ file.path || '-' }}</td>
                      <td>{{ formatSize(file.size) }}</td>
                      <td><span class="bf-tag success"><ReferenceIcon name="check" />已解析</span></td>
                      <td class="bf-sticky-actions">
                        <button
                          class="bf-link bf-danger"
                          @click="removeFiles([idOf(file.id)])"
                        >
                          <IconifyIcon class="mr-1 inline-flex size-4 align-middle" icon="lucide:trash-2" />删除
                        </button>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <EmptyState v-if="!files.length" />
              </div>
              <p class="bf-inline-hint">
                支持 CSV、XLS、XLSX；单文件最多
                {{ limits.maxFileBytes / 1024 / 1024 }}MB，最多
                {{ limits.maxFiles }} 个文件，总大小不超过
                {{ limits.maxTotalBytes / 1024 / 1024 }}MB。
              </p>
            </template>
            <template v-if="step === 2">
              <h2 class="bf-section-title">结构确认</h2>
              <div class="bf-structure">
                <aside class="bf-file-sidebar">
                  <div class="bf-structure-heading">文件列表</div>
                  <button
                    v-for="file in files"
                    :key="idOf(file.id)"
                    class="bf-file-choice"
                    :class="{ active: selectedFile === idOf(file.id) }"
                    @click="selectedFile = idOf(file.id)"
                  >
                    {{ file.name
                    }}<span class="bf-secondary">{{ file.fields.length }} 个字段 · {{ file.type }}</span>
                  </button>
                </aside>
                <div class="bf-fields">
                  <div class="bf-structure-heading">{{ fields?.name }}</div>
                  <div class="bf-table-wrap">
                    <table class="bf-table bf-fixed-actions-table bf-fixed-actions--fields">
                      <thead>
                        <tr>
                          <th>序号</th>
                          <th>字段名称</th>
                          <th>字段类型</th>
                          <th>校验</th>
                          <th class="bf-sticky-actions">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr
                          v-for="(field, index) in fields?.fields"
                          :key="field.sourceIndex"
                        >
                          <td>{{ index + 1 }}</td>
                          <td>
                            <Input
                              v-model:value="field.name"
                              :maxlength="64"
                              :aria-label="`字段${index + 1}名称`"
                            />
                          </td>
                          <td>
                            <Select
                              v-model:value="field.type"
                              class="bf-field-type"
                              :options="
                                (options?.fieldTypes ?? FIELD_TYPES).map(
                                  (type) => ({ value: type, label: type }),
                                )
                              "
                              :aria-label="`字段${index + 1}类型`"
                            />
                          </td>
                          <td>
                            <span
                              class="bf-tag"
                              :class="
                                fieldError(fields!.fields)
                                  ? 'failed'
                                  : 'success'
                              "
                              ><ReferenceIcon v-if="!fieldError(fields!.fields)" name="check" />{{
                                fieldError(fields!.fields) ? '待修正' : '通过'
                              }}</span>
                          </td>
                          <td class="bf-sticky-actions">
                            <button
                              class="bf-link bf-danger"
                              :disabled="(fields?.fields.length ?? 0) < 2"
                              @click="removeField(index)"
                            >
                              <IconifyIcon class="mr-1 inline-flex size-4 align-middle" icon="lucide:trash-2" />删除
                            </button>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
            </template>
            <template v-if="step === 3">
              <h2 class="bf-section-title">目标表配置</h2>
              <div class="bf-target-controls">
                <label class="bf-target-source"><span class="bf-required">目标数据源</span>
                  <div>
                    <Select
                      v-model:value="model.targetDatabaseId"
                      :options="
                        options?.databases.map((database) => ({
                          value: database.id,
                          label: database.name,
                        }))
                      "
                      placeholder="请选择目标数据源"
                      aria-label="目标数据源"
                      show-search
                      option-filter-prop="label"
                    />
                    <div class="bf-inline-hint">
                      当前仅支持 MySQL
                      类型数据源，当前任务所有目标表将写入该数据源。
                    </div>
                  </div></label>
                <div class="bf-actions">
                  <Button
                    class="bf-light-button"
                    :disabled="selectedTargets.length < 2"
                    @click="merge"
                    >
<template #icon><IconifyIcon icon="lucide:merge" /></template>
合并到统一目标表
</Button><Button
                    class="bf-light-button"
                    :disabled="
                      !selectedTargets.some(
                        (index) => (targets[index]?.fileIds.length ?? 0) > 1,
                      )
                    "
                    @click="split"
                    >
<template #icon><IconifyIcon icon="lucide:split" /></template>
拆分为独立目标表
</Button>
                </div>
              </div>
              <div class="bf-table-wrap">
                <table class="bf-table bf-fixed-actions-table bf-fixed-actions--targets">
                  <thead>
                    <tr>
                      <th>
                        <Checkbox
                          :checked="
                            !!targets.length &&
                            selectedTargets.length === targets.length
                          "
                          aria-label="全选目标表"
                          @change="
                            selectedTargets = $event.target.checked
                              ? targets.map((_, index) => index)
                              : []
                          "
                        />
                      </th>
                      <th>目标表</th>
                      <th>文件名</th>
                      <th>写入顺序</th>
                      <th>最终字段数</th>
                      <th>结构状态</th>
                      <th>文件类型</th>
                      <th class="bf-sticky-actions">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(target, group) in targets" :key="group">
                      <td>
                        <Checkbox
                          :checked="selectedTargets.includes(group)"
                          :aria-label="`选择目标表${group + 1}`"
                          @change="selectTarget(group, $event.target.checked)"
                        />
                      </td>
                      <td>
                        <Input
                          v-model:value="target.name"
                          class="bf-target-name"
                          :maxlength="64"
                          :aria-label="`目标表${group + 1}名称`"
                        /><span class="bf-secondary">{{
                          target.fileIds.length > 1 ? '合并表' : '单文件'
                        }}</span>
                      </td>
                      <td>
                        <div
                          v-for="fileId in target.fileIds"
                          :key="idOf(fileId)"
                          class="bf-file-order"
                        >
                          <span>{{ fileFor(fileId)?.name }}</span>
                        </div>
                      </td>
                      <td>
                        <div
                          v-for="(_, order) in target.fileIds"
                          :key="order"
                          class="bf-file-order"
                        >
                          <span class="bf-tag">{{ order + 1 }}</span>
                        </div>
                      </td>
                      <td>{{ fileFor(target.fileIds[0]!)?.fields.length }}</td>
                      <td><span class="bf-tag success"><ReferenceIcon name="check" />已确认</span></td>
                      <td>
                        <span class="bf-tag">{{ [...new Set(target.fileIds.map((fileId) => fileFor(fileId)?.type))].join(' / ') }}</span>
                      </td>
                      <td class="bf-sticky-actions">
                        <div v-for="(fileId, order) in target.fileIds" :key="idOf(fileId)" class="bf-file-order">
                          <span
                            v-if="target.fileIds.length > 1"
                            class="bf-actions"
                            ><button
                              class="bf-link"
                              :disabled="order === 0"
                              :aria-label="`${fileFor(fileId)?.name}上移`"
                              @click="move(group, order, -1)"
                            >
                              ↑</button><button
                              class="bf-link"
                              :disabled="order === target.fileIds.length - 1"
                              :aria-label="`${fileFor(fileId)?.name}下移`"
                              @click="move(group, order, 1)"
                            >
                              ↓</button><button
                              class="bf-link"
                              @click="detach(group, fileId)"
                            >
                              <IconifyIcon class="mr-1 inline-flex size-4 align-middle" icon="lucide:split" />拆出
                            </button></span><span v-else>-</span>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p class="bf-inline-hint">
                目标表不存在时创建；已存在时校验字段结构并追加数据。保存仅保存配置，任务上线后可执行入库。
                执行人需具备批量文件执行和目标数据写入权限；目标存在禁止写入策略时不可入库。
              </p>
            </template>
          </fieldset>
        </Spin>
        <Alert
          v-if="uploading && uploadName"
          type="info"
          :title="`正在上传并解析：${uploadName}`"
          show-icon
        />
        <Alert
          v-if="uploadFailures.length"
          type="error"
          title="部分文件处理失败"
          show-icon
          >
<template #description>
<div v-for="failure in uploadFailures" :key="failure">
              {{ failure }}
            </div>
</template>
</Alert>
        <footer class="bf-wizard-footer">
          <Button type="primary" :disabled="saving" @click="back">
<ReferenceIcon name="back" />返回列表
</Button><Button
            v-if="step > 0"
            class="bf-light-button"
            :disabled="busy || !canEdit"
            @click="changeStep(step - 1)"
            >
上一步
</Button><Button
            v-if="step < 3"
            class="bf-light-button"
            :disabled="busy || !canEdit"
            @click="changeStep(step + 1)"
            >
下一步
</Button><Button
            v-else
            type="primary"
            :loading="saving"
            :disabled="busy || !canEdit"
            @click="save"
            >
确定并退出
</Button>
        </footer>
      </div>
    </div>
    <SystemFilePicker v-model:open="systemOpen" @select="selectSystem" />
    <Modal
      v-model:open="conflictOpen"
      title="文件结构不一致"
      width="1000px"
      :footer="null"
      >
<div class="qdata-batch-file bf-modal-body">
        <Alert
          type="warning"
          title="字段名称、类型和顺序必须一致，请返回结构确认调整"
          show-icon
        />
        <div class="bf-table-wrap mt-4">
          <table class="bf-table">
            <thead>
              <tr>
                <th>序号</th>
                <th v-for="file in conflicts" :key="idOf(file.id)">
                  {{ file.name }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="index in Math.max(
                  0,
                  ...conflicts.map((file) => file.fields.length),
                )"
                :key="index"
              >
                <td>{{ index }}</td>
                <td v-for="file in conflicts" :key="idOf(file.id)">
                  {{ file.fields[index - 1]?.name || '-' }}
                  <span class="bf-secondary">{{
                    file.fields[index - 1]?.type || '-'
                  }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
</Modal>
  </Page>
</template>

<style scoped>
.bf-fixed-actions-table {
  min-width: 1000px;
}

.bf-sticky-actions {
  position: sticky;
  right: 0;
  z-index: 1;
  width: var(--batch-action-width, 100px);
  min-width: var(--batch-action-width, 100px);
  white-space: nowrap;
  background: hsl(var(--card));
  box-shadow: -4px 0 6px -5px hsl(var(--foreground) / 20%);
}

th.bf-sticky-actions {
  z-index: 2;
  background: hsl(var(--accent));
}

.bf-fixed-actions--fields {
  min-width: 680px;
}

.bf-fixed-actions--targets {
  --batch-action-width: 160px;
}
</style>
