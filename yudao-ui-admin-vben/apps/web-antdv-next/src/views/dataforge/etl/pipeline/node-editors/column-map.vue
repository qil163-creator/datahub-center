<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Input } from 'antdv-next';

defineOptions({ name: 'EtlNodeColumnMap' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

function addMapper() {
  props.config.regexColumnMapper.push({ fromPattern: '', toValue: '' });
}

function removeMapper(index: number) {
  props.config.regexColumnMapper.splice(index, 1);
}

onMounted(() => {
  if (!Array.isArray(props.config.regexColumnMapper)) {
    props.config.regexColumnMapper = [{ fromPattern: '', toValue: '' }];
  }
});
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="text-muted-foreground text-xs">
      按正则匹配字段名：目标名为空表示移除该字段；如 ^user_name$ → name
    </div>
    <div
      v-for="(mapper, index) in config.regexColumnMapper ?? []"
      :key="index"
      class="flex items-center gap-2"
    >
      <AutoComplete
        v-model:value="mapper.fromPattern"
        :options="fieldOptions()"
        class="flex-1"
        placeholder="源字段（正则）"
      />
      <IconifyIcon
        class="text-muted-foreground size-4"
        icon="lucide:arrow-right"
      />
      <Input
        v-model:value="mapper.toValue"
        class="flex-1"
        placeholder="目标字段名（空=移除）"
      />
      <IconifyIcon
        class="size-4 cursor-pointer text-red-500"
        icon="lucide:trash-2"
        @click="removeMapper(Number(index))"
      />
    </div>
    <Button class="self-start" size="small" type="dashed" @click="addMapper">
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      添加映射
    </Button>
  </div>
</template>
