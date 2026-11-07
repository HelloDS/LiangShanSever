#!/usr/bin/env python3
"""将构建产物发布到按内容寻址的私有运行目录，运行副本不随 Maven 重打包变化。"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def prepare(source, directory):
    if source.is_symlink() or not source.is_file():
        raise ValueError('缺少普通 JAR 构建文件')
    if directory.is_symlink():
        raise ValueError('运行目录不能是符号链接')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    checksum = digest(source)
    target = directory / (checksum + '.jar')
    if target.is_symlink():
        raise ValueError('运行副本不能是符号链接')
    if target.exists():
        if digest(target) != checksum:
            raise ValueError('既有运行副本摘要不一致，拒绝覆盖')
        return target, checksum
    descriptor, name = tempfile.mkstemp(prefix='.prepare-', dir=directory)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, 'wb') as output, source.open('rb') as input_file:
            shutil.copyfileobj(input_file, output)
            output.flush()
            os.fsync(output.fileno())
        if digest(temporary) != checksum:
            raise ValueError('复制期间构建产物发生变化，请构建结束后重试')
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:
                raise ValueError('构建 JAR 校验失败')
        os.chmod(temporary, 0o400)
        try:
            os.link(temporary, target)  # 不覆盖同名运行文件。
        except FileExistsError:
            if target.is_symlink() or digest(target) != checksum:
                raise ValueError('并发准备的运行副本不一致')
    finally:
        temporary.unlink(missing_ok=True)
    return target, checksum


def main():
    if len(sys.argv) != 2 or not sys.argv[1].isdigit() or int(sys.argv[1]) < 2:
        raise SystemExit('需要启动脚本传入真实 Java 进程 PID')
    root = Path(__file__).resolve().parents[1]
    target, checksum = prepare(root / 'ruoyi-admin/target/ruoyi-admin.jar', root / '.local/runtime')
    metadata = dict(pid=int(sys.argv[1]), relativePath=str(target.relative_to(root)), sha256=checksum,
                    preparedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    descriptor, name = tempfile.mkstemp(prefix='.server-artifact-', dir=root / '.local')
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(metadata, output)
            output.write('\n')
        os.replace(temporary, root / '.local/server-artifact.json')
    finally:
        temporary.unlink(missing_ok=True)
    print(metadata['relativePath'])


if __name__ == '__main__':
    main()
