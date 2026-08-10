const { pres, C, makeShadow, addFooter, addTitleBar, addCard, sectionDivider } = require("./lib");

// ===== 第5页：章节分隔 - 整车回路计算引擎 =====
sectionDivider("01", "整车回路计算引擎", "ProjectCircuitInfoOutput — 一切优化算法的数据底座", C.accentDark);

// ===== 第6页：整车回路计算引擎 - 产品价值 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "整车回路计算引擎", "把整车拓扑图变成可计算的成本账本");
  addCard(s, 0.5, 1.5, 6.0, 5.2, { accentColor: C.accent });
  s.addText("产品价值", { x: 0.8, y: 1.7, w: 5.4, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const values = [
    { t: "一张图算清整车成本", d: "将拓扑分支、回路、用电器、导线库打通，输出整车回路总成本、总重量、总长度" },
    { t: "支持直连接口变更", d: "识别接口直连编号，自动处理用电器位置可变场景，无需人工重新建模" },
    { t: "回路智能分类", d: "区分两点回路、多点焊点回路、固定/可变回路，分类计算提升效率" },
    { t: "干湿区差异化计价", d: "识别端点干湿状态，湿区回路计入密封成本，精准反映真实成本" },
  ];
  values.forEach((v, i) => {
    const vy = 2.3 + i * 1.05;
    s.addShape(pres.shapes.OVAL, { x: 0.85, y: vy + 0.05, w: 0.3, h: 0.3, fill: { color: C.accent } });
    s.addText(String(i + 1), { x: 0.85, y: vy + 0.05, w: 0.3, h: 0.3, fontSize: 11, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText(v.t, { x: 1.3, y: vy, w: 5.0, h: 0.35, fontSize: 13, bold: true, color: C.darkGray, fontFace: "Calibri", margin: 0 });
    s.addText(v.d, { x: 1.3, y: vy + 0.35, w: 5.0, h: 0.55, fontSize: 10.5, color: C.gray, fontFace: "Calibri", margin: 0 });
  });
  addCard(s, 6.8, 1.5, 6.0, 5.2, { accentColor: C.gold });
  s.addText("计算能力矩阵", { x: 7.1, y: 1.7, w: 5.4, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const caps = [
    { k: "拓扑建模", v: "邻接矩阵 + 全通图双模型" },
    { k: "路径搜索", v: "两点间全路径枚举 + 最短路径" },
    { k: "成本计算", v: "导线商务价 × 长度 + 接插件 + 密封" },
    { k: "重量计算", v: "导线线径 × 长度 × 密度系数" },
    { k: "长度计算", v: "路径累加 + 分支补充长度(200mm)" },
    { k: "直径估算", v: "数模直径系数 1.14 · 理论系数 1.3" },
    { k: "并发能力", v: "多线程 ThreadPool 并行计算" },
  ];
  caps.forEach((c, i) => {
    const cy = 2.35 + i * 0.6;
    s.addText(c.k, { x: 7.1, y: cy, w: 2.2, h: 0.4, fontSize: 12, bold: true, color: C.navy, fontFace: "Calibri", valign: "middle", margin: 0 });
    s.addText(c.v, { x: 9.3, y: cy, w: 3.3, h: 0.4, fontSize: 11, color: C.darkGray, fontFace: "Calibri", valign: "middle", margin: 0 });
    if (i < caps.length - 1) s.addShape(pres.shapes.LINE, { x: 7.1, y: cy + 0.45, w: 5.5, h: 0, line: { color: C.lightGray, width: 0.5 } });
  });
  addFooter(s, 6);
}

// ===== 第7页：回路计算流程 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "回路计算流程", "从 JSON 输入到成本输出 · 六步标准化流程");
  const steps = [
    { num: "1", t: "数据解析", d: "解析整车 JSON，读取端点、分支、回路、用电器、方案信息", color: C.gray },
    { num: "2", t: "拓扑建模", d: "构建邻接矩阵（含打断状态）与全通图邻接矩阵", color: C.accent },
    { num: "3", t: "回路分类", d: "区分固定回路/可变回路/焊点回路，成本<4元回路单独处理", color: C.green },
    { num: "4", t: "路径搜索", d: "枚举两点间所有路径，计算长度、打断次数、干湿区个数", color: C.gold },
    { num: "5", t: "成本核算", d: "按导线库匹配线径，计算导线成本+接插件+密封件成本", color: C.red },
    { num: "6", t: "结果输出", d: "输出回路明细 + 整车总成本/总重量/总长度", color: C.navy },
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
  addFooter(s, 7);
}

// ===== 第8页：章节分隔 - 线束拓扑优化 =====
sectionDivider("02", "线束拓扑优化算法", "HarnessBranchTopoOptimize — 分支通断的智能寻优", C.green);

// ===== 第9页：线束拓扑优化 - 产品价值与核心概念 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "线束拓扑优化算法", "在数千种分支通断组合中，自动找到成本最优解");
  addCard(s, 0.5, 1.5, 5.8, 5.2, { accentColor: C.green });
  s.addText("解决什么问题", { x: 0.8, y: 1.7, w: 5.2, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  s.addText([
    { text: "整车线束有数百条分支，每条分支有", options: { fontSize: 12, color: C.darkGray } },
    { text: " B(断)/C(通)/S(穿腔) ", options: { fontSize: 12, color: C.green, bold: true } },
    { text: "三种通断状态。不同组合直接影响回路走线长度与成本。", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
    { text: "", options: { breakLine: true, fontSize: 6 } },
    { text: "人工设计只能凭经验试错，组合空间可达", options: { fontSize: 12, color: C.darkGray } },
    { text: " 10^30+ ", options: { fontSize: 12, color: C.red, bold: true } },
    { text: "，无法穷举。", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
  ], { x: 0.8, y: 2.3, w: 5.2, h: 1.5, fontFace: "Calibri", margin: 0 });
  s.addText("产品价值", { x: 0.8, y: 3.9, w: 5.2, h: 0.4, fontSize: 14, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const vals = [
    "遗传算法全局寻优，突破人工经验上限",
    "支持组团变化、互斥约束、多选一约束",
    "AI 推理引擎可选启用，加速收敛",
    "Top20 方案推荐，保留人工决策空间",
  ];
  vals.forEach((v, i) => {
    s.addText("✓  " + v, { x: 0.8, y: 4.3 + i * 0.45, w: 5.2, h: 0.4, fontSize: 11.5, color: C.darkGray, fontFace: "Calibri", margin: 0 });
  });
  addCard(s, 6.6, 1.5, 6.2, 5.2, { accentColor: C.gold });
  s.addText("分支通断状态", { x: 6.9, y: 1.7, w: 5.6, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const states = [
    { code: "B", name: "断开 (Break)", desc: "分支断开，回路需绕行其他路径", color: C.red },
    { code: "C", name: "连通 (Connect)", desc: "分支连通，电流可直接通过", color: C.green },
    { code: "S", name: "穿腔 (Splice)", desc: "穿腔连接，涉及密封件成本", color: C.gold },
  ];
  states.forEach((st, i) => {
    const sy = 2.3 + i * 1.1;
    s.addShape(pres.shapes.RECTANGLE, { x: 6.9, y: sy, w: 1.0, h: 0.8, fill: { color: st.color } });
    s.addText(st.code, { x: 6.9, y: sy, w: 1.0, h: 0.8, fontSize: 28, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText(st.name, { x: 8.1, y: sy, w: 4.5, h: 0.35, fontSize: 13, bold: true, color: C.darkGray, fontFace: "Calibri", margin: 0 });
    s.addText(st.desc, { x: 8.1, y: sy + 0.35, w: 4.5, h: 0.4, fontSize: 11, color: C.gray, fontFace: "Calibri", margin: 0 });
  });
  s.addText("约束类型：组团一起变 · 组间互斥 · 组内只保留一个C · 闭环分支限制", { x: 6.9, y: 5.8, w: 5.6, h: 0.5, fontSize: 10.5, color: C.gray, fontFace: "Calibri", italic: true, margin: 0 });
  addFooter(s, 9);
}

// ===== 第10页：线束拓扑优化 - 遗传算法流程 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "拓扑优化 · 遗传算法流程", "初代生成 → 评估 → 交叉 → 变异 → 迭代收敛");
  const steps = [
    { num: "1", t: "分支分类", d: "识别固定分支、组团变化分支、可选BC/SC/BS/BSC分支，建立约束模型", color: C.gray },
    { num: "2", t: "初代样本生成", d: "随机生成 ≥1000 个合法方案，通过仓库去重保证多样性", color: C.accent },
    { num: "3", t: "成本评估", d: "多线程并行调用回路计算引擎，计算每个方案的成本/重量/长度", color: C.green },
    { num: "4", t: "Top筛选", d: "保留 Top1000 方案作为父本，记录最优成本", color: C.gold },
    { num: "5", t: "交叉+变异", d: "父本邻域交叉 + 随机变异，生成 ≥10000 个子代方案", color: C.red },
    { num: "6", t: "收敛判断", d: "最优解连续6代无变化则终止，否则回到步骤3继续迭代", color: C.navy },
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
  addFooter(s, 10);
}

module.exports = {};
