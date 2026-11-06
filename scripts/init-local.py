#!/usr/bin/env python3
"""创建本地专用随机凭据，禁止覆盖已有环境文件或打印密码。"""
from pathlib import Path
import os
import secrets

root = Path(__file__).resolve().parents[1]
local = root / '.local'
local.mkdir(exist_ok=True)
path = local / 'local.env'
if not path.exists():
    values = {'MYSQL_DATABASE': 'liangshan', 'MYSQL_USER': 'liangshan'}
    for name in ['MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'ADMIN_INITIAL_PASSWORD', 'ADMIN_TOKEN_SECRET']:
        values[name] = secrets.token_urlsafe(64 if name == 'ADMIN_TOKEN_SECRET' else 15 if name == 'ADMIN_INITIAL_PASSWORD' else 24)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(''.join(f'{name}={value}\n' for name, value in values.items()))
    print('已创建 .local/local.env（权限 600）；管理员密码请在本机文件中查看。')
else:
    print('保留现有 .local/local.env。')
