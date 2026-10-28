# dsh-pet 桌宠音效补丁

给 `dsh-pet` 系列桌宠（同引擎，含 `dsh-pet-nhd` 纳西妲）补一组由 WebAudio **现场合成**的音效：
不需要音频文件、不联网、不改插件配置格式。

## 换角色（bundle 切换）

DSH 桌面 profile 的启用列表在 `C:\Users\yang\.dsh\profiles\desktop\package.json` 的
`dsh.profile.bundles`。同一时间只启用一只宠物插件：

```powershell
# 切到纳西妲
node set-profile-bundles.mjs "C:\Users\yang\.dsh\profiles\desktop" dsh-pet dsh-pet-nhd

# 切回蓝毛女仆
node set-profile-bundles.mjs "C:\Users\yang\.dsh\profiles\desktop" dsh-pet-nhd dsh-pet
```

改完**重启 DSH 桌面应用**生效。两个包可以同时装着、只启用一个；
音效/拖拽补丁是打在**包内文件**上的，所以切换后要给新包再打一次：

```powershell
node apply-pet-sfx.mjs --pet-root "C:\Users\yang\.dsh\profiles\desktop\node_modules\dsh-pet-nhd"
```

> 脚本默认 `--pet-root` 是 `dsh-pet`；装纳西妲后记得显式传路径。

| 触发 | 声音 | 合成方式 |
| --- | --- | --- |
| 点击宠物 | 「叮」 | 三角波 880 → 1318.5 Hz，0.12 s |
| 抓起拖拽 | 「啵」 | 正弦 520 → 300 Hz，0.09 s |
| 松手甩出 | 风声 | 带通噪声扫频 700 → 2200 Hz，力度越大越响 |
| 落地 | 闷响 | 低频正弦 150 → 70 Hz + 触地噪声，冲击越大越沉 |
| 工作状态·完成 | 上行两音 | E5 659.3 Hz → A5 880 Hz |
| 工作状态·出错 | 下行两音 | A4 440 Hz → D4 293.7 Hz |

同一类音效有最短间隔节流（甩出 150 ms、落地 140 ms、状态 800 ms），避免反弹连击时糊成噪音。

## 为什么需要补丁

`dsh-pet` 本身没有音频能力：

- `assets/` 下没有任何音频文件，动画 webm 也没有音轨；
- 两套外壳都对 `<video>` 设了 `muted = true`（`src/client/pet.ts`、`runtime/electron-helper/sprite.js`）；
- 配置里没有声音开关。

## 用法

```powershell
# 打补丁（默认目录 %USERPROFILE%\.dsh\profiles\desktop\node_modules\dsh-pet）
node apply-pet-sfx.mjs

# 指定安装目录
node apply-pet-sfx.mjs --pet-root "C:\path\to\node_modules\dsh-pet"

# 只看会改什么，不写文件
node apply-pet-sfx.mjs --dry-run

# 还原成原版
node apply-pet-sfx.mjs --restore
```

脚本幂等：文件里已有 `dsh-pet-sfx` 标记就跳过。首次修改前把原文件备份到
`<pet-root>\.click-sound-backup\`，`--restore` 用它还原。

## 改了什么

| 文件 | 作用 |
| --- | --- |
| `lib/client.js` | 网页端实际加载的构建产物 |
| `src/client/pet.ts` | 网页端源码，保证以后重新构建仍有效 |
| `runtime/electron-helper/sprite.js` | 桌面宠物窗口（Electron）的渲染端 |
| `runtime/electron-helper/events.js` | 桌面端工作状态联动（完成 / 出错） |

插入点：

- 音效工具函数（`petSfx` / `petSfxTone` / 节流 / 噪声缓冲）与六个 `playPet*Sound`，插在模块顶层；
- 点击 → `onClick` / `handleClick` 的守卫之后；
- 抓起 → 拖拽阈值判定成立（`d.dragging = true`）处；
- 甩出 → `startThrow` 函数入口（自动覆盖甩抛、弹弓、接口抛掷、宠物互撞）；
- 落地 → 物理循环里「空中 → 地面」的转换帧（与 Q 弹同一处）；
- 工作状态 → 终态（`success` / `error`）且状态发生变化时。

## 拖拽手感（`dsh-pet-sfx:drag-loop` / `:wobble` / `:tilt`）

`assets/webm/被鼠标拖拽悬空反馈.webm` 是一段 10 秒、动作幅度很小的「被拎着」动画，
原代码用 `playOnce` 播一次——播完 `handleEnded` 在拖拽中直接 return，宠物就定格在最后一帧，
看起来像“拖拽时完全没反应”。补丁做了三件事：

- `drag-loop`：拖拽成立时改成 `switchTo(name, false)`（循环播放），长按拖动也不会定格；
- `wobble`（仅桌面端）：**以你抓住的那一点为支点**做阻尼摆动。抓住头发就绕头发转、抓住衣角就绕衣角转；
  机身是弹簧-阻尼系统（刚度 90、阻尼 7，欠阻尼），甩出去会过冲、停手后回摆两下再停；
- `tilt`：把横向拖动速度换算成摆动目标角（`vx * 0.02`，上下限 ±12°），交点给 `wobble` 的 rAF 积分。

效果：拖动时宠物像被拎着走，身体朝拖动方向甩出去、松手回摆，而不是整体刚性旋转。
窗口位置与命中区逻辑完全没改，只动舞台的 `transform` 和 `transform-origin`。

网页端（`lib/client.js` / `src/client/pet.ts`）只同步了循环播放；摆动是桌面端专属
（网页浮层里舞台 transform 另有用途，要加需单独处理）。

调参位置（`runtime/electron-helper/sprite.js` 的 `startDragWobble`）：

| 想要 | 改哪里 |
| --- | --- |
| 甩得更狠 | `vx * 0.02` 调大（0.03）、`±12` 上限调大 |
| 回摆更多 | 阻尼 `7` 调小（5）、刚度 `90` 调小 |
| 更容易停 | 阻尼 `7` 调大（12） |
| 固定从头顶吊着 | `transformOrigin` 改成 `'50% 0'` |


## 生效

- 桌面宠物：右键宠物 →「重载配置」，或重启 DSH 桌面应用（也可 `POST /dsh-pet-7340/reload`）；
- 网页里的宠物：刷新页面。

## 调音

都在各文件的 `playPet*Sound` 里：

- 音量：`petSfxTone(...)` 的最后一个数值参数（如点击是 `0.09`）；
- 音高：`from` / `to` 两个数值；
- 时长：`dur`（秒）；
- 音色：`osc.type`（`sine` 柔、`triangle` 清亮、`square` 电子）。

## 注意

`dsh-pet` 升级或重装会覆盖包内文件、音效随之消失，升级后再跑一次本脚本即可。
`--restore` 会把这四个文件还原为打补丁前的原版。
