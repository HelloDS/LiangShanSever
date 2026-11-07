#!/usr/bin/env python3
"""停止前同时核验命令和工作目录，不将复用的 PID 当作本项目进程。"""
from pathlib import Path
import re
import shlex
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2 or not re.fullmatch(r'[1-9][0-9]*', sys.argv[1]):
    raise SystemExit(1)
pid = sys.argv[1]
command = subprocess.run(['ps', '-p', pid, '-o', 'args='], capture_output=True, text=True)
arguments = shlex.split(command.stdout.strip())
if len(arguments) < 3 or Path(arguments[0]).name != 'java' or arguments[1] != '-jar':
    raise SystemExit(1)
if arguments[2] != 'ruoyi-admin/target/ruoyi-admin.jar' and not re.fullmatch(r'\.local/runtime/[0-9a-f]{64}\.jar', arguments[2]):
    raise SystemExit(1)
cwd = subprocess.run(['lsof', '-a', '-p', pid, '-d', 'cwd', '-Fn'], capture_output=True, text=True)
directories = [line[1:] for line in cwd.stdout.splitlines() if line.startswith('n')]
raise SystemExit(0 if directories == [str(root)] else 1)
