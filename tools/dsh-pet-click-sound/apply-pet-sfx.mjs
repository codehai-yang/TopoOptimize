#!/usr/bin/env node
/**
 * dsh-pet 桌宠音效补丁（幂等、可回滚）
 *
 * 背景：dsh-pet@0.3.8 本身没有任何音频能力——素材里全是无声 webm，两套外壳都对
 * <video> 设了 `muted = true`，配置里也没有声音开关。这里补一组由 WebAudio 现场
 * 合成的音效：不依赖音频文件、不联网、不改配置格式。
 *
 *   点击宠物        「叮」      三角波 880→1318.5 Hz
 *   抓起拖拽        「啵」      正弦 520→300 Hz
 *   松手甩出        风声        带通噪声扫频，力度越大越响
 *   落地            闷响        低频正弦 + 触地噪声，冲击越大越沉
 *   工作状态终态    完成上行两音 / 出错下行两音
 *
 * 改动的四个文件（都在 dsh-pet 包内）：
 *   src/client/pet.ts                    浏览器端源码（将来重新构建时保留这些音效）
 *   lib/client.js                        浏览器端构建产物（DSH 网页里实际加载的就是它）
 *   runtime/electron-helper/sprite.js    桌面宠物窗口的渲染端（Electron 每只宠物一个小窗）
 *   runtime/electron-helper/events.js    桌面端事件联动（工作状态 / 余额 / 碎碎念）
 *
 * 用法：
 *   node apply-pet-sfx.mjs [--pet-root <dsh-pet 目录>] [--restore] [--dry-run]
 *   默认 --pet-root = %USERPROFILE%\.dsh\profiles\desktop\node_modules\dsh-pet
 *
 * 说明：
 *   - 幂等：文件里已经出现 dsh-pet-sfx 标记就跳过，可以反复执行；
 *   - 首次修改前把原文件备份到 <pet-root>\.click-sound-backup\，--restore 原样还原；
 *   - 插件升级 / 重装会覆盖包内文件，届时再跑一次本脚本即可恢复音效。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';

const MARK = 'dsh-pet-sfx';

const KIT_JS = `/** ${MARK}:kit 桌宠音效包（点击「叮」/ 抓取「啵」/ 甩出风声 / 落地闷响 / 工作状态提示）。
 *  全部由 WebAudio 现场合成：不依赖音频文件、不联网、不改配置格式。
 *  上下文挂在 window 上复用（浏览器同时存在的 AudioContext 有上限，每次新建会在几次之后静音）；
 *  所有异常一律吞掉——音效失败绝不影响动画、拖拽与交互。 */
function petSfx() {
  try {
    const w = window;
    const AC = w.AudioContext || w.webkitAudioContext;
    if (!AC) return null;
    let ctx = w.__dshPetSfxCtx;
    if (!ctx || ctx.state === 'closed') ctx = w.__dshPetSfxCtx = new AC();
    if (ctx.state === 'suspended') void ctx.resume();
    return ctx;
  } catch {
    return null;
  }
}

/** 同一类音效的最短间隔（ms）：反弹连击、碰撞广播都可能瞬间连发，不节流会糊成噪音。 */
function petSfxGate(key, minMs) {
  const now = performance.now();
  const gates = (window.__dshPetSfxGate = window.__dshPetSfxGate || {});
  if (now - (gates[key] || 0) < minMs) return false;
  gates[key] = now;
  return true;
}

function petSfxNoise(ctx) {
  let buf = ctx.__dshPetNoise;
  if (!buf) {
    buf = ctx.__dshPetNoise = ctx.createBuffer(1, Math.floor(ctx.sampleRate * 0.5), ctx.sampleRate);
    const data = buf.getChannelData(0);
    for (let i = 0; i < data.length; i += 1) data[i] = Math.random() * 2 - 1;
  }
  return buf;
}

function petSfxTone(ctx, type, from, to, dur, gain, at) {
  const t0 = ctx.currentTime + (at || 0);
  const osc = ctx.createOscillator();
  const g = ctx.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(from, t0);
  if (to !== from) osc.frequency.exponentialRampToValueAtTime(to, t0 + dur);
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(gain, t0 + 0.008);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  osc.connect(g);
  g.connect(ctx.destination);
  osc.start(t0);
  osc.stop(t0 + dur + 0.02);
}

/** 点击：「叮」（三角波上滑，短促清亮） */
function playPetClickSound() {
  const ctx = petSfx();
  if (!ctx) return;
  petSfxTone(ctx, 'triangle', 880, 1318.5, 0.12, 0.09, 0);
}

/** 抓起：一声很轻的「啵」 */
function playPetGrabSound() {
  const ctx = petSfx();
  if (!ctx) return;
  petSfxTone(ctx, 'sine', 520, 300, 0.09, 0.06, 0);
}

/** 甩出：带通噪声扫频当风声，力度越大越响（speed 单位 px/s） */
function playPetThrowSound(speed) {
  const ctx = petSfx();
  if (!ctx) return;
  if (!petSfxGate('throw', 150)) return;
  const power = Math.max(0.35, Math.min(1, Math.abs(speed) / 1800));
  const t0 = ctx.currentTime;
  const src = ctx.createBufferSource();
  src.buffer = petSfxNoise(ctx);
  const bp = ctx.createBiquadFilter();
  bp.type = 'bandpass';
  bp.Q.value = 1.1;
  bp.frequency.setValueAtTime(700, t0);
  bp.frequency.exponentialRampToValueAtTime(2200, t0 + 0.16);
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(0.1 * power, t0 + 0.02);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.2);
  src.connect(bp);
  bp.connect(g);
  g.connect(ctx.destination);
  src.start(t0);
  src.stop(t0 + 0.22);
}

/** 落地：低频闷响 + 一点触地噪声，冲击越大越沉（fallingVy 为落地瞬间竖直速度，px/s） */
function playPetImpactSound(fallingVy) {
  const ctx = petSfx();
  if (!ctx) return;
  if (!petSfxGate('impact', 140)) return;
  const strength = Math.max(0.25, Math.min(1, Math.abs(fallingVy) / 1600));
  petSfxTone(ctx, 'sine', 150, 70, 0.12, 0.04 + 0.08 * strength, 0);
  const t0 = ctx.currentTime;
  const src = ctx.createBufferSource();
  src.buffer = petSfxNoise(ctx);
  const lp = ctx.createBiquadFilter();
  lp.type = 'lowpass';
  lp.frequency.value = 600;
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(0.05 * strength, t0 + 0.005);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.08);
  src.connect(lp);
  lp.connect(g);
  g.connect(ctx.destination);
  src.start(t0);
  src.stop(t0 + 0.1);
}

/** 工作状态终态：成功上行两音，出错下行两音（比纯动画更容易注意到，但不刺耳） */
function playPetStatusSound(ok) {
  const ctx = petSfx();
  if (!ctx) return;
  if (!petSfxGate('status', 800)) return;
  if (ok) {
    petSfxTone(ctx, 'triangle', 659.3, 659.3, 0.1, 0.07, 0);
    petSfxTone(ctx, 'triangle', 880, 880, 0.16, 0.07, 0.1);
  } else {
    petSfxTone(ctx, 'triangle', 440, 440, 0.14, 0.07, 0);
    petSfxTone(ctx, 'triangle', 293.7, 293.7, 0.2, 0.07, 0.14);
  }
}`;

const KIT_TS = `/** ${MARK}:kit 桌宠音效包（点击「叮」/ 抓取「啵」/ 甩出风声 / 落地闷响 / 工作状态提示）。
 *  全部由 WebAudio 现场合成：不依赖音频文件、不联网、不改配置格式。
 *  上下文挂在 window 上复用（浏览器同时存在的 AudioContext 有上限，每次新建会在几次之后静音）；
 *  所有异常一律吞掉——音效失败绝不影响动画、拖拽与交互。 */
function petSfx(): AudioContext | null {
  try {
    const w = window as unknown as {
      AudioContext?: typeof AudioContext;
      webkitAudioContext?: typeof AudioContext;
      __dshPetSfxCtx?: AudioContext;
    };
    const AC = w.AudioContext ?? w.webkitAudioContext;
    if (!AC) return null;
    let ctx = w.__dshPetSfxCtx;
    if (!ctx || ctx.state === 'closed') ctx = w.__dshPetSfxCtx = new AC();
    if (ctx.state === 'suspended') void ctx.resume();
    return ctx;
  } catch {
    return null;
  }
}

/** 同一类音效的最短间隔（ms）：反弹连击、碰撞广播都可能瞬间连发，不节流会糊成噪音。 */
function petSfxGate(key: string, minMs: number): boolean {
  const w = window as unknown as { __dshPetSfxGate?: Record<string, number> };
  const gates = (w.__dshPetSfxGate = w.__dshPetSfxGate ?? {});
  const now = performance.now();
  if (now - (gates[key] ?? 0) < minMs) return false;
  gates[key] = now;
  return true;
}

function petSfxNoise(ctx: AudioContext): AudioBuffer {
  const w = window as unknown as { __dshPetNoise?: AudioBuffer };
  let buf = w.__dshPetNoise;
  if (!buf) {
    buf = w.__dshPetNoise = ctx.createBuffer(1, Math.floor(ctx.sampleRate * 0.5), ctx.sampleRate);
    const data = buf.getChannelData(0);
    for (let i = 0; i < data.length; i += 1) data[i] = Math.random() * 2 - 1;
  }
  return buf;
}

function petSfxTone(
  ctx: AudioContext,
  type: OscillatorType,
  from: number,
  to: number,
  dur: number,
  gain: number,
  at = 0,
): void {
  const t0 = ctx.currentTime + at;
  const osc = ctx.createOscillator();
  const g = ctx.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(from, t0);
  if (to !== from) osc.frequency.exponentialRampToValueAtTime(to, t0 + dur);
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(gain, t0 + 0.008);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  osc.connect(g);
  g.connect(ctx.destination);
  osc.start(t0);
  osc.stop(t0 + dur + 0.02);
}

/** 点击：「叮」（三角波上滑，短促清亮） */
function playPetClickSound(): void {
  const ctx = petSfx();
  if (!ctx) return;
  petSfxTone(ctx, 'triangle', 880, 1318.5, 0.12, 0.09);
}

/** 抓起：一声很轻的「啵」 */
function playPetGrabSound(): void {
  const ctx = petSfx();
  if (!ctx) return;
  petSfxTone(ctx, 'sine', 520, 300, 0.09, 0.06);
}

/** 甩出：带通噪声扫频当风声，力度越大越响（speed 单位 px/s） */
function playPetThrowSound(speed: number): void {
  const ctx = petSfx();
  if (!ctx) return;
  if (!petSfxGate('throw', 150)) return;
  const power = Math.max(0.35, Math.min(1, Math.abs(speed) / 1800));
  const t0 = ctx.currentTime;
  const src = ctx.createBufferSource();
  src.buffer = petSfxNoise(ctx);
  const bp = ctx.createBiquadFilter();
  bp.type = 'bandpass';
  bp.Q.value = 1.1;
  bp.frequency.setValueAtTime(700, t0);
  bp.frequency.exponentialRampToValueAtTime(2200, t0 + 0.16);
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(0.1 * power, t0 + 0.02);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.2);
  src.connect(bp);
  bp.connect(g);
  g.connect(ctx.destination);
  src.start(t0);
  src.stop(t0 + 0.22);
}

/** 落地：低频闷响 + 一点触地噪声，冲击越大越沉（fallingVy 为落地瞬间竖直速度，px/s） */
function playPetImpactSound(fallingVy: number): void {
  const ctx = petSfx();
  if (!ctx) return;
  if (!petSfxGate('impact', 140)) return;
  const strength = Math.max(0.25, Math.min(1, Math.abs(fallingVy) / 1600));
  petSfxTone(ctx, 'sine', 150, 70, 0.12, 0.04 + 0.08 * strength);
  const t0 = ctx.currentTime;
  const src = ctx.createBufferSource();
  src.buffer = petSfxNoise(ctx);
  const lp = ctx.createBiquadFilter();
  lp.type = 'lowpass';
  lp.frequency.value = 600;
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(0.05 * strength, t0 + 0.005);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.08);
  src.connect(lp);
  lp.connect(g);
  g.connect(ctx.destination);
  src.start(t0);
  src.stop(t0 + 0.1);
}

/** 工作状态终态：成功上行两音，出错下行两音（比纯动画更容易注意到，但不刺耳） */
function playPetStatusSound(ok: boolean): void {
  const ctx = petSfx();
  if (!ctx) return;
  if (!petSfxGate('status', 800)) return;
  if (ok) {
    petSfxTone(ctx, 'triangle', 659.3, 659.3, 0.1, 0.07, 0);
    petSfxTone(ctx, 'triangle', 880, 880, 0.16, 0.07, 0.1);
  } else {
    petSfxTone(ctx, 'triangle', 440, 440, 0.14, 0.07, 0);
    petSfxTone(ctx, 'triangle', 293.7, 293.7, 0.2, 0.07, 0.14);
  }
}`;

const call = (id, code) => `${code} // ${MARK}:${id}`;

/**
 * 每个文件的插入点。
 *   before: 插在该行之前（多行代码块）
 *   after : 插在该行之后（单行调用）
 *   scopeAfter: 从这个锚点之后开始找（同一文件里守卫语句可能重复出现）
 */
const EDITS = {
  'runtime/electron-helper/sprite.js': [
    { id: 'kit', before: '// ---------- 单只宠物', text: KIT_JS },
    {
      id: 'click',
      scopeAfter: 'onClick(e) {',
      after: 'if (d.active || d.dragging || this.justDragged) return;',
      text: call('click', 'playPetClickSound();'),
    },
    {
      id: 'wobble',
      before: 'onPointerMove(e) {',
      text: [
        '/** dsh-pet-sfx:wobble —— 拖拽摆动：以**抓住的那一点**为支点做阻尼摆动，',
        ' *  像被拎着头发/衣角带走：加速时甩出去、停手后回摆几下再停。纯渲染端效果，不改窗口位置。 */',
        'startDragWobble(e) {',
        '  const rect = this.stage.getBoundingClientRect();',
        '  const px = rect.width ? ((e.clientX - rect.left) / rect.width) * 100 : 50;',
        '  const py = rect.height ? ((e.clientY - rect.top) / rect.height) * 100 : 0;',
        '  this.dragPivot = { x: Math.max(0, Math.min(100, px)), y: Math.max(0, Math.min(100, py)) };',
        "  this.stage.style.transformOrigin = this.dragPivot.x + '% ' + this.dragPivot.y + '%';",
        '  this.dragTilt = 0;',
        '  this.dragTiltV = 0;',
        '  this.dragTiltTarget = 0;',
        '  this.wobbleLast = 0;',
        '  this.stopDragWobble();',
        '  const tick = (now) => {',
        '    if (!this.dragState.active || !this.dragState.dragging) {',
        '      this.wobbleRaf = null;',
        '      return;',
        '    }',
        '    const dt = Math.min(0.05, Math.max(0.001, (now - (this.wobbleLast || now)) / 1000));',
        '    this.wobbleLast = now;',
        '    this.dragTiltTarget *= 0.92; // 指针停下后目标自然衰减到 0，靠弹簧自己晃回去',
        '    this.dragTiltV += ((this.dragTiltTarget - this.dragTilt) * 90 - this.dragTiltV * 7) * dt;',
        '    this.dragTilt += this.dragTiltV * dt;',
        '    const deg = Math.max(-14, Math.min(14, this.dragTilt));',
        "    if (this.stage) this.stage.style.transform = 'rotate(' + deg.toFixed(2) + 'deg)';",
        '    this.wobbleRaf = requestAnimationFrame(tick);',
        '  };',
        '  this.wobbleRaf = requestAnimationFrame(tick);',
        '}',
        '',
        'stopDragWobble() {',
        '  if (this.wobbleRaf) {',
        '    cancelAnimationFrame(this.wobbleRaf);',
        '    this.wobbleRaf = null;',
        '  }',
        '}',
        '',
      ].join('\n'),
    },
    {
      id: 'grab',
      scopeAfter: 'onPointerMove(e) {',
      after: 'd.dragging = true;',
      text: call('grab', 'playPetGrabSound();') + '\n' + call('wobble-start', 'this.startDragWobble(e);'),
    },
    {
      id: 'drag-loop',
      replace: {
        from: `      if (this.animations.drag.length) {
        this.playOnce(S.pick(this.animations.drag));
      }`,
        to: `      if (this.animations.drag.length) {
        // ${MARK}:drag-loop —— 拖拽期间循环播「被拎起来」的动画（这段素材 10s，原来播一次就停在最后一帧）
        const dragName = S.pick(this.animations.drag);
        this.anim = dragName;
        this.once = false;
        this.switchTo(dragName, false);
      }`,
      },
    },
    {
      id: 'tilt',
      scopeAfter: 'onPointerMove(e) {',
      after: 'this.startDragFollow();',
      text: [
        `// ${MARK}:tilt —— 只更新摆动目标：横向速度越大甩得越狠（真正的摆动在 startDragWobble 的 rAF 里积分）`,
        'const tiltTrail = this.dragTrail;',
        'if (tiltTrail.length >= 2) {',
        '  const lastPt = tiltTrail[tiltTrail.length - 1];',
        '  const prevPt = tiltTrail[Math.max(0, tiltTrail.length - 3)];',
        '  if (lastPt.t > prevPt.t) {',
        '    const vx = ((lastPt.x - prevPt.x) / (lastPt.t - prevPt.t)) * 1000;',
        '    this.dragTiltTarget = Math.max(-12, Math.min(12, vx * 0.02));',
        '  }',
        '}',
      ].join('\n'),
    },
    {
      id: 'wobble-stop',
      scopeAfter: 'onPointerUp(e) {',
      after: 'this.stopDragFollow();',
      text:
        call('wobble-stop', 'this.stopDragWobble();') +
        '\n' +
        call('pivot-reset', "this.stage.style.transformOrigin = '';"),
    },
    {
      id: 'throw',
      after: 'startThrow(px, py, vx, vy) {',
      text: call('throw', 'playPetThrowSound(Math.hypot(vx, vy));'),
    },
    {
      id: 'impact',
      after: 'if (res.bounced && grounded && !prevGrounded) {',
      text: call('impact', 'playPetImpactSound(fallingVy);'),
    },
  ],
  'runtime/electron-helper/events.js': [
    {
      id: 'status',
      after: "const terminal = state === 'success' || state === 'error';",
      text: call('status', "if (stateChanged && terminal) playPetStatusSound(state === 'success');"),
    },
  ],
  'src/client/pet.ts': [
    { id: 'kit', before: 'export function makePetUI(rt: {', text: KIT_TS },
    {
      id: 'click',
      scopeAfter: 'const handleClick = () => {',
      after: 'if (d.active || d.dragging || justDraggedRef.current) return;',
      text: call('click', 'playPetClickSound();'),
    },
    {
      id: 'grab',
      scopeAfter: 'const handlePointerMove',
      after: 'd.dragging = true;',
      text: call('grab', 'playPetGrabSound();'),
    },
    {
      id: 'drag-loop',
      scopeAfter: 'd.dragging = true;',
      replace: {
        from: 'setOnce(true);',
        to: 'setOnce(false); // ' + MARK + ':drag-loop —— 拖拽期间循环播放，松手前不会停在最后一帧',
      },
    },
    {
      id: 'throw',
      after: 'const startThrow = (px: number, py: number, vx: number, vy: number) => {',
      text: call('throw', 'playPetThrowSound(Math.hypot(vx, vy));'),
    },
    {
      id: 'impact',
      after: 'if (res.bounced && grounded && !prevGrounded) {',
      text: call('impact', 'playPetImpactSound(fallingVy);'),
    },
    {
      id: 'status',
      after: "const terminal = workStatus.state === 'success' || workStatus.state === 'error';",
      text: call('status', "if (stateChanged) playPetStatusSound(workStatus.state === 'success');"),
    },
  ],
  'lib/client.js': [
    { id: 'kit', before: 'function makePetUI(rt) {', text: KIT_JS },
    {
      id: 'click',
      scopeAfter: 'const handleClick = () => {',
      after: 'if (d.active || d.dragging || justDraggedRef.current) return;',
      text: call('click', 'playPetClickSound();'),
    },
    {
      id: 'grab',
      scopeAfter: 'const handlePointerMove',
      after: 'd.dragging = true;',
      text: call('grab', 'playPetGrabSound();'),
    },
    {
      id: 'drag-loop',
      scopeAfter: 'd.dragging = true;',
      replace: {
        from: 'setOnce(true);',
        to: 'setOnce(false); // ' + MARK + ':drag-loop —— 拖拽期间循环播放，松手前不会停在最后一帧',
      },
    },
    {
      id: 'throw',
      after: 'const startThrow = (px, py, vx, vy) => {',
      text: call('throw', 'playPetThrowSound(Math.hypot(vx, vy));'),
    },
    {
      id: 'impact',
      after: 'if (res.bounced && grounded && !prevGrounded) {',
      text: call('impact', 'playPetImpactSound(fallingVy);'),
    },
    {
      id: 'status',
      after: 'const terminal = workStatus.state === "success" || workStatus.state === "error";',
      text: call('status', "if (stateChanged) playPetStatusSound(workStatus.state === 'success');"),
    },
  ],
};

function parseArgs(argv) {
  const out = { petRoot: null, restore: false, dryRun: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--pet-root') {
      out.petRoot = argv[i + 1];
      if (!out.petRoot) throw new Error('--pet-root 缺少参数');
      i += 1;
    } else if (a === '--restore') out.restore = true;
    else if (a === '--dry-run') out.dryRun = true;
    else if (a === '-h' || a === '--help') {
      console.log('用法：node apply-pet-sfx.mjs [--pet-root <dsh-pet 目录>] [--restore] [--dry-run]');
      process.exit(0);
    } else throw new Error('未知参数：' + a);
  }
  return out;
}

function indentBlock(text, indent) {
  if (!indent) return text;
  return text
    .split('\n')
    .map((line) => (line ? indent + line : line))
    .join('\n');
}

function lineStartOf(src, idx) {
  return src.lastIndexOf('\n', idx) + 1;
}

/** 把文件里所有插入/替换点算成偏移量，再自后向前一次性写入（偏移互不影响）。 */
function patchFile(rel, edits) {
  const src = readFileSync(join(PET_ROOT, rel), 'utf8');
  const ops = [];
  for (const e of edits) {
    const scopeIdx = e.scopeAfter ? src.indexOf(e.scopeAfter) : 0;
    if (e.scopeAfter && scopeIdx === -1) {
      throw new Error(`${rel}：找不到作用域锚点 ${JSON.stringify(e.scopeAfter)}`);
    }

    if (e.replace) {
      const from = e.replace.from;
      const idx = src.indexOf(from, scopeIdx);
      if (idx === -1) throw new Error(`${rel}：找不到待替换片段 ${JSON.stringify(from)}`);
      if (e.scopeAfter && idx - scopeIdx > 600) {
        throw new Error(`${rel}：待替换片段离作用域太远，拒绝盲改（${e.id}）`);
      }
      ops.push({ start: idx, end: idx + from.length, text: e.replace.to, id: e.id });
      continue;
    }

    const anchor = e.before ?? e.after;
    const idx = src.indexOf(anchor, scopeIdx);
    if (idx === -1) throw new Error(`${rel}：找不到锚点 ${JSON.stringify(anchor)}`);
    if (!e.scopeAfter && src.indexOf(anchor, idx + 1) !== -1) {
      throw new Error(`${rel}：锚点 ${JSON.stringify(anchor)} 出现多次，拒绝盲改`);
    }
    const lineStart = lineStartOf(src, idx);
    const indent = src.slice(lineStart, idx);
    const lineEnd = src.indexOf('\n', idx);
    if (lineEnd === -1) throw new Error(`${rel}：锚点所在行没有换行，文件结构异常`);
    // 锚点本身是 `{` 开块行时，函数体应比它再缩进一级
    const bodyIndent = anchor.trimEnd().endsWith('{') ? indent + '  ' : indent;
    const ins = e.before
      ? { pos: lineStart, text: indentBlock(e.text, indent) + '\n\n' }
      : { pos: lineEnd, text: '\n' + indentBlock(e.text, bodyIndent) };
    ops.push({ start: ins.pos, end: ins.pos, text: ins.text, id: e.id });
  }
  ops.sort((a, b) => b.start - a.start);
  let text = src;
  for (const op of ops) text = text.slice(0, op.start) + op.text + text.slice(op.end);
  return { text, count: ops.length };
}

function verify(rel, edits) {
  const text = readFileSync(join(PET_ROOT, rel), 'utf8');
  if (!text.includes(MARK)) throw new Error(`${rel}：写入后找不到补丁标记`);
  for (const e of edits) {
    if (!text.includes(`${MARK}:${e.id}`)) throw new Error(`${rel}：缺少 ${e.id} 这一处插入`);
    if (e.replace) continue;
    const anchor = e.before ?? e.after;
    if (!text.includes(anchor)) throw new Error(`${rel}：锚点 ${e.id} 被破坏`);
  }
}

let PET_ROOT = '';

function main() {
  const args = parseArgs(process.argv.slice(2));
  PET_ROOT =
    args.petRoot ?? join(homedir(), '.dsh', 'profiles', 'desktop', 'node_modules', 'dsh-pet');
  const pkgPath = join(PET_ROOT, 'package.json');
  if (!existsSync(pkgPath)) {
    throw new Error(`找不到 dsh-pet 包：${pkgPath}\n可用 --pet-root 指定安装目录`);
  }
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
  // dsh-pet 及其同引擎衍生包（如 dsh-pet-nhd）都适用：文件结构相同
  if (!/^dsh-pet(-|$)/.test(String(pkg.name))) {
    throw new Error(`${pkgPath} 不是 dsh-pet 系列包（name=${pkg.name}）`);
  }

  const backupDir = join(PET_ROOT, '.click-sound-backup');
  console.log(`[${MARK}] 目标：${PET_ROOT}（dsh-pet v${pkg.version}）`);

  let changed = 0;
  for (const [rel, edits] of Object.entries(EDITS)) {
    const file = join(PET_ROOT, rel);
    if (!existsSync(file)) {
      console.log(`  - ${rel}：文件不存在，跳过`);
      continue;
    }
    if (args.restore) {
      const bak = join(backupDir, rel);
      if (!existsSync(bak)) {
        console.log(`  - ${rel}：没有备份，跳过`);
        continue;
      }
      writeFileSync(file, readFileSync(bak));
      console.log(`  ✓ ${rel}：已还原`);
      changed += 1;
      continue;
    }

    const src = readFileSync(file, 'utf8');
    if (src.includes(MARK)) {
      console.log(`  = ${rel}：已打过补丁，跳过`);
      continue;
    }
    const { text, count } = patchFile(rel, edits);
    if (args.dryRun) {
      console.log(`  ~ ${rel}：将插入 ${count} 处（dry-run，未写入）`);
      continue;
    }
    const bak = join(backupDir, rel);
    if (!existsSync(bak)) {
      mkdirSync(dirname(bak), { recursive: true });
      writeFileSync(bak, src); // 保留最早的原版，反复重打不会覆盖
    }
    writeFileSync(file, text);
    verify(rel, edits);
    console.log(`  ✓ ${rel}：已插入 ${count} 处（备份 ${bak}）`);
    changed += 1;
  }

  console.log('');
  if (!args.dryRun) {
    console.log(`[${MARK}] 完成，改动 ${changed} 个文件。`);
    console.log('生效方式：');
    console.log('  · 桌面宠物：右键宠物 →「重载配置」（或重启 DSH 桌面应用）');
    console.log('  · 网页里的宠物：刷新页面（Ctrl+R）');
  }
}

try {
  main();
} catch (err) {
  console.error(`[${MARK}] 失败：${err instanceof Error ? err.message : String(err)}`);
  process.exit(1);
}
