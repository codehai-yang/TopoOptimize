require("./slides_01_04");
require("./slides_05_10");
require("./slides_11_16");
require("./slides_17_22");

const { pres } = require("./lib");

pres.writeFile({ fileName: "整车线束智能优化算法平台_技术汇报.pptx" })
  .then(fileName => console.log("PPT已生成: " + fileName))
  .catch(err => console.error("生成失败:", err));
