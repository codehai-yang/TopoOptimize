const { pres, C, makeShadow, addFooter, addTitleBar, addCard, sectionDivider } = require("./lib");

// ===== 第11页：章节分隔 - 控制器位置优化 =====
sectionDivider("03", "控制器位置优化算法", "ElecPositionVariantCalculation — 用电器位置智能推荐", C.gold);

// ===== 第12页：控制器位置优化 - 产品价值 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "控制器位置优化算法", "控制器放哪里最省钱？算法给出 Top20 推荐方案");
  addCard(s, 0.5, 1.5, 5.8, 5.2, { accentColor: C.gold });
  s.addText("解决什么问题", { x: 0.8, y: 1.7, w: 5.2, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  s.addText([
    { text: "控制器（BCM/CPM等）的位置并非固定，", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
    { text: "不同安装位置导致回路走线长度差异巨大。", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
    { text: "", options: { breakLine: true, fontSize: 6 } },
    { text: "当多个控制器位置可变时，", options: { fontSize: 12, color: C.darkGray } },
    { text: "组合空间可达 10^15+", options: { fontSize: 12, color: C.red, bold: true } },
    { text: "，人工无法评估。", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
  ], { x: 0.8, y: 2.3, w: 5.2, h: 1.5, fontFace: "Calibri", margin: 0 });
  s.addText("产品价值", { x: 0.8, y: 3.9, w: 5.2, h: 0.4, fontSize: 14, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const vals = [
    "自动识别可变位置用电器，无需人工标注",
    "智能分组：关联用电器协同优化",
    "小空间枚举 + 大空间遗传，兼顾精度与效率",
    "Top20 方案对比，支持人工最终决策",
  ];
  vals.forEach((v, i) => {
    s.addText("✓  " + v, { x: 0.8, y: 4.3 + i * 0.45, w: 5.2, h: 0.4, fontSize: 11.5, color: C.darkGray, fontFace: "Calibri", margin: 0 });
  });

  addCard(s, 6.6, 1.5, 6.2, 5.2, { accentColor: C.accent });
  s.addText("智能分组策略", { x: 6.9, y: 1.7, w: 5.6, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  s.addText("按用电器关联性自动分组，分组内协同优化", { x: 6.9, y: 2.2, w: 5.6, h: 0.4, fontSize: 11, color: C.gray, fontFace: "Calibri", italic: true, margin: 0 });
  const groups = [
    { t: "关联性判定", d: "两用电器间回路导线商务价 > 2元 则判定为强关联" },
    { t: "DFS 分组", d: "深度优先搜索将强关联用电器归入同一组" },
    { t: "独立优化", d: "组间独立计算，降低搜索空间复杂度" },
    { t: "结果合并", d: "各组 Top 方案合并，输出整车推荐方案" },
  ];
  groups.forEach((g, i) => {
    const gy = 2.8 + i * 0.85;
    s.addShape(pres.shapes.RECTANGLE, { x: 6.9, y: gy, w: 0.08, h: 0.7, fill: { color: C.accent } });
    s.addText(g.t, { x: 7.15, y: gy, w: 5.3, h: 0.3, fontSize: 12, bold: true, color: C.darkGray, fontFace: "Calibri", margin: 0 });
    s.addText(g.d, { x: 7.15, y: gy + 0.3, w: 5.3, h: 0.4, fontSize: 10.5, color: C.gray, fontFace: "Calibri", margin: 0 });
  });
  addFooter(s, 12);
}

// ===== 第13页：控制器位置优化 - 算法策略 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "位置优化 · 自适应算法策略", "根据组合空间大小，自动选择最优计算策略");
  const strategies = [
    {
      title: "单用电器优化", space: "N 个位置点", strategy: "全枚举",
      desc: "逐一计算用电器在每个可变位置点的成本，直接选出 Top 方案", color: C.green
    },
    {
      title: "小组多电器优化", space: "< 10,000 种组合", strategy: "全排列枚举",
      desc: "递归生成所有位置排列，多线程并行计算，选 Top20", color: C.accent
    },
    {
      title: "大组多电器优化", space: "≥ 10,000 种组合", strategy: "遗传算法",
      desc: "初代10000样本 → 交叉变异 → 迭代收敛，Top20推荐", color: C.gold
    },
  ];
  strategies.forEach((st, i) => {
    const y = 1.5 + i * 1.7;
    addCard(s, 0.5, y, 12.3, 1.5, { accentColor: st.color });
    s.addShape(pres.shapes.RECTANGLE, { x: 0.5, y, w: 2.5, h: 1.5, fill: { color: st.color } });
    s.addText(st.title, { x: 0.5, y, w: 2.5, h: 1.5, fontSize: 15, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText("组合空间", { x: 3.2, y: y + 0.15, w: 3.0, h: 0.3, fontSize: 10, color: C.gray, fontFace: "Calibri", margin: 0 });
    s.addText(st.space, { x: 3.2, y: y + 0.45, w: 3.0, h: 0.4, fontSize: 13, bold: true, color: C.darkGray, fontFace: "Calibri", margin: 0 });
    s.addText("计算策略", { x: 6.5, y: y + 0.15, w: 2.5, h: 0.3, fontSize: 10, color: C.gray, fontFace: "Calibri", margin: 0 });
    s.addText(st.strategy, { x: 6.5, y: y + 0.45, w: 2.5, h: 0.4, fontSize: 13, bold: true, color: st.color, fontFace: "Calibri", margin: 0 });
    s.addText(st.desc, { x: 9.3, y: y + 0.2, w: 3.3, h: 1.1, fontSize: 10.5, color: C.darkGray, fontFace: "Calibri", valign: "middle", margin: 0 });
  });
  addCard(s, 0.5, 6.4, 12.3, 0.5, { accentColor: C.navy });
  s.addText("核心优势：自适应策略保证小空间精度 + 大空间效率，避免组合爆炸", { x: 0.8, y: 6.45, w: 12, h: 0.4, fontSize: 12, bold: true, color: C.navy, fontFace: "Calibri", valign: "middle", margin: 0 });
  addFooter(s, 13);
}

// ===== 第14页：章节分隔 - 配电驱动优化 =====
sectionDivider("04", "配电驱动优化算法", "PowerDistributionDriveOptimization — 供电路径全局寻优", C.red);

// ===== 第15页：配电驱动优化 - 产品价值 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "配电驱动优化算法", "用电器该接哪个控制器？算法给出最优连接方案");
  addCard(s, 0.5, 1.5, 5.8, 5.2, { accentColor: C.red });
  s.addText("解决什么问题", { x: 0.8, y: 1.7, w: 5.2, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  s.addText([
    { text: "同一个用电器可能连接到多个控制器/电源点，", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
    { text: "不同连接关系导致回路成本差异显著。", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
    { text: "", options: { breakLine: true, fontSize: 6 } },
    { text: "需在满足", options: { fontSize: 12, color: C.darkGray } },
    { text: "资源数量限制、组团/互斥约束", options: { fontSize: 12, color: C.red, bold: true } },
    { text: "前提下，找到成本最优的连接方案。", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
  ], { x: 0.8, y: 2.3, w: 5.2, h: 1.5, fontFace: "Calibri", margin: 0 });
  s.addText("产品价值", { x: 0.8, y: 3.9, w: 5.2, h: 0.4, fontSize: 14, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const vals = [
    "支持配电回路、驱动回路、主供电回路三类优化",
    "8类资源数量限制（大小电流/硬线/高速线缆）",
    "组团连接 + 互斥连接约束建模",
    "枚举与遗传自适应切换，Top20 方案推荐",
  ];
  vals.forEach((v, i) => {
    s.addText("✓  " + v, { x: 0.8, y: 4.3 + i * 0.45, w: 5.2, h: 0.4, fontSize: 11.5, color: C.darkGray, fontFace: "Calibri", margin: 0 });
  });

  addCard(s, 6.6, 1.5, 6.2, 5.2, { accentColor: C.gold });
  s.addText("优化类型矩阵", { x: 6.9, y: 1.7, w: 5.6, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const types = [
    { code: "3", name: "配电回路优化", desc: "仅优化配电回路 + 主供电回路", color: C.accent },
    { code: "4", name: "驱动回路优化", desc: "仅优化驱动回路连接关系", color: C.green },
    { code: "5", name: "全量优化", desc: "配电 + 主供电 + 驱动（含硬线/高速线缆/接地）", color: C.gold },
  ];
  types.forEach((t, i) => {
    const ty = 2.3 + i * 1.1;
    s.addShape(pres.shapes.RECTANGLE, { x: 6.9, y: ty, w: 1.0, h: 0.8, fill: { color: t.color } });
    s.addText(t.code, { x: 6.9, y: ty, w: 1.0, h: 0.8, fontSize: 28, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText(t.name, { x: 8.1, y: ty, w: 4.5, h: 0.35, fontSize: 13, bold: true, color: C.darkGray, fontFace: "Calibri", margin: 0 });
    s.addText(t.desc, { x: 8.1, y: ty + 0.35, w: 4.5, h: 0.4, fontSize: 11, color: C.gray, fontFace: "Calibri", margin: 0 });
  });
  s.addText("约束：组团一起变 · 互斥连接 · 资源数量上限 · 直连接口", { x: 6.9, y: 5.8, w: 5.6, h: 0.5, fontSize: 10.5, color: C.gray, fontFace: "Calibri", italic: true, margin: 0 });
  addFooter(s, 15);
}

// ===== 第16页：配电驱动优化 - 算法流程 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "配电驱动优化 · 算法流程", "枚举优先 · 遗传兜底 · 自适应切换");
  const steps = [
    { num: "1", t: "回路分类", d: "按优化类型(3/4/5)筛选配电回路/驱动回路，识别可变连接关系", color: C.gray },
    { num: "2", t: "约束建模", d: "解析组团连接、互斥连接、8类资源数量限制、直连接口分组", color: C.accent },
    { num: "3", t: "方案生成", d: "生成所有合法连接方案，仓库去重（ConcurrentHashMap 原子操作）", color: C.green },
    { num: "4", t: "策略选择", d: "方案数 ≤ 100 走枚举；> 100 走遗传算法（初代1000样本）", color: C.gold },
    { num: "5", t: "成本评估", d: "多线程并行调用回路计算引擎，计算每个方案成本/重量/长度", color: C.red },
    { num: "6", t: "迭代收敛", d: "交叉概率0.7，连续1代无变化或10代空代则终止，输出Top20", color: C.navy },
  ];
  const startY = 1.6, stepH = 0.85;
  steps.forEach((st, i) => {
    const y = startY + i * stepH;
    s.addShape(pres.shapes.OVAL, { x: 0.6, y: y + 0.1, w: 0.55, h: 0.55, fill: { color: st.color } });
    s.addText(st.num, { x: 0.6, y: y + 0.1, w: 0.55, h: 0.55, fontSize: 18, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addShape(pres.shapes.RECTANGLE, { x: 1.4, y, w: 11.3, h: stepH - 0.1, fill: { color: C.white }, shadow: makeShadow(), line: { color: C.lightGray, width: 0.5 } });
    s.addShape(pres.shapes.RECTANGLE, { x: 1.4, y, w: 0.06, h: stepH - 0.1, fill: { color: st.color } });
    s.addText(st.t, { x: 1.65, y, w: 3.0, h: stepH - 0.1, fontSize: 14, bold: true, color: C.darkGray, fontFace: "Calibri", valign: "middle", margin: 0 });
    s.addText(st.d, { x: 4.7, y, w: 7.8, h: stepH - 0.1, fontSize: 11, color: C.gray, fontFace: "Calibri", valign: "middle", margin: 0 });
  });
  addFooter(s, 16);
}

module.exports = {};
