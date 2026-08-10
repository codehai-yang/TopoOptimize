const pptxgen = require("pptxgenjs");

const C = {
  navy: "1E2761", iceBlue: "CADCFC", white: "FFFFFF",
  lightBg: "F4F6FB", accent: "3B82F6", accentDark: "1D4ED8",
  gold: "F59E0B", green: "10B981", red: "EF4444",
  gray: "64748B", darkGray: "334155", lightGray: "E2E8F0", cardBg: "FFFFFF",
};

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE";
pres.author = "线束优化算法团队";
pres.title = "整车线束智能优化算法平台";

const makeShadow = () => ({ type: "outer", color: "000000", blur: 8, offset: 3, angle: 135, opacity: 0.12 });

function addFooter(slide, pageNum) {
  slide.addShape(pres.shapes.LINE, { x: 0.5, y: 7.05, w: 12.3, h: 0, line: { color: C.lightGray, width: 1 } });
  slide.addText("整车线束智能优化算法平台  |  技术产品汇报", { x: 0.5, y: 7.1, w: 8, h: 0.3, fontSize: 9, color: C.gray, fontFace: "Calibri" });
  slide.addText(String(pageNum), { x: 12.3, y: 7.1, w: 0.5, h: 0.3, fontSize: 9, color: C.gray, fontFace: "Calibri", align: "right" });
}

function addTitleBar(slide, title, subtitle) {
  slide.addShape(pres.shapes.RECTANGLE, { x: 0, y: 0, w: 13.3, h: 1.1, fill: { color: C.navy } });
  slide.addShape(pres.shapes.RECTANGLE, { x: 0, y: 1.1, w: 13.3, h: 0.06, fill: { color: C.gold } });
  slide.addText(title, { x: 0.6, y: 0.2, w: 9, h: 0.7, fontSize: 26, bold: true, color: C.white, fontFace: "Calibri", valign: "middle", margin: 0 });
  if (subtitle) slide.addText(subtitle, { x: 0.6, y: 0.78, w: 9, h: 0.3, fontSize: 12, color: C.iceBlue, fontFace: "Calibri", margin: 0 });
}

function addCard(slide, x, y, w, h, opts) {
  opts = opts || {};
  slide.addShape(pres.shapes.RECTANGLE, { x, y, w, h, fill: { color: opts.fill || C.cardBg }, shadow: makeShadow(), line: { color: C.lightGray, width: 0.5 } });
  if (opts.accentColor) slide.addShape(pres.shapes.RECTANGLE, { x, y, w: 0.08, h, fill: { color: opts.accentColor } });
}

function sectionDivider(num, title, subtitle, ovalColor) {
  const s = pres.addSlide();
  s.background = { color: C.navy };
  s.addShape(pres.shapes.OVAL, { x: 9, y: -1.5, w: 5, h: 5, fill: { color: ovalColor, transparency: 70 } });
  s.addShape(pres.shapes.RECTANGLE, { x: 1.0, y: 2.8, w: 0.12, h: 1.8, fill: { color: C.gold } });
  s.addText(num, { x: 1.3, y: 2.5, w: 2, h: 0.8, fontSize: 40, bold: true, color: C.gold, fontFace: "Calibri", margin: 0 });
  s.addText(title, { x: 1.3, y: 3.3, w: 10, h: 0.8, fontSize: 32, bold: true, color: C.white, fontFace: "Calibri", margin: 0 });
  s.addText(subtitle, { x: 1.3, y: 4.1, w: 10, h: 0.5, fontSize: 16, color: C.iceBlue, fontFace: "Calibri", italic: true, margin: 0 });
}

module.exports = { pres, C, makeShadow, addFooter, addTitleBar, addCard, sectionDivider };
