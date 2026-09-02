/**
 * 图表渲染层
 * ============================================================
 * 负责柱状图的 DOM 构建与布局，以及整车级指标卡片渲染。
 * 柱状图特性：
 *   - 每个柱的数值标签紧贴各自柱顶上方（y 动态计算）
 *   - 底部名称 -45° 倾斜，避免长名称互相挤压
 *   - 柱宽随条目数量自适应
 * 通过 window.AppChart 暴露。
 */
(function (root) {
  "use strict";

  const util = root.AppUtil;
  const config = root.AppConfig;

  const CHART = config.CHART;

  /**
   * 柱体颜色：按排名在蓝→绿 之间渐变。
   * @param {number} index 排名（0 起）
   * @param {number} total 总数
   * @returns {string} css 颜色字符串
   */
  function barColor(index, total) {
    const ratio = total <= 1 ? 0 : index / (total - 1);
    const hue = 210 - ratio * 120;
    return "hsl(" + hue + ", 72%, 52%)";
  }

  /**
   * 计算柱宽：随条目数自适应。
   * @param {number} count 柱条数
   * @returns {number} 单柱宽（px）
   */
  function computeBarWidth(count) {
    const target = Math.round(1200 / count) + 24;
    return Math.max(CHART.barWidthMin, Math.min(CHART.barWidthMax, target));
  }

  /**
   * 渲染柱状图。
   * @param {Array<{name:string, v:number}>} items 已按指标排序的条目
   * @param {string} metric 指标字段名
   * @param {boolean} ascending 是否升序（仅影响外部排序，此处只消费顺序）
   * @returns {Object} 统计结果 { count, skipped, max, min, sum }
   */
  function renderBars(items, metric) {
    const container = document.getElementById("chart-inner");
    const withValue = items
      .map((item) => ({ name: item.name, value: util.toNumber(item.agg ? item.agg[metric] : null) }))
      .filter((item) => !Number.isNaN(item.value));

    if (withValue.length === 0) {
      container.innerHTML =
        '<div class="empty">该层级没有任何条目包含指标「' + util.escapeHtml(metric) + "」</div>";
      return { count: 0, skipped: items.length, max: null, min: null, sum: null };
    }

    const n = withValue.length;
    const max = Math.max.apply(null, withValue.map((item) => item.value));
    const min = Math.min.apply(null, withValue.map((item) => item.value));
    const sum = withValue.reduce((acc, item) => acc + item.value, 0);

    const barWidth = computeBarWidth(n);
    const plotHeight = CHART.plotHeight;
    const topLabelZone = CHART.topLabelZone;           // 顶部预留区：容纳最高柱的数值标签
    const bottom = CHART.labelHeight + CHART.labelGap; // 名称区占位（标签区 + 柱底间距）
    const columnHeight = topLabelZone + plotHeight + bottom;
    const valueLabelHeight = 12; // 数值标签自身高度（line-height 1）

    const html = withValue.map((item, index) => {
      const height = Math.max(CHART.barMinHeight, (item.value / max) * plotHeight);
      // 柱顶相对列顶部的高度 = topLabelZone + (plotHeight - height)
      // 数值标签紧贴各自柱顶上方：柱顶 Y - valueGap - 标签自身高度
      const numberTop = topLabelZone + (plotHeight - height - CHART.valueGap - valueLabelHeight);
      const width = barWidth + "px";
      const name = util.escapeHtml(item.name);
      const valueText = util.formatValue(item.value, metric);
      const color = barColor(index, n);
      const title = name + " : " + valueText;

      return (
        '<div class="bar-column" style="width:' + width + ';height:' + columnHeight + 'px" title="' + title + '">' +
        '<div class="bar-value" style="top:' + numberTop + 'px">' + valueText + "</div>" +
        // 柱体
        '<div class="bar-rect" style="bottom:' + bottom + 'px;height:' +
        height + "px;background:" + color + '"></div>' +
        // 名称标签：底部倾斜
        '<div class="bar-name" style="bottom:' + (CHART.labelHeight - 24) + 'px">' +
        name + "</div>" +
        "</div>"
      );
    }).join("");

    container.style.width = (n * barWidth) + "px";
    container.innerHTML = html;
    return { count: n, skipped: items.length - n, max: max, min: min, sum: sum };
  }

  /**
   * 渲染整车级指标卡片总览。
   * @param {Object} project projectCircuitInfo
   */
  function renderProject(project) {
    const grid = document.getElementById("project-grid");
    const html = Object.keys(project).map((key) => {
      const raw = project[key];
      const isNumeric = !Number.isNaN(util.toNumber(raw));
      const text = isNumeric
        ? util.formatValue(raw, key)
        : util.escapeHtml(raw === null || raw === undefined ? "" : String(raw));
      return (
        '<div class="stat-card">' +
        '<div class="stat-name" title="' + util.escapeHtml(key) + '">' +
        util.escapeHtml(key) + "</div>" +
        '<div class="stat-val">' + text + "</div>" +
        "</div>"
      );
    }).join("");
    grid.innerHTML = html;
  }

  root.AppChart = {
    renderBars: renderBars,
    renderProject: renderProject,
    barColor: barColor,
    computeBarWidth: computeBarWidth
  };
})(typeof window !== "undefined" ? window : globalThis);