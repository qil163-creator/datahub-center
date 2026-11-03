<script lang="ts" setup>
import { ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button } from 'antdv-next';

const expanded = ref(true);
const sources = [
  { name: '业务系统', icon: 'lucide:database' },
  { name: '日志数据', icon: 'lucide:files' },
  { name: '第三方数据', icon: 'lucide:cloud' },
  { name: '接口采集', icon: 'lucide:cable' },
];
const services = [
  { name: '报表中心', icon: 'lucide:chart-no-axes-combined' },
  { name: '数据大屏', icon: 'lucide:panels-top-left' },
  { name: '数据 API', icon: 'lucide:radar' },
  { name: '数据集市', icon: 'lucide:database' },
];
</script>

<template>
  <section class="warehouse-architecture" aria-labelledby="architecture-title">
    <header class="architecture-heading">
      <div>
        <h2 id="architecture-title">数据架构总览</h2>
        <span>明确各层职责，让数据有序流转</span>
      </div>
      <Button
        type="text"
        size="small"
        :aria-expanded="expanded"
        aria-controls="warehouse-flow"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起' : '展开'
        }}<IconifyIcon
          :icon="expanded ? 'lucide:chevron-up' : 'lucide:chevron-down'"
        />
      </Button>
    </header>
    <div v-if="expanded" id="warehouse-flow" class="architecture-scroll">
      <div class="architecture-flow">
        <div class="architecture-endpoint">
          <h3>数据源</h3>
          <div
            v-for="source in sources"
            :key="source.name"
            class="endpoint-item"
          >
            <IconifyIcon :icon="source.icon" /><span>{{ source.name }}</span>
          </div>
        </div>
        <IconifyIcon class="architecture-arrow" icon="lucide:move-right" />
        <div class="architecture-stage stage-ods">
          <div class="stage-icon"><IconifyIcon icon="lucide:database" /></div>
          <div>
            <strong>ODS</strong>
            <h3>操作数据层</h3>
            <p>原始数据接入</p>
          </div>
        </div>
        <IconifyIcon class="architecture-arrow" icon="lucide:move-right" />
        <div class="architecture-public">
          <span class="stage-label">公共层</span>
          <div class="public-inputs">
            <div class="public-stage">
              <IconifyIcon icon="lucide:boxes" />
              <div>
                <strong>DIM</strong>
                <h3>公共维度层</h3>
              </div>
            </div>
            <div class="public-stage">
              <IconifyIcon icon="lucide:table-properties" />
              <div>
                <strong>DWD</strong>
                <h3>明细数据层</h3>
              </div>
            </div>
          </div>
          <div class="public-arrows" aria-hidden="true">
            <IconifyIcon icon="lucide:move-down-right" /><IconifyIcon
              icon="lucide:move-up-right"
            />
          </div>
          <div class="public-stage public-summary">
            <IconifyIcon icon="lucide:layers-3" />
            <div>
              <strong>DWS</strong>
              <h3>汇总数据层</h3>
            </div>
          </div>
        </div>
        <IconifyIcon class="architecture-arrow" icon="lucide:move-right" />
        <div class="architecture-stage stage-ads">
          <div class="stage-icon">
            <IconifyIcon icon="lucide:chart-column" />
          </div>
          <div>
            <strong>ADS</strong>
            <h3>数据应用层</h3>
            <p>面向业务场景</p>
          </div>
        </div>
        <IconifyIcon class="architecture-arrow" icon="lucide:move-right" />
        <div class="architecture-endpoint">
          <h3>下游服务</h3>
          <div
            v-for="service in services"
            :key="service.name"
            class="endpoint-item"
          >
            <IconifyIcon :icon="service.icon" /><span>{{ service.name }}</span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.warehouse-architecture {
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.architecture-heading {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid hsl(var(--border));
}

.architecture-heading > div {
  display: flex;
  gap: 16px;
  align-items: center;
}

.architecture-heading h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.architecture-heading span {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.architecture-scroll {
  padding: 20px 26px;
  overflow-x: auto;
}

.architecture-flow {
  display: grid;
  grid-template-columns:
    minmax(104px, 0.7fr) 36px minmax(130px, 1fr)
    36px minmax(320px, 2.5fr) 36px minmax(130px, 1fr) 36px minmax(104px, 0.7fr);
  gap: 8px;
  align-items: center;
  min-width: 1020px;
  max-width: 1600px;
  margin: 0 auto;
}

.architecture-endpoint {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.architecture-endpoint h3 {
  margin: 0 0 5px;
  font-size: 13px;
  font-weight: 500;
  text-align: center;
}

.endpoint-item {
  display: flex;
  gap: 9px;
  align-items: center;
  justify-content: center;
  height: 36px;
  font-size: 12px;
  background: hsl(var(--muted) / 45%);
  border: 1px solid hsl(var(--border));
  border-radius: 5px;
}

.endpoint-item svg {
  font-size: 19px;
  color: #6c87b8;
}

.architecture-arrow {
  justify-self: center;
  width: 26px;
  height: 26px;
  color: #8aa4d6;
}

.architecture-stage {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: center;
  min-height: 116px;
  padding: 18px 12px;
  background: color-mix(in srgb, hsl(var(--card)) 94%, #4b8bff);
  border: 1px solid #b9d3ff;
  border-radius: 7px;
}

.stage-label {
  position: absolute;
  top: -10px;
  left: 15px;
  padding: 0 8px;
  font-size: 12px;
  color: #6280b2;
  background: hsl(var(--card));
}

.stage-icon {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 32px;
  height: 38px;
  font-size: 30px;
  color: #5687eb;
}

.architecture-stage strong,
.public-stage strong {
  font-size: 22px;
  color: #4e77c6;
  letter-spacing: 0.03em;
}

.architecture-stage h3,
.public-stage h3 {
  margin: 5px 0 0;
  font-size: 12px;
  font-weight: 500;
}

.architecture-stage p {
  margin: 8px 0 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.architecture-public {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 28px 1fr;
  align-items: center;
  min-height: 200px;
  padding: 18px 14px;
  background: color-mix(in srgb, hsl(var(--card)) 97%, #4b8bff);
  border: 1px dashed #a9c3e8;
  border-radius: 7px;
}

.public-inputs {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.public-stage {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  min-height: 70px;
  padding: 12px 8px;
  background: color-mix(in srgb, hsl(var(--card)) 92%, #65a8ff);
  border: 1px solid #bfd4f1;
  border-radius: 5px;
}

.public-stage > svg {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  color: #6b8be5;
}

.public-stage strong {
  font-size: 18px;
}

.public-stage h3 {
  font-size: 11px;
  white-space: nowrap;
}

.public-arrows {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: center;
  font-size: 24px;
  color: #8aa4d6;
}

.public-summary {
  background: color-mix(in srgb, hsl(var(--card)) 94%, #8e9ff2);
}

.stage-ads {
  background: color-mix(in srgb, hsl(var(--card)) 95%, #9d82f4);
  border-color: #d1c6f6;
}

.stage-ads .stage-icon,
.stage-ads strong {
  color: #9874d3;
}

.dark .architecture-stage {
  border-color: #375986;
}

.dark .architecture-public {
  border-color: #32676a;
}

.dark .public-stage {
  border-color: #375986;
}

.dark .stage-ads {
  border-color: #5a477b;
}

@media (max-width: 640px) {
  .architecture-heading > div {
    flex-direction: column;
    gap: 4px;
    align-items: flex-start;
  }

  .architecture-scroll {
    padding: 20px 18px;
  }
}
</style>
