#!/usr/bin/env python3
"""复制实际已编译类、资源与测试依赖，确保后台开发/编译不改变正在测量的代码。"""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from room_load_paths import validated_sources, digest_snapshot, verify_snapshot

root = Path(__file__).resolve().parents[1]
if len(sys.argv) > 1:
    if len(sys.argv) != 3 or sys.argv[1] != '--verify':
        raise SystemExit('仅接受 --verify <既有快照目录>')
    existing = Path(sys.argv[2]).resolve()
    verify_snapshot(existing, root)
    print(existing)
    raise SystemExit(0)
reports = sorted((root / 'ruoyi-admin/target/surefire-reports').glob('TEST-*.xml'), key=lambda path: path.stat().st_mtime, reverse=True)
if not reports:
    raise SystemExit('需先实际执行完整测试，缺少真实测试 classpath')
properties = {entry.get('name'): entry.get('value') for entry in ET.parse(reports[0]).getroot().findall('properties/property')}
classpath = properties.get('surefire.test.class.path')
if not classpath:
    raise SystemExit('实际测试报告没有 classpath')
try:
    sources = validated_sources(classpath, root)
except ValueError as error:
    raise SystemExit(str(error)) from None
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
destination = root / '.local' / ('room-storage-snapshot-' + stamp)
destination.mkdir(parents=True, exist_ok=False)
entries = []
for index, source in enumerate(sources):
    if source.is_dir():
        target = destination / 'entries' / str(index)
        shutil.copytree(source, target)
    else:
        target = destination / 'lib' / (str(index) + '-' + source.name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    entries.append(str(target))
sha256, files = digest_snapshot(destination)
metadata = dict(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                sha256=sha256, files=files, classpathEntries=len(entries))
(destination / 'manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
(destination / 'classpath.txt').write_text(os.pathsep.join(entries))
print(destination)
