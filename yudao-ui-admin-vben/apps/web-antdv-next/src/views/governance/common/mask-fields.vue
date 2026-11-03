<script lang="ts" setup>
import type { SafetyMaskFields } from './mask-fields';

import {
  Button,
  CheckboxGroup,
  Form,
  FormItem,
  Input,
  InputNumber,
  RadioGroup,
  Select,
} from 'antdv-next';

defineProps<{ disabled?: boolean; readonly?: boolean }>();
const model = defineModel<SafetyMaskFields>({ required: true });
</script>

<template>
  <Form
    class="category-form category-mask-form"
    :colon="false"
    :disabled="disabled || readonly"
    :label-col="{ style: { width: '110px' } }"
    :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
  >
    <FormItem label="脱敏规则名称" required>
      <Input
        v-model:value="model.ruleName"
        :maxlength="64"
        placeholder="请输入脱敏规则名称"
      />
    </FormItem>
    <FormItem label="数据分类" required><slot name="category"></slot></FormItem>
    <FormItem label="应用场景" required>
      <CheckboxGroup
        v-model:value="model.applicationScene"
        :options="[
          { label: '数据资产', value: '1' },
          { label: '数据查询', value: '2' },
          { label: '数据服务', value: '3', disabled: true },
        ]"
      />
    </FormItem>
    <FormItem label="脱敏方式" required>
      <RadioGroup
        v-model:value="model.maskType"
        :options="[
          { label: '底层脱敏', value: '1', disabled: true },
          { label: '展示脱敏', value: '2' },
        ]"
      />
    </FormItem>
    <FormItem label="转换规则" required>
      <Select
        v-model:value="model.replaceRule"
        :options="[
          { label: '自定义掩码', value: '1' },
          { label: '自定义映射', value: '2', disabled: true },
        ]"
        placeholder="请选择转换规则"
      />
    </FormItem>
    <FormItem label="替换内容" required>
      <Input
        v-model:value="model.replaceContent"
        :maxlength="256"
        placeholder="请输入替换内容"
      />
    </FormItem>
    <FormItem label="脱敏区间" required class="category-full-row">
      <div class="category-ranges">
        <div
          v-for="(interval, index) in model.intervalList"
          :key="index"
          class="category-range-row"
        >
          <span>区间 {{ index + 1 }}：</span><InputNumber
            v-model:value="interval.startNum"
            :min="1"
            :precision="0"
            :controls="false"
            placeholder="起始位置"
          /><InputNumber
            v-model:value="interval.endNum"
            :min="1"
            :precision="0"
            :controls="false"
            placeholder="截止位置"
          /><Button
            type="link"
            danger
            :disabled="disabled || model.intervalList.length <= 1 || readonly"
            aria-label="删除区间"
            @click="model.intervalList.splice(index, 1)"
          >
            <i
              class="category-icon"
              :style="{ maskImage: 'url(/assets/qdata-tag/delete.svg)' }"
            ></i>
</Button><Button
            v-if="index === model.intervalList.length - 1 && !readonly"
            type="link"
            :disabled="disabled || model.intervalList.length >= 100"
            @click="model.intervalList.push({ startNum: null, endNum: null })"
          >
            ＋ 添加区间
          </Button>
        </div>
      </div>
    </FormItem>
    <FormItem label="状态" class="category-full-row">
      <RadioGroup
        v-model:value="model.status"
        :options="[
          { label: '禁用', value: 1 },
          { label: '启用', value: 0 },
        ]"
      />
    </FormItem>
    <FormItem label="描述" class="category-full-row">
      <Input.TextArea
        v-model:value="model.description"
        :rows="4"
        :maxlength="256"
        show-count
        placeholder="请输入描述"
      />
    </FormItem>
  </Form>
</template>
