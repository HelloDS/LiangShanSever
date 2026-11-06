"""负载快照只接收实际编译目录和依赖文件。"""
import os
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
