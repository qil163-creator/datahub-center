<script setup lang="ts">
import type { FormInstance } from 'antdv-next';

import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

import { computed, onDeactivated, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  Spin,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import {
  createProjectCategory,
  getProjectCategory,
  getProjectCategoryList,
  updateProjectCategory,
} from '#/api/dataforge/projectcategory';
import { useObjectRequest } from '#/hooks/use-object-request';
import { sameResourceId } from '#/utils/resource-id';

import {
  categoryLabels,
  categoryParentOptions,
  categoryPermission,
  isPositiveCount,
} from './model';
import ReferenceIcon from './reference-icon.vue';

const props = defineProps<{ kind: Api.Kind }>();
const emit = defineEmits<{ saved: [] }>();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const label = computed(() => categoryLabels[props.kind]);
const scope = computed(() =>
  JSON.stringify([
    props.kind,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const visible = ref(false);
const loading = ref(false);
const ready = ref(false);
const saving = ref(false);
const failed = ref(false);
const editId = ref<Api.Id>();
const original = ref<Api.Category>();
const changeConfirmation = ref('');
const parents = ref<Api.Category[]>([]);
const formRef = ref<FormInstance>();
const model = reactive({
  parentId: '0',
  name: '',
  status: 0,
  sort: 0 as null | number,
  description: '',
});
const options = computed(() =>
  categoryParentOptions(parents.value, editId.value),
);
const nameLimit = computed(() =>
  props.kind === 'masterPush' ? 128 : 50,
);
const descriptionLimit = computed(() =>
  props.kind === 'masterPush' ? 500 : 256,
);
const rules = computed(() => ({
  parentId: [{ required: true, message: '请选择上级类目' }],
  name: [
    { required: true, whitespace: true, message: `请输入${label.value}名称` },
    {
      max: nameLimit.value,
      message: `类目名称不能超过${nameLimit.value}个字符`,
    },
    {
      pattern: /[\p{Script=Han}a-z0-9]/iu,
      message: '类目名称须包含中文、字母或数字',
    },
  ],
  sort: [
    {
      type: 'integer' as const,
      min: 0,
      max: 100_000,
      message: '排序须为0至100000的整数',
    },
  ],
  description: [
    {
      max: descriptionLimit.value,
      message: `描述不能超过${descriptionLimit.value}个字符`,
    },
  ],
}));
const request = useObjectRequest(() => scope.value, invalidate);

function invalidate() {
  request.begin();
  visible.value = false;
  loading.value = false;
  ready.value = false;
  saving.value = false;
  failed.value = false;
  editId.value = undefined;
  original.value = undefined;
  changeConfirmation.value = '';
  parents.value = [];
  Object.assign(model, {
    parentId: '0',
    name: '',
    status: 0,
    sort: 0,
    description: '',
  });
  formRef.value?.clearValidate();
}

async function open(data: { id?: Api.Id; parentId?: Api.Id } = {}) {
  invalidate();
  const editing = data.id !== undefined;
  if (
    !hasAccessByCodes([
      categoryPermission(props.kind, editing ? 'update' : 'create'),
    ])
  )
    return;
  editId.value = data.id;
  model.parentId = String(data.parentId ?? 0);
  visible.value = true;
  loading.value = true;
  const current = request.begin();
  try {
    const [rows, detail] = await Promise.all([
      getProjectCategoryList(props.kind),
      editing ? getProjectCategory(props.kind, data.id!) : undefined,
    ]);
    if (!current() || !visible.value) return;
    if (editing && !sameResourceId(detail?.id, data.id))
      throw new Error('CATEGORY_IDENTITY');
    parents.value = rows;
    if (detail) {
      original.value = detail;
      Object.assign(model, {
        parentId: String(detail.parentId),
        name: detail.name,
        status: detail.status,
        sort: detail.sort,
        description: detail.description ?? '',
      });
    }
    ready.value = true;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function cancel() {
  if (!saving.value) invalidate();
}

async function save(confirmed = false) {
  if (!visible.value || !ready.value || saving.value) return;
  if (
    !hasAccessByCodes([
      categoryPermission(
        props.kind,
        editId.value === undefined ? 'create' : 'update',
      ),
    ])
  )
    return;
  saving.value = true;
  const current = request.begin();
  try {
    await formRef.value?.validate();
    if (!current() || !visible.value) return;
    if (!confirmed && original.value) {
      if (
        isPositiveCount(original.value.taskCount) &&
        model.name !== original.value.name
      ) {
        changeConfirmation.value = `该类目已被${original.value.taskCount}个任务使用，修改名称后\n任务归属显示将同步变化。`;
        return;
      }
      if (model.parentId !== String(original.value.parentId)) {
        changeConfirmation.value =
          '修改上级类目会影响该类目下任务的归属路径，请确认。';
        return;
      }
    }
    const payload: Api.Save = {
      kind: props.kind,
      id: editId.value,
      parentId: model.parentId,
      name: model.name.trim(),
      status: model.status,
      sort: model.sort ?? 0,
      description: model.description,
    };
    await (editId.value === undefined
      ? createProjectCategory(payload)
      : updateProjectCategory(payload));
    if (!current() || !visible.value) return;
    message.success(editId.value === undefined ? '新增成功' : '修改成功');
    invalidate();
    emit('saved');
  } catch {
    // 表单显示校验结果，请求失败由统一请求层提示并保留修改内容。
  } finally {
    if (current()) saving.value = false;
  }
}

onDeactivated(invalidate);
function confirmSave() {
  changeConfirmation.value = '';
  void save(true);
}
defineExpose({ open, close: invalidate });
</script>

<template>
  <Modal
    :open="visible"
    :title="`${editId === undefined ? '添加' : '修改'}${label}`"
    :width="800"
    :confirm-loading="saving"
    :ok-button-props="{ disabled: !ready }"
    :closable="!saving"
    :keyboard="!saving"
    :mask-closable="false"
    class="qpc-modal"
    ok-text="确定"
    cancel-text="取消"
    destroy-on-hidden
    @cancel="cancel"
    @ok="save(false)"
  >
    <template #closeIcon><ReferenceIcon name="close" /></template>
    <Alert
      v-if="failed"
      type="error"
      message="类目信息加载失败，请关闭后重试"
      show-icon
      class="qpc-form-error"
    />
    <Spin :spinning="loading">
      <Form
        ref="formRef"
        :model="model"
        :rules="rules"
        :label-col="{ style: { width: '130px' } }"
        :wrapper-col="{ style: { flex: '1', minWidth: '0' } }"
        :disabled="!ready || saving"
        layout="horizontal"
        class="qpc-form grid grid-cols-1 gap-x-6 sm:grid-cols-2"
        autocomplete="off"
      >
        <FormItem label="上级类目" name="parentId">
          <TreeSelect
            v-model:value="model.parentId"
            :tree-data="options"
            :allow-clear="false"
            tree-default-expand-all
            :classes="{ popup: { root: 'qpc-parent-popup' } }"
          />
        </FormItem>
        <FormItem :label="`${label}名称`" name="name">
          <Input
            v-model:value="model.name"
            :placeholder="`请输入${label}名称`"
            :maxlength="nameLimit"
          />
        </FormItem>
        <FormItem label="状态" name="status">
          <RadioGroup
            v-model:value="model.status"
            :options="[
              { label: '启用', value: 0 },
              { label: '禁用', value: 1 },
            ]"
          />
        </FormItem>
        <FormItem label="排序" name="sort">
          <InputNumber
            v-model:value="model.sort"
            :min="0"
            :max="100000"
            :precision="0"
            class="w-full"
          />
        </FormItem>
        <FormItem label="描述" name="description" class="col-span-full">
          <TextArea
            v-model:value="model.description"
            :maxlength="descriptionLimit"
            :rows="4"
            show-count
            placeholder="请输入描述"
          />
        </FormItem>
      </Form>
    </Spin>
  </Modal>
  <Modal
    :open="!!changeConfirmation"
    title="系统提示"
    :width="420"
    :mask-closable="false"
    class="qpc-confirmation"
    ok-text="确定"
    cancel-text="取消"
    @ok="confirmSave"
    @cancel="changeConfirmation = ''"
  >
    <template #closeIcon><ReferenceIcon name="close" /></template>
    <p class="whitespace-pre-wrap break-words">{{ changeConfirmation }}</p>
  </Modal>
</template>
