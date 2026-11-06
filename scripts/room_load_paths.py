"""负载快照只接收实际编译目录和依赖文件。"""
import os
import hashlib
import json
import re
from pathlib import Path


def validated_sources(classpath: str, root: Path) -> list[Path]:
    root = root.resolve()
    # Java 空项表示工作目录，不得递归复制工作区或快照自身。
    sources = [Path(entry).resolve() for entry in classpath.split(os.pathsep) if entry.strip()]
    if not sources:
        raise ValueError('缺少可复制的实际 classpath')
    for source in sources:
        if not source.exists():
            raise ValueError('classpath 条目不存在，拒绝生成不完整负载快照')
        if source.is_dir() and (root not in source.parents or source.name not in {'classes', 'test-classes'} or source.parent.name != 'target'):
            raise ValueError('拒绝复制非 target 编译目录')
    return sources


def digest_snapshot(directory: Path) -> tuple[str, int]:
    files = sorted(path for name in ['entries', 'lib'] for path in (directory / name).rglob('*') if path.is_file())
    digest = hashlib.sha256()
    for path in files:
        if path.is_symlink():
            raise ValueError('快照不能包含符号链接')
        digest.update(str(path.relative_to(directory)).encode())
        digest.update(b'\0')
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
    return digest.hexdigest(), len(files)


def verify_snapshot(directory: Path, root: Path) -> dict:
    directory, root = directory.resolve(), root.resolve()
    if directory.parent != root / '.local' or not re.fullmatch(r'room-storage-snapshot-\d{8}T\d{12}Z', directory.name):
        raise ValueError('只允许复用本工程生成的负载快照')
    metadata = json.loads((directory / 'manifest.json').read_text())
    entries = (directory / 'classpath.txt').read_text().split(os.pathsep)
    if not entries or len(entries) != metadata['classpathEntries']:
        raise ValueError('快照 classpath 数量不一致')
    for entry in entries:
        path = Path(entry).resolve()
        if not entry or not path.exists() or not any(path.is_relative_to(directory / name) for name in ['entries', 'lib']):
            raise ValueError('快照 classpath 含缺失或外部条目')
    sha256, files = digest_snapshot(directory)
    if sha256 != metadata['sha256'] or files != metadata['files']:
        raise ValueError('快照文件或依赖摘要已改变')
    return metadata
