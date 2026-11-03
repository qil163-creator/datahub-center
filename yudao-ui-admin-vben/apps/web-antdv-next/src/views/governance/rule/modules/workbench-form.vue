<script lang="ts" setup>
import type { SafetyRuleWorkbenchApi as Api } from '#/api/governance/rule/workbench';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { Alert, Button, Input, Modal, Spin, TreeSelect } from 'antdv-next';

import {
  getSafetyRule,
  getSafetyRuleOptions,
  saveSafetyRule,
} from '#/api/governance/rule/workbench';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { validCategoryRevision } from '#/views/governance/category/reference-data';
import { useCategorySession } from '#/views/governance/category/use-session';
import {
  newSafetyMaskFields,
  safetyMaskFieldsError,
} from '#/views/governance/common/mask-fields';
import SafetyMaskFields from '#/views/governance/common/mask-fields.vue';

import { ruleCategoryTree, ruleVersion } from '../reference-data';

const props = defineProps<{ id?: Api.Id }>();
const emit = defineEmits<{ close: []; success: [] }>();
const { identity, can } = useCategorySession();
const editing = computed(() => !!parseResourceId(props.id));
const allowed = computed(() =>
  can(editing.value ? 'update' : 'create', 'rule'),
);
const form = reactive<Api.Save>({
  ...newSafetyMaskFields(),
  categoryId: '',
  categoryRevision: '',
});
const options = ref<Api.Options>({ directories: [], categories: [] });
const categoryTree = computed(() =>
  ruleCategoryTree(options.value, editing.value ? form.categoryId : undefined),
);
const fresh = ref<Api.Rule>();
const loading = ref(false);
const saving = ref(false);
const ready = ref(false);
const error = ref('');
const guard = createLatestWins();
async function load() {
  guard.invalidate();
  ready.value = false;
  error.value = '';
  fresh.value = undefined;
  Object.assign(form, newSafetyMaskFields(), {
    id: undefined,
    ruleVersionId: undefined,
    ruleRevisionToken: undefined,
    categoryId: '',
    categoryRevision: '',
  });
  if (!allowed.value) return;
  const ticket = guard.next();
  loading.value = true;
  try {
    const [context, row] = await Promise.all([
      getSafetyRuleOptions(),
      editing.value ? getSafetyRule(props.id!) : Promise.resolve(undefined),
    ]);
    if (!ticket.isCurrent()) return;
    if (
      row &&
      (resourceIdKey(row.id) !== resourceIdKey(props.id) || !ruleVersion(row))
    )
      throw new Error('RULE_IDENTITY');
    options.value = context;
    fresh.value = row;
    if (row)
      Object.assign(form, row, {
        categoryId: resourceIdKey(row.categoryId),
        applicationScene: [...row.applicationScene],
        intervalList: row.intervalList.map((item) => ({ ...item })),
      });
    ready.value = true;
  } catch {
    if (ticket.isCurrent())
      error.value = '脱敏规则或分类读取失败，请关闭后刷新重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function save() {
  if (!ready.value || saving.value || !allowed.value) return;
  const invalid = safetyMaskFieldsError(form);
  if (invalid) {
    error.value = invalid;
    return;
  }
  const category = options.value.categories.find(
    (item) => resourceIdKey(item.id) === resourceIdKey(form.categoryId),
  );
  const categoryRevision =
    editing.value &&
    resourceIdKey(form.categoryId) === resourceIdKey(fresh.value?.categoryId)
      ? fresh.value?.categoryRevision
      : category?.revision;
  if (
    !category ||
    !parseResourceId(form.categoryId) ||
    !validCategoryRevision(categoryRevision) ||
    (editing.value && !ruleVersion(fresh.value))
  ) {
    error.value = '请选择有效数据分类，或刷新规则状态后重试。';
    return;
  }
  const ticket = guard.next();
  saving.value = true;
  error.value = '';
  try {
    await saveSafetyRule({
      id: fresh.value?.id,
      ruleVersionId: fresh.value?.ruleVersionId,
      ruleRevisionToken: fresh.value?.ruleRevisionToken,
      categoryId: form.categoryId,
      categoryRevision,
      ruleName: form.ruleName.trim(),
      applicationScene: [...form.applicationScene],
      maskType: '2',
      replaceRule: '1',
      replaceContent: form.replaceContent,
      intervalList: form.intervalList.map((item) => ({ ...item })),
      status: form.status,
      description: form.description,
    });
    if (!ticket.isCurrent()) return;
    emit('success');
    emit('close');
  } catch {
    if (ticket.isCurrent())
      error.value = '保存失败，规则或分类可能已被修改，请刷新后重试。';
  } finally {
    if (ticket.isCurrent()) saving.value = false;
  }
}
watch(
  () => props.id,
  () => {
    void load();
  },
  { immediate: true },
);
watch(
  identity,
  () => {
    guard.invalidate();
    ready.value = false;
    emit('close');
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => guard.invalidate());
</script>
<template>
  <Modal
    :open="true"
    :title="editing ? '修改脱敏规则' : '新增脱敏规则'"
    :width="920"
    :centered="false"
    :style="{ top: '6vh' }"
    :mask-closable="false"
    :closable="!saving"
    :keyboard="!saving"
    class="category-reference-dialog"
    @cancel="emit('close')"
  >
    <Alert v-if="error" :message="error" type="error" show-icon />
    <Spin :spinning="loading">
      <SafetyMaskFields
        :model-value="form"
        :disabled="saving || !ready || !allowed"
        @update:model-value="(value) => Object.assign(form, value)"
      >
        <template #category>
          <Input
            v-if="editing"
            :value="fresh?.categoryName"
            disabled
          /><TreeSelect
            v-else
            v-model:value="form.categoryId"
            :tree-data="categoryTree"
            tree-default-expand-all
            show-search
            tree-node-filter-prop="title"
            placeholder="请选择数据分类"
          />
        </template>
      </SafetyMaskFields>
    </Spin>
    <template #footer>
      <Button :disabled="saving" @click="emit('close')">取消</Button><Button
        type="primary"
        :loading="saving"
        :disabled="loading || !ready || !allowed"
        @click="save"
      >
        确定
      </Button>
    </template>
  </Modal>
</template>
