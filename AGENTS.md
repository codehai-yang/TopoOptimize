# Repository Guidelines

## 项目结构与模块划分
本仓库是基于 Maven 的 Java 17 项目，主要用于线束拓扑优化与电器位置/配电驱动相关计算。核心代码位于 `src/main/java/HarnessPackOpti`，按职责分为：

- `Algorithm/`、`CircuitInfoCalculate/`、`GraphGenerate/`：核心算法、路径计算、图结构处理
- `InfoRead/`、`ProjectInfoOutPut/`、`ErrorOutput/`：输入读取、结果输出、错误信息生成
- `Optimize/topo/`、`Optimize/elec/`：拓扑优化与电器优化入口
- `utils/`：线程池、归一化、推理引擎等公共工具

资源文件、样例数据、模型文件与配置位于 `src/main/resources`。当前程序入口为 `src/main/java/HarnessPackOpti/main.java`。

## 构建、测试与开发命令
- `mvn clean compile`：清理并编译全部源码。
- `mvn test`：执行 Maven 测试流程。当前仓库暂无标准 `src/test/java` 测试集，这个命令目前主要用于校验工程是否能正常进入测试阶段。
- `mvn package`：打包生成构建产物。
- `mvn exec:java -Dexec.mainClass=HarnessPackOpti.main`：在本地已配置 Exec 插件时运行主入口。

手动调试时，优先使用 `src/main/resources` 下的样例文件，不要在新增代码中继续写死本机绝对路径。

## 编码风格与命名规范
统一使用 UTF-8 编码和 4 空格缩进。类名使用大驼峰，如 `ReadTopologyInfo`；方法名和变量名使用小驼峰，如 `findShortestPath`；常量使用 `UPPER_SNAKE_CASE`。

尽量保持 `main.java` 只做流程编排，不要堆积业务逻辑。新增逻辑应放入现有职责明确的包中，并沿用 `HarnessPackOpti.*` 的包结构。

## 测试规范
新增可重复执行的测试时，放在 `src/test/java`，目录结构尽量与生产代码保持一致。测试类建议使用 `*Test` 后缀，例如 `FindShortestPathTest`。

涉及 Excel、TXT、模型文件的场景时，优先准备最小化测试数据，避免复制大体积生产资源。提交前至少运行 `mvn clean compile`，并补充与改动模块对应的手工验证步骤。

## 提交与合并请求规范
现有提交信息以简短中文为主，如 `代码优化`、`资源限制添加`、`创建虚拟数据`。建议继续保持“一次提交只描述一个明确改动”，标题直接说明改了什么。

提交合并请求时应包含：

- 改动目的与行为变化说明
- 涉及的模块或资源文件路径
- 已执行的验证命令或手工验证步骤
- 若修改了报表、Excel 输出或优化结果，附示例输出或截图

## 配置与数据注意事项
`src/main/resources` 中包含表格、文本样例、配置文件和模型二进制文件，这些资源应按生产资产对待。除非改动目的明确且已在说明中写清，否则不要随意重命名、覆盖或重复提交大文件。
