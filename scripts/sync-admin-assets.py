#!/usr/bin/env python3
"""原子替换已忽略的生成资源；旧资源保存在 .local，不递归删除目录。"""
import datetime
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / 'admin-ui/dist'
if not (source / 'index.html').is_file() or source.is_symlink():
    raise SystemExit('缺少有效的已构建后台页面')
local = root / '.local'
if local.is_symlink():
    raise SystemExit('本地生成目录不能是符号链接')
local.mkdir(exist_ok=True)
staging = Path(tempfile.mkdtemp(prefix='admin-assets-staging-', dir=local)) / 'admin'
shutil.copytree(source, staging)
targets = ['ruoyi-admin/src/main/resources/static/admin', 'ruoyi-admin/target/classes/static/admin']
for relative in targets:
    target = root / relative
    if target.is_symlink() or root not in target.resolve().parents:
        raise SystemExit('生成资源目录不能是符号链接')
    subprocess.run(['git', 'check-ignore', '-q', relative + '/index.html'], cwd=root, check=True)
    if subprocess.check_output(['git', 'ls-files', '--', relative], cwd=root):
        raise SystemExit('不能替换包含已跟踪文件的生成目录')
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
backup = local / ('admin-assets-backup-' + stamp)
for index, relative in enumerate(targets):
    target = root / relative
    if target.exists():
        backup.mkdir(exist_ok=True)
        target.rename(backup / str(index))
destination = root / targets[0]
destination.parent.mkdir(parents=True, exist_ok=True)
staging.rename(destination)
print('已同步新后台资源；旧生成资源保留在忽略的 .local 目录')
