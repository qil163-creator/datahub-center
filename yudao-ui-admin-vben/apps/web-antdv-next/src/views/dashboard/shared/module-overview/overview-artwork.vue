<script lang="ts" setup>
import { IconifyIcon } from '@vben/icons';

/** 模块概览横幅插画：立方体底座 + 两侧可配置的悬浮图标（与建模概览保持同一视觉）。 */
withDefaults(defineProps<{ compact?: boolean; icons?: [string, string] }>(), {
  compact: false,
  icons: () => ['lucide:chart-no-axes-combined', 'lucide:file-chart-column'],
});
</script>

<template>
  <div
    :class="{ 'model-artwork--compact': compact }"
    class="model-artwork"
    aria-hidden="true"
  >
    <div class="artwork-orbit artwork-orbit--outer"></div>
    <div class="artwork-orbit artwork-orbit--inner"></div>
    <div class="artwork-base artwork-base--bottom"></div>
    <div class="artwork-base artwork-base--top"></div>
    <div class="artwork-cube artwork-cube--back">
      <i></i><b></b><span></span>
    </div>
    <div class="artwork-cube artwork-cube--left">
      <i></i><b></b><span></span>
    </div>
    <div class="artwork-cube artwork-cube--right">
      <i></i><b></b><span></span>
    </div>
    <div class="artwork-cube artwork-cube--front">
      <i></i><b></b><span></span>
    </div>
    <div v-if="!compact" class="artwork-tile artwork-tile--chart">
      <IconifyIcon :icon="icons[0]" />
    </div>
    <div v-if="!compact" class="artwork-tile artwork-tile--file">
      <IconifyIcon :icon="icons[1]" />
    </div>
    <span class="artwork-point artwork-point--one"></span>
    <span class="artwork-point artwork-point--two"></span>
    <span class="artwork-point artwork-point--three"></span>
  </div>
</template>

<style scoped>
.model-artwork {
  position: relative;
  width: 300px;
  height: 180px;
  pointer-events: none;
}

.artwork-orbit {
  position: absolute;
  inset: 24px 29px;
  border: 1px solid #88b9ff;
  border-radius: 50%;
  opacity: 0.35;
  transform: rotate(-18deg);
}

.artwork-orbit--inner {
  inset: 7px 74px;
  transform: rotate(36deg);
}

.artwork-base {
  position: absolute;
  bottom: 27px;
  left: 100px;
  width: 105px;
  height: 105px;
  background: linear-gradient(135deg, #c5e5ff, #7badfc 70%, #d1e8ff);
  border: 1px solid #e1efff;
  border-radius: 8px;
  box-shadow:
    9px 9px 0 #95baf94d,
    19px 19px 28px #407bea1f;
  transform: rotate(30deg) skew(-25deg) scaleY(0.6);
}

.artwork-base--bottom {
  bottom: 10px;
  background: #d6e7fd;
  opacity: 0.7;
}

.artwork-cube {
  position: absolute;
  top: 43px;
  left: 130px;
  width: 50px;
  height: 58px;
  filter: drop-shadow(4px 7px 7px #3a7adb24);
}

.artwork-cube i,
.artwork-cube b,
.artwork-cube span {
  position: absolute;
  inset: 0;
}

.artwork-cube i {
  background: linear-gradient(145deg, #e2f5ff, #81bdff);
  clip-path: polygon(50% 0, 100% 25%, 50% 50%, 0 25%);
}

.artwork-cube b {
  background: linear-gradient(135deg, #a9d4ff, #3d76ef);
  clip-path: polygon(0 25%, 50% 50%, 50% 100%, 0 75%);
}

.artwork-cube span {
  background: linear-gradient(150deg, #73b9fa, #3865d9);
  clip-path: polygon(50% 50%, 100% 25%, 100% 75%, 50% 100%);
}

.artwork-cube--back {
  top: 25px;
}

.artwork-cube--left {
  top: 43px;
  left: 103px;
}

.artwork-cube--right {
  top: 43px;
  left: 157px;
  opacity: 0.8;
}

.artwork-cube--front {
  top: 61px;
}

.artwork-tile {
  position: absolute;
  display: grid;
  place-items: center;
  width: 49px;
  height: 54px;
  font-size: 30px;
  color: #fff;
  background: linear-gradient(130deg, #d7efff, #82b1f8 58%, #578ae9);
  border: 2px solid #b9d9ff;
  border-radius: 7px;
  box-shadow: 5px 9px 16px #5788e833;
}

.artwork-tile--chart {
  top: 31px;
  left: 17px;
  transform: rotate(-7deg) skewY(-6deg);
}

.artwork-tile--file {
  top: 20px;
  right: 12px;
  transform: rotate(8deg) skewY(6deg);
}

.artwork-point {
  position: absolute;
  width: 5px;
  height: 5px;
  background: #72acfb;
  border-radius: 50%;
  box-shadow: 0 0 0 3px #7eaefb1f;
}

.artwork-point--one {
  top: 38px;
  left: 88px;
}

.artwork-point--two {
  top: 108px;
  right: 30px;
}

.artwork-point--three {
  bottom: 26px;
  left: 52px;
}

.model-artwork--compact {
  width: 160px;
  height: 160px;
  background: radial-gradient(
    circle,
    #b7d7ff38 36%,
    transparent 37%,
    transparent 47%,
    #b7d7ff24 48%,
    #b7d7ff24 53%,
    transparent 54%
  );
}

.model-artwork--compact .artwork-base {
  left: 36px;
  width: 88px;
  height: 88px;
}

.model-artwork--compact .artwork-cube {
  left: 56px;
  transform: scale(0.8);
}

.model-artwork--compact .artwork-cube--left {
  left: 35px;
}

.model-artwork--compact .artwork-cube--right {
  left: 77px;
}

.model-artwork--compact .artwork-orbit--outer {
  inset: 15px;
  border-width: 5px;
}

.model-artwork--compact .artwork-orbit--inner {
  display: none;
}

.model-artwork--compact .artwork-point--one {
  left: 20px;
}

.model-artwork--compact .artwork-point--three {
  left: 28px;
}
</style>
