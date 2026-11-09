<script setup lang="ts">
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';

import { computed } from 'vue';

import { formatDateTime } from '@vben/utils';

import { Descriptions, DescriptionsItem, Tag } from 'antdv-next';

import DetailBackButton from '#/components/detail-back-button/index.vue';

import defaultIcon from './reference-assets/domain-icon.png';

const props = defineProps<{
  board?: ModelingDataDomainApi.DataDomain;
  item: ModelingDataDomainApi.DataDomain &
    ModelingSubjectDomainApi.SubjectDomain;
  kind: 'domain' | 'subject';
}>();
defineEmits<{ back: [] }>();
const person = (name?: string, phone?: string) =>
  [name || '-', phone].filter(Boolean).join('/');
const date = (value?: number | string) =>
  formatDateTime(value)?.slice(0, 16) || '-';
const cells = computed(() =>
  props.kind === 'domain'
    ? [
        { label: '板块英文名称', value: props.item.code },
        { label: '板块图标', icon: props.item.icon || defaultIcon },
        {
          label: '板块架构师',
          value: person(props.item.leaderName, props.item.leaderPhone),
        },
        { label: '描述', value: props.item.description, wide: true },
        {
          label: '创建人',
          value: person(props.item.creatorName, props.item.creatorPhone),
        },
        { label: '创建时间', value: date(props.item.createTime) },
        { label: '更新时间', value: date(props.item.updateTime) },
      ]
    : [
        { label: '主题域编码', value: props.item.code },
        { label: '英文缩写', value: props.item.abbreviation },
        {
          label: '所属板块',
          value: props.board?.name || props.item.dataDomainName,
          icon: props.board?.icon || defaultIcon,
        },
        { label: '描述', value: props.item.description, wide: true },
        {
          label: '负责人',
          value: person(props.item.leaderName, props.item.leaderPhone),
        },
        {
          label: '创建人',
          value: person(props.item.creatorName, props.item.creatorPhone),
        },
        { label: '创建时间', value: date(props.item.createTime) },
      ],
);
</script>

<template>
  <section class="planning-detail-summary">
    <div class="planning-detail-title">
      <span class="planning-number" :title="String(item.id)">{{ item.id }}</span>
      <h1>{{ item.name }}</h1>
      <Tag :color="item.status === 0 ? 'success' : 'default'">
        {{ item.status === 0 ? '启用' : '禁用' }}
      </Tag>
      <DetailBackButton class="ml-auto shrink-0" @click="$emit('back')" />
    </div>
    <Descriptions
      class="planning-detail-info"
      :column="{ xs: 1, sm: 1, md: 2, lg: 3 }"
      bordered
      size="small"
    >
      <DescriptionsItem
        v-for="cell in cells"
        :key="cell.label"
        :label="cell.label"
        :span="cell.wide ? 'filled' : 1"
      >
        <span class="planning-detail-value">
          <img
            v-if="cell.icon"
            :src="cell.icon"
            alt=""
            :class="{ large: kind === 'domain' }"
          />{{ cell.value || (cell.icon ? '' : '-') }}
        </span>
      </DescriptionsItem>
    </Descriptions>
  </section>
</template>
