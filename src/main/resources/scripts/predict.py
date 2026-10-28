"""
GINE 模型推理 TCP 服务器 —— socketserver + ThreadPoolExecutor，零第三方依赖。

协议（与管道帧兼容）：
  客户端 → 服务端:  [4字节 payload_len][payload]  Big-Endian
  服务端 → 客户端:  JSON行\n
   payload_len=0 → 客户端断开连接

启动：
  python predict.py [--port 15000] [--workers 11]

关闭：SIGINT(Ctrl+C) 或 SIGTERM，收到信号后优雅退出。
"""
import sys
import os
import json
import time
import datetime
import signal
import struct
import argparse
import socket
import socketserver
import threading
import traceback
from typing import Optional, Tuple

# ============================================================
# 日志：每个接口请求独立日志文件 (python_logs/方案名_日期_毫秒.log)
# 不重启 Python 进程，通过线程局部变量为每个请求路由日志
# ============================================================

_thread_local = threading.local()
_open_log_files = {}            # filename -> file handle (缓存已打开的日志文件)
_open_log_files_lock = threading.Lock()


def _get_or_open_log_file(filename):
    """按文件名获取(或打开并缓存)日志文件句柄。"""
    if not filename:
        return None
    with _open_log_files_lock:
        lf = _open_log_files.get(filename)
        if lf is None or lf.closed:
            path = os.path.join(_log_dir, filename)
            lf = open(path, 'a', encoding='utf-8')
            _open_log_files[filename] = lf
    return lf


def _set_thread_log_file(filename):
    """设置当前线程的日志文件 (一个接口请求的所有 predict 共用同一文件)。"""
    if filename:
        _thread_local.log_file = _get_or_open_log_file(filename)
    else:
        _thread_local.log_file = None


def _log(msg):
    """写一条带时间戳的日志到当前线程的日志文件；
    若未设置线程局部文件(启动阶段)，则写到启动日志 (fd 1/2)。"""
    ts = datetime.datetime.now().strftime('[%Y-%m-%d %H:%M:%S]')
    line = '%s %s\n' % (ts, msg)
    lf = getattr(_thread_local, 'log_file', None)
    if lf is not None:
        lf.write(line)
        lf.flush()
    else:
        sys.__stdout__.write(line)
        sys.__stdout__.flush()


def _log_traceback():
    """将当前异常的 traceback 写到当前线程的日志文件。"""
    lf = getattr(_thread_local, 'log_file', None)
    if lf is not None:
        traceback.print_exc(file=lf)
        lf.flush()
    else:
        traceback.print_exc()


# 解析命令行参数，设置日志目录 (仅请求阶段的日志文件使用，不做启动日志重定向)
_parser = argparse.ArgumentParser(description='GINE 模型推理 TCP 服务器')
_parser.add_argument('--port', type=int, default=16000, help='监听端口（默认 16000）')
_parser.add_argument('--host', default='127.0.0.1', help='绑定地址（默认 127.0.0.1）')
_parser.add_argument('--log-dir', default='python_logs', help='日志目录（默认 python_logs）')
_args = _parser.parse_args()
_log_dir = _args.log_dir
try:
    if not os.path.isdir(_log_dir):
        os.makedirs(_log_dir, exist_ok=True)
except Exception as e:
    sys.__stderr__.write('[predict] create log dir fail: %s\n' % e)

import warnings
warnings.filterwarnings('ignore', message=r".*pyg-lib.*")
warnings.filterwarnings('ignore', message=r".*torch-scatter.*")
warnings.filterwarnings('ignore', message=r".*torch-spline-conv.*")
warnings.filterwarnings('ignore', message=r".*torch-sparse.*")

import numpy as np
import torch
from torch_geometric.nn import GINEConv, global_add_pool
import torch.nn as nn

# ============================================================
# 全局配置
# ============================================================
EDGE_FEAT_DIM = 4    # 通断(3) + 长度(1)
NODE_FEAT_DIM = 200
HIDDEN_DIM = 64
NUM_LAYERS = 3

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(SCRIPT_DIR, 'best_modelV2.pt')
NORM_PARAMS_PATH = os.path.join(SCRIPT_DIR, 'normalization_params.json')

# ============================================================
# 模型
# 说明: HIDDEN_DIM / NUM_LAYERS 只是默认值，真实结构在 get_model() 里
# 从 checkpoint 的权重形状推断。best_model.pt(V1) 是 hidden=64，
# best_modelV2.pt 是 hidden=128，写死常量会 load_state_dict 形状不匹配。
# ============================================================
class CostModelV2(nn.Module):
    def __init__(self, hidden_dim=HIDDEN_DIM, num_layers=NUM_LAYERS):
        super().__init__()
        self.input_proj = nn.Linear(NODE_FEAT_DIM, hidden_dim)
        self.convs = nn.ModuleList()
        self.norms = nn.ModuleList()
        for _ in range(num_layers):
            mlp = nn.Sequential(
                nn.Linear(hidden_dim, hidden_dim * 2), nn.ReLU(),
                nn.Linear(hidden_dim * 2, hidden_dim),
            )
            self.convs.append(GINEConv(mlp, edge_dim=EDGE_FEAT_DIM))
            self.norms.append(nn.LayerNorm(hidden_dim))
        half = max(hidden_dim // 2, 1)
        self.regressor = nn.Sequential(
            nn.Linear(hidden_dim, half), nn.ReLU(),
            nn.Linear(half, 1),
        )

    def forward(self, x, edge_index, edge_attr, batch=None):
        h = self.input_proj(x)
        for conv, norm in zip(self.convs, self.norms):
            h = conv(h, edge_index, edge_attr)
            h = norm(h)
            h = torch.relu(h)
        if batch is None:
            batch = torch.zeros(h.size(0), dtype=torch.long, device=h.device)
        graph_emb = global_add_pool(h, batch)
        return self.regressor(graph_emb).squeeze()

# ============================================================
# 标准化（与 Normalize.py 一致）
# ============================================================
_normalization_params = None

def _load_norm_params():
    with open(NORM_PARAMS_PATH, 'r', encoding='utf-8') as f:
        return json.load(f)

def _get_norm_params():
    global _normalization_params
    if _normalization_params is None:
        _normalization_params = _load_norm_params()
    return _normalization_params

def normalize_branch_feature(bf, mean, std):
    r = bf.copy()
    if std > 0: r[:, 3] = (r[:, 3] - mean) / std
    else: r[:, 3] = 0.0
    return r

def normalize_price_matrix(cc, mean, std, nnodes):
    r = cc.copy()
    pm = r[:, :nnodes]
    mask = pm != 0
    if mask.sum() > 0 and std > 0: pm[mask] = (pm[mask] - mean) / std
    r[:, :nnodes] = pm
    return r

def normalize_wet_cost(cc, mean, std, nnodes):
    r = cc.copy()
    wc = r[:, nnodes]
    mask = wc != 0
    if mask.sum() > 0 and std > 0: wc[mask] = (wc[mask] - mean) / std
    r[:, nnodes] = wc
    return r

def normalize_input(branch_feature, circuit_cost, num_nodes):
    p = _get_norm_params()
    bf = normalize_branch_feature(branch_feature, p['branch_length_mean'], p['branch_length_std'])
    cc = normalize_price_matrix(circuit_cost, p['price_mean'], p['price_std'], num_nodes)
    cc = normalize_wet_cost(cc, p['wet_cost_mean'], p['wet_cost_std'], num_nodes)
    return bf, cc

def denormalize_output(val):
    p = _get_norm_params()
    return val * p['total_cost_std'] + p['total_cost_mean']

# ============================================================
# 二进制反序列化
# payload 结构: [log_name_len(4)][log_name(UTF-8)][nodeDim(4)][edgeDim(4)][...]
# ============================================================
def deserialize_binary(data: bytes) -> Tuple[str, np.ndarray, np.ndarray, np.ndarray]:
    off = 0
    name_len = int(np.frombuffer(data[off:off+4], dtype='>i4')[0]);  off += 4
    log_name = data[off:off+name_len].decode('utf-8');              off += name_len
    nn = int(np.frombuffer(data[off:off+4], dtype='>i4')[0]);   off += 4
    ne = int(np.frombuffer(data[off:off+4], dtype='>i4')[0]);   off += 4
    eib = 2 * ne * 4
    eab = ne * EDGE_FEAT_DIM * 4
    xb  = nn * NODE_FEAT_DIM * 4
    ei = (np.frombuffer(data[off:off+eib], dtype='>i4')
          .reshape(2, ne).byteswap().newbyteorder('=').copy());  off += eib
    ea = (np.frombuffer(data[off:off+eab], dtype='>f4')
          .reshape(ne, EDGE_FEAT_DIM).byteswap().newbyteorder('=').copy()); off += eab
    x  = (np.frombuffer(data[off:off+xb], dtype='>f4')
          .reshape(nn, NODE_FEAT_DIM).byteswap().newbyteorder('=').copy())
    return log_name, x, ei, ea

# ============================================================
# 模型加载 / 推理
# ============================================================
_device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
_model: Optional[CostModelV2] = None


def _infer_arch(state_dict):
    """从 checkpoint 权重形状推断 (hidden_dim, num_layers)，避免写死常量。"""
    hidden = int(state_dict['input_proj.weight'].shape[0])
    layer_ids = set()
    for k in state_dict:
        if k.startswith('convs.'):
            layer_ids.add(int(k.split('.')[1]))
    num_layers = (max(layer_ids) + 1) if layer_ids else NUM_LAYERS
    return hidden, num_layers


def get_model():
    global _model
    if _model is None:
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        state = torch.load(MODEL_PATH, map_location=_device)
        # 兼容 {'model_state_dict': {...}} / {'state_dict': {...}} 这类包装格式
        if isinstance(state, dict) and 'input_proj.weight' not in state:
            for key in ('model_state_dict', 'state_dict', 'model', 'net'):
                if key in state and isinstance(state[key], dict):
                    state = state[key]
                    break
        hidden_dim, num_layers = _infer_arch(state)
        print(f"[predict] model={os.path.basename(MODEL_PATH)} "
              f"arch: hidden={hidden_dim}, layers={num_layers}")
        _model = CostModelV2(hidden_dim=hidden_dim, num_layers=num_layers)
        _model.load_state_dict(state)
        _model.to(_device)
        _model.eval()
    return _model

def predict(x: np.ndarray, edge_index: np.ndarray, edge_attr: np.ndarray) -> float:
    model = get_model()
    xt = torch.tensor(x, dtype=torch.float).to(_device)
    eit = torch.tensor(edge_index, dtype=torch.long).to(_device)
    eat = torch.tensor(edge_attr, dtype=torch.float).to(_device)
    with torch.no_grad():
        return model(xt, eit, eat).item()

def do_predict(x: np.ndarray, edge_index: np.ndarray, edge_attr: np.ndarray) -> str:
    """完整推理流水线，返回一行 JSON。"""
    start = time.time()
    nnodes = x.shape[0]
    ea, x = normalize_input(edge_attr, x, num_nodes=nnodes)
    pred_norm = predict(x, edge_index, ea)
    cost = denormalize_output(pred_norm)
    elapsed = (time.time() - start) * 1000
    return json.dumps({"predicted_cost": round(float(cost), 4), "elapsed_ms": round(elapsed, 1)})

# ============================================================
# TCP 请求处理器（socketserver.ThreadingMixIn 每个连接一个线程）
# PyTorch 推理时释放 GIL，多线程可真正并行。
# ============================================================

def _recv_exact(sock, n):
    """从 socket 精确读取 n 字节。"""
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("客户端断开")
        buf += chunk
    return buf

class PredictHandler(socketserver.BaseRequestHandler):
    """每个 TCP 连接 = 一个 handler 实例，在独立线程中运行。"""

    def handle(self):
        sock: socket.socket = self.request
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        # 长连接复用模型：等待下一个请求时不设短超时，避免"闲置误杀"。
        # 推理本身只需几十 ms，Java 端 setSoTimeout 已保证 Python 真卡死时的兜底。
        sock.settimeout(None)
        conn_id = id(sock)

        while True:
            try:
                header = _recv_exact(sock, 4)
                payload_len = struct.unpack('>i', header)[0]

                if payload_len == 0:
                    break  # 退出信号

                raw = _recv_exact(sock, payload_len)
                # 解析日志文件名，设置线程局部日志文件 (仅错误日志会写到该文件)
                log_name, x, ei, ea = deserialize_binary(raw)
                _set_thread_log_file(log_name)

                # 推理（阻塞当前 handler 线程，但其他连接不受影响）
                result_line = do_predict(x, ei, ea)
                sock.sendall((result_line + '\n').encode('utf-8'))

            except (ConnectionError, socket.timeout, OSError) as e:
                _log(f"[predict] connection error: {e}, conn={conn_id}")
                _log_traceback()
                break
            except Exception as e:
                _log(f"[predict] inference error: {e}, conn={conn_id}")
                _log_traceback()
                err = json.dumps({"error": str(e)})
                try:
                    sock.sendall((err + '\n').encode('utf-8'))
                except OSError:
                    pass

class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    """多线程 TCP 服务器 —— 每个连接一个线程，并发推理。"""
    allow_reuse_address = True
    daemon_threads = True

# ============================================================
# 入口
# ============================================================
def main():
    # 命令行参数和日志重定向已在模块加载阶段完成(早于 torch 导入)
    args = _args

    # 预加载模型和归一化参数
    print(f"predict load model... device={_device}")
    get_model()
    _get_norm_params()
    print(f"Model ready")

    server = ThreadedTCPServer((args.host, args.port), PredictHandler)
    print(f"[predict] jian ting {args.host}:{args.port}")

    def shutdown(sig, frame):
        print("\n[predict]mo xing zhun bei tui chu ...")
        server.shutdown()
        server.server_close()
        print("[predict] yi guan bi")
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        shutdown(None, None)

if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Java 端把 stdout/stderr 重定向到了 NUL，启动异常必须落地到文件才看得见
        _tb = traceback.format_exc()
        try:
            _err_path = os.path.join(_log_dir, 'startup_error.log')
            with open(_err_path, 'a', encoding='utf-8') as _f:
                _f.write('%s [predict] startup failed\n%s\n'
                         % (datetime.datetime.now().strftime('[%Y-%m-%d %H:%M:%S]'), _tb))
        except Exception:
            pass
        sys.__stderr__.write(_tb)
        sys.__stderr__.flush()
        sys.exit(1)
