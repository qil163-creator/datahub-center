<script lang="ts" setup>
import { onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { handleTree } from '@vben/utils';

import { Button, Select, TreeSelect } from 'antdv-next';

import { getSimpleDeptList } from '#/api/system/dept';
import { getSimpleUserList } from '#/api/system/user';

defineOptions({ name: 'DataforgeFillOrgAssigneeList' });

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    modelValue?: OrgAssigneeRow[];
    value?: OrgAssigneeRow[];
  }>(),
  {
    disabled: false,
    modelValue: undefined,
    value: undefined,
  },
);

const emit = defineEmits<{
  (e: 'update:modelValue' | 'update:value', value: OrgAssigneeRow[]): void;
}>();

export interface OrgAssigneeRow {
  assigneeUserIds: (number | string)[];
  reportingOrgId?: number | string;
}

const rows = ref<OrgAssigneeRow[]>([{ assigneeUserIds: [] }]);
const deptTree = ref<any[]>([]);
const userOptions = ref<{ label: string; value: number | string }[]>([]);

function commit(next: OrgAssigneeRow[]) {
  rows.value = next;
  emit('update:modelValue', next);
  emit('update:value', next);
}

watch(
  () => props.modelValue ?? props.value,
  (next) => {
    if (next && next.length > 0) {
      rows.value = next;
    }
  },
  { immediate: true },
);

onMounted(async () => {
  const [depts, users] = await Promise.all([
    getSimpleDeptList(),
    getSimpleUserList(),
  ]);
  deptTree.value = handleTree(depts as any[]);
  userOptions.value = (users as any[]).map((user) => ({
    label: user.nickname,
    value: user.id,
  }));
});

function addRow() {
  commit([...rows.value, { assigneeUserIds: [] }]);
}

function removeRow(index: number) {
  commit(rows.value.filter((_, i) => i !== index));
}

function updateRow(index: number, patch: Partial<OrgAssigneeRow>) {
  commit(
    rows.value.map((row, i) => (i === index ? { ...row, ...patch } : row)),
  );
}

function onAssigneesChange(index: number, value: unknown) {
  const ids = Array.isArray(value)
    ? value.filter(
        (id): id is number | string =>
          typeof id === 'number' || typeof id === 'string',
      )
    : [];
  updateRow(index, { assigneeUserIds: ids });
}
</script>

<template>
  <div class="flex w-full flex-col gap-2">
    <div
      v-for="(row, index) in rows"
      :key="index"
      class="flex items-start gap-2"
    >
      <TreeSelect
        :disabled="disabled"
        :field-names="{ children: 'children', label: 'name', value: 'id' }"
        :tree-data="deptTree"
        :value="row.reportingOrgId"
        class="w-56 shrink-0"
        placeholder="选择报送组织"
        show-search
        tree-default-expand-all
        tree-node-filter-prop="name"
        @update:value="
          (val: number | string) => updateRow(index, { reportingOrgId: val })
        "
      />
      <Select
        :disabled="disabled"
        :options="userOptions"
        :value="row.assigneeUserIds"
        class="flex-1"
        mode="multiple"
        option-filter-prop="label"
        placeholder="选择该组织的填报人（可多选）"
        show-search
        @update:value="onAssigneesChange(index, $event)"
      />
      <Button
        :disabled="disabled || rows.length <= 1"
        danger
        type="text"
        @click="removeRow(index)"
      >
        <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
        删除
      </Button>
    </div>
    <Button :disabled="disabled" class="w-40" type="dashed" @click="addRow">
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      + 添加目标组织
    </Button>
  </div>
</template>
