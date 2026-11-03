<script setup lang="ts">
import type { SafetyCategoryWorkbenchApi as Api } from '#/api/governance/category/workbench';

import {
  computed,
  getCurrentInstance,
  onBeforeUnmount,
  reactive,
  ref,
  watch,
} from 'vue';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  Select,
  Spin,
  Tag,
  TreeSelect,
} from 'antdv-next';

import {
  createCategoryWorkbenchLevel,
  getCategoryWorkbench,
  getCategoryWorkbenchDirectory,
  getCategoryWorkbenchMask,
  getCategoryWorkbenchOptions,
  saveCategoryWorkbench,
  saveCategoryWorkbenchDirectory,
  saveCategoryWorkbenchMask,
  setCategoryWorkbenchLevel,
} from '#/api/governance/category/workbench';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { useBatchConfirm as useOwnedConfirm } from '#/views/dataforge/batchfile/use-confirm';
import { safetyMaskFieldsError } from '#/views/governance/common/mask-fields';
import SafetyMaskFields from '#/views/governance/common/mask-fields.vue';

import ReferenceLevel from '../components/reference-level.vue';
import {
  CATEGORY_STATUS_OPTIONS,
  categoryConfigured,
  categoryDirectoryName,
  categoryDirectoryTree,
  categoryDisplayId,
  categoryLevel,
  categoryTime,
  validCategoryRevision,
} from '../reference-data';
import { useCategorySession } from '../use-session';

export interface DialogRequest {
  mode:
    | 'batch'
    | 'create'
    | 'detail'
    | 'directory'
    | 'edit'
    | 'mask'
    | 'mask-view';
  id?: Api.Id;
  categoryId?: Api.Id;
  items?: Api.MutationItem[];
}
const props = defineProps<{ request: DialogRequest | null }>();
const emit = defineEmits<{ close: []; mask: [id: Api.Id]; success: [] }>();
const { identity, can } = useCategorySession();
const confirm = useOwnedConfirm(
  computed(() => JSON.stringify([identity.value, props.request])),
);
const options = ref<Api.Options>({
  directories: [],
  levels: [],
  priorities: [],
});
const row = ref<Api.Category>();
const self = getCurrentInstance()!.type;
const nestedMaskId = ref<Api.Id>();
const form = reactive<Partial<Api.Save>>({});
const directory = reactive<Partial<Api.Directory>>({});
const directoryParentId = ref('0');
const directoryConfirmOpen = ref(false);
const mask = reactive<Api.Mask>({
  id: '',
  revision: '',
  ruleName: '',
  applicationScene: [],
  maskType: '2',
  replaceRule: '1',
  replaceContent: '',
  intervalList: [],
  status: 1,
});
const loading = ref(false);
const saving = ref(false);
const ready = ref(false);
const errorText = ref('');
const guard = createLatestWins();
let alive = true;
const mode = computed(() => props.request?.mode);
const readOnly = computed(
  () => mode.value === 'detail' || mode.value === 'mask-view',
);
const isMask = computed(
  () => mode.value === 'mask' || mode.value === 'mask-view',
);
const isDirectory = computed(() => mode.value === 'directory');
const title = computed(
  () =>
    ({
      batch: '指定分级',
      create: '新增数据分类',
      detail: '数据分类详情',
      directory: props.request?.id ? '修改数据分类类目' : '新增数据分类类目',
      edit: '修改数据分类',
      mask: mask.ruleId ? '修改脱敏规则' : '设置脱敏',
      'mask-view': '查看脱敏规则',
    })[mode.value || 'detail'],
);
const permitted = computed(() => {
  if (!props.request) return false;
  if (readOnly.value) return can('query');
  if (isDirectory.value)
    return can(props.request.id ? 'update' : 'create', 'category-data');
  return can(mode.value === 'create' ? 'create' : 'update');
});
const directoryTree = computed(() =>
  categoryDirectoryTree(options.value.directories),
);
const parentTree = computed(() => [
  { key: '0', title: '数据分类类目', children: directoryTree.value },
]);
const levelOptions = computed(() =>
  options.value.levels
    .filter(
      (level) =>
        level.status !== 1 ||
        resourceIdKey(level.id) === resourceIdKey(form.dataLevelId),
    )
    .map((level) => ({
      value: resourceIdKey(level.id),
      label: level.safetyLevel || level.safetyName || '-',
    })),
);
const priorities = computed(() => options.value.priorities);
const priorityLabel = computed(
  () =>
    priorities.value.find((item) => item.value === row.value?.priority)
      ?.label ||
    row.value?.priority ||
    '-',
);
const level = computed(() =>
  row.value ? categoryLevel(row.value, options.value.levels) : {},
);

function clear() {
  guard.invalidate();
  row.value = undefined;
  nestedMaskId.value = undefined;
  directoryConfirmOpen.value = false;
  for (const target of [form, directory])
    for (const key of Object.keys(target)) Reflect.deleteProperty(target, key);
  options.value = { directories: [], levels: [], priorities: [] };
  Object.assign(mask, {
    id: '',
    revision: '',
    ruleId: undefined,
    ruleVersionId: undefined,
    ruleRevisionToken: undefined,
    ruleName: '',
    applicationScene: [],
    maskType: '2',
    replaceRule: '1',
    replaceContent: '',
    intervalList: [{ startNum: null, endNum: null }],
    status: 1,
    description: '',
  });
  loading.value = false;
  saving.value = false;
  ready.value = false;
  errorText.value = '';
  levelOpen.value = false;
  createdLevelId.value = undefined;
  levelGuard.invalidate();
}
async function load() {
  clear();
  const request = props.request;
  if (!request || !permitted.value) return;
  const ticket = guard.next();
  loading.value = true;
  try {
    const [context, category, rule, node] = await Promise.all([
      getCategoryWorkbenchOptions(),
      request.id && !isDirectory.value
        ? getCategoryWorkbench(request.id)
        : Promise.resolve(undefined),
      request.id && isMask.value
        ? getCategoryWorkbenchMask(request.id)
        : Promise.resolve(null),
      request.id && isDirectory.value
        ? getCategoryWorkbenchDirectory(request.id)
        : Promise.resolve(undefined),
    ]);
    if (!ticket.isCurrent()) return;
    if (
      request.id &&
      !isDirectory.value &&
      resourceIdKey(category?.id) !== resourceIdKey(request.id)
    )
      throw new Error('CATEGORY_IDENTITY');
    if (
      request.id &&
      isDirectory.value &&
      resourceIdKey(node?.id) !== resourceIdKey(request.id)
    )
      throw new Error('DIRECTORY_IDENTITY');
    options.value = context;
    row.value = category;
    Object.assign(
      form,
      { status: 1, categoryId: request.categoryId },
      category || {},
    );
    if (form.categoryId !== undefined)
      form.categoryId = resourceIdKey(form.categoryId);
    if (form.dataLevelId !== undefined)
      form.dataLevelId = resourceIdKey(form.dataLevelId);
    Object.assign(
      directory,
      {
        parentId: request.categoryId || '0',
        categoryName: '',
        status: 0,
        sort: 0,
        remark: '',
      },
      node || {},
    );
    directory.parentId = resourceIdKey(directory.parentId) || '0';
    directoryParentId.value = directory.parentId;
    if (isMask.value) {
      if (!category || !validCategoryRevision(category.revision))
        throw new Error('CATEGORY_REVISION');
      if (rule && resourceIdKey(rule.id) !== resourceIdKey(request.id))
        throw new Error('RULE_IDENTITY');
      if (mode.value === 'mask-view' && !rule) throw new Error('RULE_MISSING');
      Object.assign(mask, rule || {}, {
        id: category.id,
        revision: rule?.revision ?? category.revision,
      });
      mask.intervalList = rule?.intervalList?.length
        ? rule.intervalList.map((item) => ({ ...item }))
        : [{ startNum: null, endNum: null }];
      mask.applicationScene = [...(rule?.applicationScene || [])];
    }
    ready.value = true;
  } catch {
    if (ticket.isCurrent()) errorText.value = '读取失败，请关闭后刷新重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function validate(): string | undefined {
  if (isMask.value) {
    const invalidFields = safetyMaskFieldsError(mask);
    if (invalidFields) return invalidFields;
    if (!validCategoryRevision(mask.revision))
      return '数据已变化，请刷新后重试';
    if (
      mask.ruleId &&
      (!parseResourceId(mask.ruleVersionId) || !mask.ruleRevisionToken)
    )
      return '脱敏规则已变化，请刷新后重试';
  } else if (mode.value === 'batch') {
    if (!parseResourceId(form.dataLevelId)) return '请选择数据分级';
    if (
      !props.request?.items?.length ||
      props.request.items.some(
        (item) =>
          !parseResourceId(item.id) || !validCategoryRevision(item.revision),
      )
    )
      return '请选择有效的数据分类';
  } else if (isDirectory.value) {
    if (
      !directory.categoryName?.trim() ||
      directory.categoryName.trim().length > 50
    )
      return '请填写类目名称（最多50字）';
    if (directory.parentId === undefined) return '请选择上级类目';
    if (!/[A-Za-z0-9\u4E00-\u9FA5]/u.test(directory.categoryName))
      return '数据分类类目名称不能为纯符号';
    if (props.request?.id && !validCategoryRevision(directory.revision))
      return '类目已变化，请刷新后重试';
  } else {
    if (!form.name?.trim() || form.name.trim().length > 50)
      return '请填写分类名称（最多50字）';
    if (
      !parseResourceId(form.categoryId) ||
      !parseResourceId(form.dataLevelId) ||
      !form.priority
    )
      return '请选择所属类目、数据分级和优先级';
    if (mode.value === 'edit' && !validCategoryRevision(row.value?.revision))
      return '数据已变化，请刷新后重试';
  }
  return undefined;
}
async function save(confirmed = false) {
  if (
    !permitted.value ||
    readOnly.value ||
    saving.value ||
    !ready.value ||
    !alive
  )
    return;
  const invalid = validate();
  if (invalid) {
    message.warning(invalid);
    return;
  }
  if (
    isDirectory.value &&
    props.request?.id &&
    resourceIdKey(directory.parentId) !== directoryParentId.value &&
    !confirmed
  ) {
    if (directoryConfirmOpen.value) return;
    const subject = identity.value;
    const request = props.request;
    directoryConfirmOpen.value = true;
    confirm({
      title: '提示',
      content: '修改上级类目会影响该类目下任务的归属路径，请确认。',
      okText: '确定',
      cancelText: '取消',
      afterClose() {
        directoryConfirmOpen.value = false;
      },
      async onOk() {
        if (subject === identity.value && props.request === request && alive)
          await save(true);
      },
    });
    return;
  }
  const ticket = guard.next();
  saving.value = true;
  errorText.value = '';
  try {
    if (isMask.value) {
      const { ruleId: _ruleId, ...payload } = mask;
      await saveCategoryWorkbenchMask({
        ...payload,
        ruleName: mask.ruleName.trim(),
        intervalList: mask.intervalList.map((item) => ({ ...item })),
      });
    } else if (mode.value === 'batch')
      await setCategoryWorkbenchLevel(props.request!.items!, form.dataLevelId!);
    else if (isDirectory.value)
      await saveCategoryWorkbenchDirectory({
        id: props.request?.id,
        revision: directory.revision,
        parentId: directory.parentId,
        categoryName: directory.categoryName!.trim(),
        sort: directory.sort,
        status: directory.status,
        remark: directory.remark,
      });
    else
      await saveCategoryWorkbench({
        id: mode.value === 'edit' ? row.value?.id : undefined,
        revision: mode.value === 'edit' ? row.value?.revision : undefined,
        categoryId: form.categoryId!,
        name: form.name!.trim(),
        shortName: form.shortName?.trim(),
        dataLevelId: form.dataLevelId!,
        priority: form.priority!,
        status: form.status ?? 1,
        description: form.description,
      });
    if (!ticket.isCurrent()) return;
    message.success('保存成功');
    emit('success');
    emit('close');
  } catch {
    if (ticket.isCurrent())
      errorText.value = '保存失败，数据可能已被修改或存在引用，请刷新后重试。';
  } finally {
    if (ticket.isCurrent()) saving.value = false;
  }
}
const levelOpen = ref(false);
const levelSaving = ref(false);
const levelError = ref('');
const createdLevelId = ref<Api.Id>();
const levelForm = reactive<{
  description: string;
  safetyLevel: string;
  safetyName: string;
  sensitiveLevel?: number;
  status: number;
}>({ safetyName: '', safetyLevel: '', status: 0, description: '' });
const levelGuard = createLatestWins();
function openLevel() {
  if (!can('create', 'level')) return;
  levelGuard.invalidate();
  createdLevelId.value = undefined;
  Object.assign(levelForm, {
    safetyName: '',
    safetyLevel: '',
    sensitiveLevel: undefined,
    status: 0,
    description: '',
  });
  levelError.value = '';
  levelSaving.value = false;
  levelOpen.value = true;
}
async function saveLevel() {
  if (!can('create', 'level') || !levelOpen.value || levelSaving.value) return;
  if (
    !levelForm.safetyName.trim() ||
    levelForm.safetyName.trim().length > 50 ||
    !/^[a-zA-Z0-9_]{2,10}$/u.test(levelForm.safetyLevel) ||
    !Number.isInteger(levelForm.sensitiveLevel) ||
    levelForm.sensitiveLevel! < 1 ||
    levelForm.sensitiveLevel! > 100
  ) {
    message.warning(
      '请填写分级名称、2-10位字母数字下划线简称及1-100的整数敏感等级',
    );
    return;
  }
  const ticket = levelGuard.next();
  levelSaving.value = true;
  try {
    let id = createdLevelId.value;
    if (id === undefined) {
      id = await createCategoryWorkbenchLevel({
        ...levelForm,
        safetyName: levelForm.safetyName.trim(),
        sensitiveLevel: levelForm.sensitiveLevel!,
      });
      if (!ticket.isCurrent()) return;
      if (!parseResourceId(id)) throw new Error('LEVEL_IDENTITY');
      createdLevelId.value = id;
    }
    const context = await getCategoryWorkbenchOptions();
    if (!ticket.isCurrent()) return;
    options.value = context;
    if (parseResourceId(id)) form.dataLevelId = resourceIdKey(id);
    levelOpen.value = false;
    message.success('新增成功');
    emit('success');
  } catch {
    if (ticket.isCurrent())
      levelError.value =
        createdLevelId.value === undefined
          ? '新增分级失败，请检查内容后重试。'
          : '分级已新增，选项刷新失败，请重试加载。';
  } finally {
    if (ticket.isCurrent()) levelSaving.value = false;
  }
}
watch(
  () => props.request,
  () => {
    void load();
  },
  { immediate: true },
);
watch(
  identity,
  () => {
    clear();
    emit('close');
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  alive = false;
  clear();
});
</script>

<template>
  <Modal
    :open="!!request"
    :title="title"
    :width="isMask ? 920 : mode === 'batch' ? 600 : 800"
    :centered="false"
    :mask-closable="false"
    :keyboard="!saving"
    :closable="!saving"
    :style="{ top: '6vh' }"
    class="category-reference-dialog"
    @cancel="emit('close')"
  >
    <Alert v-if="errorText" :message="errorText" type="error" show-icon />
    <Alert v-if="!permitted" message="暂无操作权限" type="warning" />
    <Spin :spinning="loading">
      <Form
        v-if="mode === 'detail' && row"
        class="category-form category-detail-form"
        :colon="false"
        :label-col="{ style: { width: '110px' } }"
        :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
      >
        <FormItem label="编号">
          <Input :value="categoryDisplayId(row)" disabled />
</FormItem><FormItem label="分类名称">
          <Input :value="row.name" disabled />
        </FormItem>
        <FormItem label="所属类目">
          <Input
            :value="categoryDirectoryName(row, options.directories)"
            disabled
          />
</FormItem><FormItem label="数据分级"><ReferenceLevel v-bind="level" /></FormItem>
        <FormItem label="优先级">
          <Tag class="category-soft-tag">{{ priorityLabel }}</Tag>
</FormItem><FormItem label="脱敏配置">
          <button
            v-if="categoryConfigured(row)"
            class="category-tag-link"
            @click="nestedMaskId = row.id"
          >
            已配置
</button><span v-else>未配置</span>
        </FormItem>
        <FormItem label="状态" class="category-full-row">
          <Tag
            :class="row.status === 0 ? 'category-enabled' : 'category-disabled'"
          >
            {{ row.status === 0 ? '启用' : '禁用' }}
          </Tag>
        </FormItem>
        <FormItem label="描述" class="category-full-row">
          <Input.TextArea :value="row.description" :rows="3" disabled />
        </FormItem>
        <FormItem label="创建人">
          <Input :value="row.creatorName || '-'" disabled />
</FormItem><FormItem label="创建时间">
          <Input :value="categoryTime(row.createTime)" disabled />
        </FormItem>
        <FormItem label="更新人">
          <Input :value="row.updaterName || '-'" disabled />
</FormItem><FormItem label="更新时间">
          <Input :value="categoryTime(row.updateTime)" disabled />
        </FormItem>
      </Form>
      <SafetyMaskFields
        v-else-if="isMask"
        :model-value="mask"
        :readonly="readOnly"
        :disabled="saving || !ready"
        @update:model-value="(value) => Object.assign(mask, value)"
      >
        <template #category><Input :value="row?.name" disabled /></template>
      </SafetyMaskFields>
      <Form
        v-else-if="mode === 'batch'"
        class="category-form"
        :colon="false"
        :label-col="{ style: { width: '110px' } }"
        :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
      >
        <FormItem label="数据分级" required>
          <div class="category-level-picker">
            <Select
              v-model:value="form.dataLevelId"
              :options="levelOptions"
              placeholder="请选择数据分级"
              :disabled="saving || !ready"
            /><Button
              v-if="can('create', 'level')"
              type="primary"
              :disabled="saving"
              @click="openLevel"
            >
              新增分级
            </Button>
          </div>
        </FormItem>
      </Form>
      <Form
        v-else-if="isDirectory"
        class="category-form"
        :colon="false"
        :disabled="saving || !ready"
        :label-col="{ style: { width: '150px' } }"
        :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
      >
        <FormItem label="上级类目" required>
          <TreeSelect
            v-model:value="directory.parentId"
            :tree-data="parentTree"
            :field-names="{
              value: 'key',
              label: 'title',
              children: 'children',
            }"
            tree-default-expand-all
            placeholder="请选择上级类目"
          />
</FormItem><FormItem label="数据分类类目名称" required>
          <Input
            v-model:value="directory.categoryName"
            :maxlength="50"
            placeholder="请输入数据分类类目名称"
          />
</FormItem><FormItem label="描述">
          <Input.TextArea
            v-model:value="directory.remark"
            :rows="4"
            :maxlength="256"
            show-count
            placeholder="请输入描述"
          />
        </FormItem>
      </Form>
      <Form
        v-else
        class="category-form"
        :colon="false"
        :disabled="saving || !ready"
        :label-col="{ style: { width: '110px' } }"
        :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
      >
        <FormItem label="所属类目" required>
          <TreeSelect
            v-model:value="form.categoryId"
            :tree-data="directoryTree"
            :field-names="{
              value: 'key',
              label: 'title',
              children: 'children',
            }"
            tree-default-expand-all
            placeholder="请选择所属类目"
          />
</FormItem><FormItem label="分类名称" required>
          <Input
            v-model:value="form.name"
            :maxlength="50"
            placeholder="请输入分类名称"
          />
</FormItem><FormItem label="数据分级" required>
          <Select
            v-model:value="form.dataLevelId"
            :options="levelOptions"
            placeholder="请选择数据分级"
          />
</FormItem><FormItem label="优先级" required>
          <Select
            v-model:value="form.priority"
            :options="priorities"
            placeholder="请选择优先级"
          />
</FormItem><FormItem label="分类编码">
          <Input
            v-model:value="form.shortName"
            placeholder="请输入分类编码"
          />
</FormItem><FormItem label="状态">
          <RadioGroup
            v-model:value="form.status"
            :options="CATEGORY_STATUS_OPTIONS"
          />
</FormItem><FormItem label="描述">
          <Input.TextArea
            v-model:value="form.description"
            :rows="4"
            :maxlength="256"
            show-count
            placeholder="请输入描述"
          />
        </FormItem>
      </Form>
    </Spin>
    <template #footer>
      <Button :disabled="saving" @click="emit('close')">
        {{ readOnly ? '关闭' : '取消' }}
</Button><Button
        v-if="!readOnly"
        type="primary"
        :loading="saving"
        :disabled="!permitted || loading || !ready"
        @click="save()"
      >
        确定
      </Button>
    </template>
  </Modal>
  <Modal
    :open="levelOpen"
    title="新增数据分级"
    :width="800"
    :centered="false"
    :style="{ top: '6vh' }"
    :mask-closable="false"
    :closable="!levelSaving"
    :keyboard="!levelSaving"
    class="category-reference-dialog"
    @cancel="
      levelOpen = false;
      levelGuard.invalidate();
    "
  >
    <Alert v-if="levelError" :message="levelError" type="error" />
    <Form
      class="category-form"
      :colon="false"
      :disabled="levelSaving || createdLevelId !== undefined"
      :label-col="{ style: { width: '110px' } }"
      :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
    >
      <FormItem label="分级名称" required>
        <Input
          v-model:value="levelForm.safetyName"
          :maxlength="50"
          placeholder="请输入分级名称"
        />
</FormItem><FormItem label="分级简称" required>
        <Input
          v-model:value="levelForm.safetyLevel"
          :maxlength="10"
          placeholder="请输入分级简称"
        />
</FormItem><FormItem label="敏感等级" required>
        <InputNumber
          v-model:value="levelForm.sensitiveLevel"
          :min="1"
          :max="100"
          :precision="0"
          :controls="false"
          placeholder="请输入敏感等级，1-100之间的整数"
        />
</FormItem><FormItem label="状态">
        <RadioGroup
          v-model:value="levelForm.status"
          :options="CATEGORY_STATUS_OPTIONS"
        />
</FormItem><FormItem label="描述">
        <Input.TextArea
          v-model:value="levelForm.description"
          :rows="4"
          :maxlength="256"
          show-count
          placeholder="请输入描述"
        />
      </FormItem>
    </Form>
    <template #footer>
      <Button
        :disabled="levelSaving"
        @click="
          levelOpen = false;
          levelGuard.invalidate();
        "
      >
        取消
</Button><Button type="primary" :loading="levelSaving" @click="saveLevel">
        确定
      </Button>
    </template>
  </Modal>
  <component
    :is="self"
    v-if="nestedMaskId !== undefined"
    :request="{ mode: 'mask-view', id: nestedMaskId }"
    @close="nestedMaskId = undefined"
  />
</template>
