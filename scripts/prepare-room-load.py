#!/usr/bin/env python3
"""复制实际已编译类、资源与测试依赖，确保后台开发/编译不改变正在测量的代码。"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
reports = sorted((root / 'ruoyi-admin/target/surefire-reports').glob('TEST-*.xml'), key=lambda path: path.stat().st_mtime, reverse=True)
if not reports:
    raise SystemExit('需先实际执行完整测试，缺少真实测试 classpath')
properties = {entry.get('name'): entry.get('value') for entry in ET.parse(reports[0]).getroot().findall('properties/property')}
classpath = properties.get('surefire.test.class.path')
if not classpath:
    raise SystemExit('实际测试报告没有 classpath')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
destination = root / '.local' / ('room-storage-snapshot-' + stamp)
destination.mkdir(parents=True, exist_ok=False)
entries = []
for index, entry in enumerate(classpath.split(os.pathsep)):
    source = Path(entry)
    if not source.exists():
        raise SystemExit('classpath 条目不存在，拒绝生成不完整负载快照')
    if source.is_dir():
        target = destination / 'entries' / str(index)
        shutil.copytree(source, target)
    else:
        target = destination / 'lib' / (str(index) + '-' + source.name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    entries.append(str(target))
digest = hashlib.sha256()
files = sorted(path for path in destination.rglob('*') if path.is_file())
for path in files:
    digest.update(str(path.relative_to(destination)).encode())
    digest.update(b'\0')
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
metadata = dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                sha256=digest.hexdigest(), files=len(files), classpathEntries=len(entries))
(destination / 'manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
(destination / 'classpath.txt').write_text(os.pathsep.join(entries))
print(destination)
