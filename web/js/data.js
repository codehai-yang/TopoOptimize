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
   *  - branch    : bundeleRelatedCircuitInfo，键为分支 id，值含 circuitInfoIntergation
   *  - circuit   : circuitInfo 数组，每根柱以「回路编号」作为名称，聚合字段即回路自身字段
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
    if (level === "circuit") {
      return flattenCircuit(outputData.circuitInfo);
    }
    const sourceMap = {
      system: outputData.systemCircuitInfo,
      elec: outputData.elecRelatedCircuitInfo,
      branch: outputData.bundeleRelatedCircuitInfo
    }[level];
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
   * 展开回路线数据：每条回路即为一个条目，柱体名称取「回路编号」，
   * 缺失编号时回退到「回路id」。
   * @param {Array<Object>} circuitList circuitInfo 数组
   * @returns {Array<{name:string, agg:Object}>} 展开后的条目
   */
  function flattenCircuit(circuitList) {
    const result = [];
    if (!Array.isArray(circuitList)) return result;
    circuitList.forEach((circuit) => {
      if (!circuit) return;
      const number = circuit["回路编号"];
      const fallbackId = circuit["回路id"];
      const name = (number !== null && number !== undefined && String(number) !== "")
        ? String(number)
        : (fallbackId !== null && fallbackId !== undefined ? String(fallbackId) : "");
      result.push({ name: name, id: circuit["回路id"], agg: circuit });
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

  /**
   * 聚合指标名 -> 单根回路指标名的映射。
   * 聚合对象用「XX总成本/总重量/总长度」等求和口径，单根回路对象用「XX成本/重量/长度」。
   * 当用户在聚合层级选择这些指标时，回路明细按对应单根口径排序与展示。
   * @type {Object<string,string>}
   */
  const AGG_TO_CIRCUIT_METRIC = {
    "回路导线总成本": "回路导线成本",
    "回路总长度": "回路长度",
    "回路总重量": "回路重量",
    "端子总成本": "端子成本",
    "连接器塑壳总成本": "连接器塑壳成本",
    "防水塞总成本": "防水塞成本",
    "回路两端端子总成本": "回路两端端子成本",
    "回路重量均值(克/根)": "回路重量",
    "回路成本均值(元/根)": "回路总成本"
  };

  /**
   * 构建「回路id -> 回路对象」索引，供按 circuitList 反查明细使用。
   * @param {Object} outputData 优化后方案数据
   * @returns {Map<string,Object>} 回路索引
   */
  function buildCircuitMap(outputData) {
    const map = new Map();
    const list = outputData && Array.isArray(outputData.circuitInfo) ? outputData.circuitInfo : [];
    list.forEach((circuit) => {
      if (circuit && circuit["回路id"] !== null && circuit["回路id"] !== undefined) {
        map.set(String(circuit["回路id"]), circuit);
      }
    });
    return map;
  }

  /**
   * 获取某层级下指定名称条目所包含的回路对象列表。
   *  - 非回路层：从层级源节点取 circuitList（回路id 数组），再反查 circuitInfo。
   *    注意：circuitList 是 circuitInfoIntergation 的同级字段，而非其子字段。
   *  - 回路层：条目本身即单根回路（按 id 或编号定位）。
   * @param {Object} outputData 优化后方案数据
   * @param {string} level 层级 key
   * @param {string} name 条目名称
   * @param {string} [id] 条目唯一 id（回路层为回路 id）
   * @returns {Array<Object>} 回路对象列表
   */
  function getCircuitsForEntry(outputData, level, name, id) {
    if (!outputData) return [];
    const circuitArray = Array.isArray(outputData.circuitInfo) ? outputData.circuitInfo : [];

    if (level === "circuit") {
      if (id !== null && id !== undefined) {
        const hit = circuitArray.find((c) => c && String(c["回路id"]) === String(id));
        return hit ? [hit] : [];
      }
      return circuitArray.filter((c) => c && String(c["回路编号"]) === String(name));
    }

    // 解析各层级节点，取出与 circuitInfoIntergation 同级的 circuitList
    let circuitList = null;
    if (level === "interface") {
      const map = outputData.elecInterfaceRelatedCircuitInfo || {};
      Object.keys(map).some(function (device) {
        const group = map[device] || {};
        return Object.keys(group).some(function (type) {
          if (device + " · " + type === name) {
            circuitList = group[type] && group[type].circuitList;
            return true;
          }
          return false;
        });
      });
    } else {
      const sourceMap = {
        system: outputData.systemCircuitInfo,
        elec: outputData.elecRelatedCircuitInfo,
        branch: outputData.bundeleRelatedCircuitInfo
      }[level];
      const node = sourceMap && sourceMap[name];
      circuitList = node && node.circuitList;
    }

    if (!Array.isArray(circuitList)) return [];
    const map = buildCircuitMap(outputData);
    return circuitList
      .map((circuitId) => map.get(String(circuitId)))
      .filter(Boolean);
  }

  /**
   * 解析回路明细的排序指标：优先复用当前指标，若为聚合口径则映射为单根回路口径。
   * @param {string} metric 当前选中的指标名
   * @param {Object} outputData 优化后方案数据
   * @returns {string} 可直接用于回路对象排序的字段名
   */
  function resolveCircuitMetric(metric, outputData) {
    const sample = (outputData && Array.isArray(outputData.circuitInfo) && outputData.circuitInfo[0]) || {};
    if (metric in sample) return metric;
    return AGG_TO_CIRCUIT_METRIC[metric] || "回路重量";
  }

  root.AppData = {
    getEntries: getEntries,
    getMetricOptions: getMetricOptions,
    getCircuitsForEntry: getCircuitsForEntry,
    resolveCircuitMetric: resolveCircuitMetric
  };
})(typeof window !== "undefined" ? window : globalThis);