import json, sys

path = r"f:\office\idearProjects\project20251009\src\main\resources\分配优化.txt"
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)

print("=== TOP LEVEL KEYS ===")
for k in data.keys():
    v = data[k]
    if isinstance(v, list):
        print(f"  {k}: list[{len(v)}]")
    elif isinstance(v, dict):
        print(f"  {k}: dict(keys={list(v.keys())[:20]})")
    else:
        print(f"  {k}: {type(v).__name__} = {str(v)[:80]}")

def show_one(name, lst, idx=0):
    print(f"\n=== {name}[{idx}] fields ===")
    if not lst:
        print("  (empty)")
        return
    item = lst[idx]
    if isinstance(item, dict):
        for kk, vv in item.items():
            s = str(vv)
            if len(s) > 120:
                s = s[:120] + f"...<len={len(str(vv))}>"
            print(f"  {kk}: {s}")
    else:
        print("  ", type(item), str(item)[:200])

for key in ("loopInfos","appPositions","edges","points"):
    if key in data and isinstance(data[key], list):
        print(f"\n##### {key}  length={len(data[key])} #####")
        show_one(key, data[key], 0)
        if len(data[key]) > 1:
            show_one(key, data[key], 1)
