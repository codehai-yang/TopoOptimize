/**
 * 数据访问层
 * ============================================================
 * 负责从解析后的 output 数据中，按层级提取统计条目。
 * 各层级返回统一结构 [{ name, agg }]；整车级仅返回单个对象。
 * 通过 window.AppData 暴露。
 */
(function (root) {
  "use strict";

  const util = root.AppUtil;

  /**
   * 按层级返回统计条目统一列表。
   *  - system    : systemCircuitInfo，键为系统名，值含 circuitInfoIntergation
   *  - elec      : elecRelatedCircuitInfo，键为用电器名
   *  - interface : elecInterfaceRelatedCircuitInfo，按「用电器 · 接口类型」展开
   *  - project   : projectCircuitInfo，整车单对象
   * @param {string} level 层级 key
   * @param {Object} outputData 优化后方案数据
   * @returns {Array<{name:string, agg:Object}>} 统计条目列表
   */
  function getEntries(level, outputData) {
    if (!outputData) return [];
    if (level === "project") {
      return [{ name: "整车", agg: outputData.projectCircuitInfo || {} }];
    }
    if (level === "interface") {
      return flattenInterface(outputData.elecInterfaceRelatedCircuitInfo);
    }
    const sourceMap = level === "system"
      ? outputData.systemCircuitInfo
      : outputData.elecRelatedCircuitInfo;
    if (!sourceMap) return [];
    return Object.keys(sourceMap).map((key) => ({
      name: key,
      agg: (sourceMap[key] || {}).circuitInfoIntergation || {}
    }));
  }

  /**
   * 展开接口层数据。子键为接口类型（如 ZXD 的 B/S/K/O、IPD 的 HFM C 等），
   * 动态遍历所有子键，保留含 circuitInfoIntergation 的项。
   * @param {Object} map elecInterfaceRelatedCircuitInfo
   * @returns {Array<{name:string, agg:Object}>} 展开后的条目
   */
  function flattenInterface(map) {
    const result = [];
    if (!map) return result;
    Object.keys(map).forEach((device) => {
      const group = map[device];
      if (!group) return;
      Object.keys(group).forEach((type) => {
        const node = group[type];
        if (node && node.circuitInfoIntergation) {
          result.push({ name: device + " · " + type, agg: node.circuitInfoIntergation });
        }
      });
    });
    return result;
  }

  /**
   * 获取指定层级可用的数值指标字段（取所有条目的并集）。
   * @param {string} level 层级 key
   * @param {Object} outputData 优化后方案数据
   * @returns {string[]} 数值指标字段名列表
   */
  function getMetricOptions(level, outputData) {
    const metricSet = new Set();
    getEntries(level, outputData).forEach((entry) => {
      const agg = entry.agg || {};
      Object.keys(agg).forEach((key) => {
        if (util.isNumericLike(agg[key])) metricSet.add(key);
      });
    });
    return Array.from(metricSet);
  }

  root.AppData = {
    getEntries: getEntries,
    getMetricOptions: getMetricOptions
  };
})(typeof window !== "undefined" ? window : globalThis);