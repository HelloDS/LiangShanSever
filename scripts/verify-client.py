#!/usr/bin/env python3
"""验证 Creator 所有已跟踪文件仅发生登记的地址替换，包含原换行与 meta 摘要。"""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
client = root.parent / 'Client'
baseline = json.loads((root / 'docs/evidence/client-baseline.json').read_text())['files']
allowed = {entry['file']: entry for entry in json.loads((root / 'docs/evidence/client-address-changes.json').read_text())['allowedChanges']}
changes = []
for name, expected in baseline.items():
    data = (client / name).read_bytes()
    if hashlib.sha256(data).hexdigest() == expected:
        continue
    change = allowed.get(name)
    if not change:
        raise SystemExit(f'发现白名单外修改：{name}')
    current, original = change['new'].encode(), change['old'].encode()
    if data.count(current) != 1 or hashlib.sha256(data.replace(current, original)).hexdigest() != expected:
        raise SystemExit(f'文件含地址替换之外的修改：{name}')
    changes.append(name)
print(f'已核验 {len(baseline)} 个 Creator 文件；仅 {len(changes)} 处登记的地址文件发生变化。')
