<script setup lang="ts">
import type { GovernanceMetaFileApi as Api } from '#/api/governance/metafile';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  TabPane,
  Tabs,
} from 'antdv-next';

import { getMetaFile } from '#/api/governance/metafile';
import { useObjectRequest } from '#/hooks/use-object-request';

import { metadataSize, metadataTime } from '../metadata-common/helpers';
import { metadataIcon } from '../metadata-common/icons';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaFileDetail' });
const route = useRoute();
const router = useRouter();
const id = computed(() =>
  route.name === 'GovernanceMetaFileDetail'
    ? String(route.params.id ?? '')
    : '',
);
const detail = ref<Api.MetaFile>();
const error = ref(false);
const requests = useObjectRequest(
  () => id.value,
  () => {
    detail.value = undefined;
  },
);
async function load() {
  if (!id.value) return;
  const current = requests.begin();
  error.value = false;
  try {
    const result = await getMetaFile(id.value);
    if (current()) detail.value = result;
  } catch {
    if (current()) error.value = true;
  }
}
watch(id, load, { immediate: true });
</script>
<template>
  <Page auto-content-height content-class="metadata-page">
    <Alert v-if="error" type="error" message="文件元数据读取失败">
      <template #action>
        <Button @click="load">
          <template #icon>
            <IconifyIcon :icon="metadataIcon('refresh')" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <div class="metadata-detail">
      <section class="metadata-panel">
        <div class="metadata-detail__title">
          <span class="metadata-detail__id">{{ detail?.id }}</span>
          <h2>{{ detail?.name || '文件元数据详情' }}</h2>
          <span class="metadata-tag">{{
            detail?.fileType?.toUpperCase() || '-'
          }}</span><Button
            class="metadata-detail__back"
            size="small"
            @click="
              router.push({
                path: String(
                  route.meta.activePath || '/govern/metadata/metaobject',
                ),
                query: { kind: 'file' },
              })
            "
          >
            返回
          </Button>
        </div>
        <Descriptions bordered size="small" :column="3">
          <DescriptionsItem label="来源系统">
            {{ detail?.sourceSystemName || '-' }}
</DescriptionsItem><DescriptionsItem label="文件类型">
            <span class="metadata-tag">{{
              detail?.fileType || '-'
            }}</span>
</DescriptionsItem><DescriptionsItem label="文件大小">
            <span class="metadata-tag">{{ metadataSize(detail?.size) }}</span>
          </DescriptionsItem>
          <DescriptionsItem label="描述" :span="3">
            {{ detail?.description || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="创建人">
            {{ detail?.creatorName || '-' }}
</DescriptionsItem><DescriptionsItem label="创建时间">
            {{
              metadataTime(detail?.createTime || detail?.firstSeenTime)
            }}
</DescriptionsItem><DescriptionsItem label="更新人">
            {{ detail?.updaterName || '-' }}
          </DescriptionsItem>
        </Descriptions>
      </section>
      <section class="metadata-panel min-h-0 flex-1">
        <Tabs active-key="base">
          <TabPane key="base" tab="基本信息">
            <Descriptions bordered size="small" :column="2">
              <DescriptionsItem label="数据连接名称">
                {{ detail?.datasourceName || '-' }}
</DescriptionsItem><DescriptionsItem label="数据连接类型">
                {{ detail?.dbType || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="文件名">
                {{ detail?.name || '-' }}
</DescriptionsItem><DescriptionsItem label="文件大小">
                <span class="metadata-tag">{{
                  metadataSize(detail?.size)
                }}</span>
              </DescriptionsItem>
              <DescriptionsItem label="文件路径">
                {{ detail?.path || '-' }}
</DescriptionsItem><DescriptionsItem label="更新时间">
                {{ metadataTime(detail?.sourceModifiedTime) }}
              </DescriptionsItem>
              <DescriptionsItem label="业务责任人">
                {{
                  detail?.businessLeaderName || detail?.ownerName || '-'
                }}
</DescriptionsItem><DescriptionsItem label="业务责任人电话">
                {{ detail?.businessLeaderPhone || detail?.ownerPhone || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="技术责任人">
                {{ detail?.techLeaderName || '-' }}
</DescriptionsItem><DescriptionsItem label="技术责任人电话">
                {{ detail?.techLeaderPhone || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="最近采集时间">
                {{ metadataTime(detail?.lastSeenTime) }}
</DescriptionsItem><DescriptionsItem label="更新人">
                {{ detail?.updaterName || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="源端状态">
                {{
                  detail?.objectStatus === 2
                    ? '源端缺失'
                    : detail
                      ? '存在'
                      : '-'
                }}
</DescriptionsItem><DescriptionsItem label="创建时间">
                {{ metadataTime(detail?.createTime || detail?.firstSeenTime) }}
              </DescriptionsItem>
            </Descriptions>
          </TabPane>
        </Tabs>
      </section>
    </div>
  </Page>
</template>
