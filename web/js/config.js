/**
 * 全局配置与常量
 * ============================================================
 * 集中管理层级定义、默认指标、柱状图布局参数等。
 * 通过 window.AppConfig 暴露，供其它模块读取。
 */
(function (root) {
  "use strict";

  /**
   * 层级定义：LEVELS[i] 对应界面层级按钮的顺序。
   * @type {Array<{key:string, label:string, defaultMetric:string}>}
   */
  const LEVELS = [
    { key: "system", label: "系统级", defaultMetric: "回路总重量" },
    { key: "elec", label: "用电器级", defaultMetric: "回路总重量" },
    { key: "interface", label: "接口级", defaultMetric: "回路总重量" },
    { key: "branch", label: "分支级", defaultMetric: "回路总重量" },
    { key: "circuit", label: "回路级", defaultMetric: "回路重量" },
    { key: "project", label: "整车级", defaultMetric: "回路总重量" }
  ];

  /**
   * 柱状图布局参数
   * @type {Object}
   */
  const CHART = {
    /** 柱体最小/最大宽度（px），随条目数自适应 */
    barWidthMin: 60,
    barWidthMax: 130,
    /** 柱图内容总高度预算（px），不含数值与名称标签 */
    plotHeight: 400,
    /** 顶部数值区高度（px）：最高柱的数值标签在此区内显示，防止柱顶数值越界 */
    topLabelZone: 20,
    /** 数值标签距柱顶的垂直间距（px） */
    valueGap: 4,
    /** 名称标签高度（px），与底部水平名称标签的实际高度一致 */
    labelHeight: 24,
    /** 名称标签到柱体底部的间距（px） */
    labelGap: 10,
    /** 柱体最小可见高度（px），保证 0 值也可见 */
    barMinHeight: 2,
    /** 柱体左右留白比例（相对柱宽），可视化宽度为剩余的（16%x2） */
    barSideGapRatio: 0.15
  };

  /**
   * 指标下拉默认值的兜底路径（ref 用于补选项标签）。
   * @type {string}
   */
  const DEFAULT_METRIC = "回路总重量";

  root.AppConfig = {
    LEVELS: LEVELS,
    CHART: CHART,
    DEFAULT_METRIC: DEFAULT_METRIC
  };
})(typeof window !== "undefined" ? window : globalThis);