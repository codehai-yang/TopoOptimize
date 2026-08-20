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
import signal
import struct
import argparse
import socket
import socketserver
from typing import Optional, Tuple

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
MODEL_PATH = os.path.join(SCRIPT_DIR, 'best_model.pt')
NORM_PARAMS_PATH = os.path.join(SCRIPT_DIR, 'normalization_params.json')

# ============================================================
# 模型
# ============================================================
class CostModelV2(nn.Module):
    def __init__(self):
        super().__init__()
        self.input_proj = nn.Linear(NODE_FEAT_DIM, HIDDEN_DIM)
        self.convs = nn.ModuleList()
        self.norms = nn.ModuleList()
        for _ in range(NUM_LAYERS):
            mlp = nn.Sequential(
                nn.Linear(HIDDEN_DIM, HIDDEN_DIM * 2), nn.ReLU(),
                nn.Linear(HIDDEN_DIM * 2, HIDDEN_DIM),
            )
            self.convs.append(GINEConv(mlp, edge_dim=EDGE_FEAT_DIM))
            self.norms.append(nn.LayerNorm(HIDDEN_DIM))
        self.regressor = nn.Sequential(
            nn.Linear(HIDDEN_DIM, HIDDEN_DIM // 2), nn.ReLU(),
            nn.Linear(HIDDEN_DIM // 2, 1),
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
# ============================================================
def deserialize_binary(data: bytes) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    off = 0
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
    return x, ei, ea

# ============================================================
# 模型加载 / 推理
# ============================================================
_device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
_model: Optional[CostModelV2] = None

def get_model():
    global _model
    if _model is None:
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        _model = CostModelV2()
        _model.load_state_dict(torch.load(MODEL_PATH, map_location=_device))
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

def do_predict(raw: bytes, seq: int = 0) -> str:
    """完整推理流水线，返回一行 JSON。"""
    start = time.time()
    x, ei, ea = deserialize_binary(raw)
    nnodes = x.shape[0]
    ea, x = normalize_input(ea, x, num_nodes=nnodes)
    pred_norm = predict(x, ei, ea)
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
        sock.settimeout(30)  # 30s 读超时

        while True:
            try:
                header = _recv_exact(sock, 4)
                payload_len = struct.unpack('>i', header)[0]

                if payload_len == 0:
                    break  # 退出信号

                raw = _recv_exact(sock, payload_len)

                # 推理（阻塞当前 handler 线程，但其他连接不受影响）
                result_line = do_predict(raw)
                sock.sendall((result_line + '\n').encode('utf-8'))

            except (ConnectionError, socket.timeout, OSError):
                break
            except Exception as e:
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
    parser = argparse.ArgumentParser(description='GINE 模型推理 TCP 服务器')
    parser.add_argument('--port', type=int, default=16000, help='监听端口（默认 16000）')
    parser.add_argument('--host', default='127.0.0.1', help='绑定地址（默认 127.0.0.1）')
    args = parser.parse_args()

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
    main()
