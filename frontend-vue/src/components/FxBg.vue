<template>
  <!--
    流光背景层（三个内页共用）。
    【结构写在这里而不是 JS 注入】—— 这样它随首屏一起出现，
    不会出现「底色纯白闪一下才出效果」。样式全在 styles/fx-bg.css。

    【定位提醒】.fx-bg 是 fixed + z-index:0（定位元素），会盖住 static 的内容容器。
    所以使用它的页面，内容容器必须 position:relative; z-index:1，
    否则整页空白且无报错。
  -->
  <div class="fx-bg" aria-hidden="true">
    <span class="fx-blob fx-1"></span>
    <span class="fx-blob fx-2"></span>
    <span class="fx-blob fx-3"></span>
    <span class="fx-blob fx-4"></span>
    <span class="fx-blob fx-5"></span>
    <span class="fx-sheen"></span>
    <span class="fx-grain"></span>
  </div>
</template>
