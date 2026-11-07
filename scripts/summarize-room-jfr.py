#!/usr/bin/env python3
"""只发布固定诊断聚合，不发布原始 JFR、网络地址、线程内容或运行凭据。"""
import collections
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from room_load_paths import verify_snapshot

root = Path(__file__).resolve().parents[1]
verify_existing = len(sys.argv) == 3 and sys.argv[2] == '--verify-existing'
if (len(sys.argv) != 2 and not verify_existing) or not re.fullmatch(r'room-storage-load-[a-z0-9-]+\.json', sys.argv[1]):
    raise SystemExit('用法：summarize-room-jfr.py room-storage-load-名称.json [--verify-existing]')
report_path = root / 'docs/evidence' / sys.argv[1]
report = json.loads(report_path.read_text())
if not report.get('flightRecordingEnabled'):
    raise SystemExit('该报告没有启用真实 JFR')
output = report_path.with_name(report_path.name.replace('room-storage-load-', 'room-storage-jfr-'))
if output.is_symlink() or output.exists() and not verify_existing:
    raise SystemExit('保留既有诊断，请使用新报告名称')
snapshots = [p.parent for p in (root / '.local').glob('room-storage-snapshot-*/manifest.json')
             if json.loads(p.read_text()).get('sha256') == report['snapshotSha256']]
if len(snapshots) != 1:
    raise SystemExit('缺少唯一匹配的实际冻结快照')
snapshot = snapshots[0]
verify_snapshot(snapshot, root)
recording = snapshot / 'profile.jfr'
if not recording.is_file() or recording.is_symlink():
    raise SystemExit('缺少已完成录制的私有 JFR 文件')
jfr = shutil.which('jfr')
if not jfr:
    raise SystemExit('请先加载 scripts/java-env.sh')
private_events = snapshot / 'profile-summary-events.json'
if not private_events.exists():
    with private_events.open('xb') as target:
        os.chmod(private_events, 0o600)
        subprocess.run([jfr, 'print', '--json', '--events',
                        'jdk.SocketRead,jdk.GarbageCollection,jdk.ExecutionSample',
                        '--stack-depth', '48', str(recording)], stdout=target, check=True)
if private_events.is_symlink():
    raise SystemExit('拒绝读取符号链接事件文件')

def recorded_events():
    # JFR 的重复栈 JSON 可远大于录制本体；逐个事件解码，内存只保留一个事件。
    decoder = json.JSONDecoder()
    with private_events.open() as source:
        buffer = ''
        while '"events"' not in buffer:
            chunk = source.read(65536)
            if not chunk:
                raise ValueError('JFR JSON 缺少事件列表')
            buffer += chunk
        buffer = buffer.split('"events"', 1)[1]
        while '[' not in buffer:
            buffer += source.read(65536)
        buffer = buffer.split('[', 1)[1]
        while True:
            buffer = buffer.lstrip(' \t\r\n,')
            if buffer.startswith(']'):
                return
            try:
                event, offset = decoder.raw_decode(buffer)
            except json.JSONDecodeError:
                chunk = source.read(65536)
                if not chunk:
                    raise ValueError('JFR JSON 未完整结束')
                buffer += chunk
                if len(buffer) > 8 * 1024 * 1024:
                    raise ValueError('单个 JFR 事件超出解码预算')
                continue
            yield event
            buffer = buffer[offset:]

def instant(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))

def milliseconds(value):
    parts = re.fullmatch(r'PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?', value)
    if not parts:
        raise ValueError('未知 JFR 时长格式')
    hours, minutes, seconds = (float(v or 0) for v in parts.groups())
    return (hours * 3600 + minutes * 60 + seconds) * 1000

def method(frame):
    value = frame['method']
    return value['type']['name'].replace('/', '.') + '.' + value['name']

def distribution(values):
    values = sorted(values)
    def percentile(fraction):
        return round(values[min(len(values)-1, max(0, math.ceil(len(values)*fraction)-1))], 3) if values else None
    return dict(count=len(values), sumMs=round(sum(values), 3), p50Ms=percentile(.5),
                p95Ms=percentile(.95), maxMs=round(values[-1], 3) if values else None)

start, end = instant(report['measurementStartedAt']), instant(report['measurementEndedAt'])
reads = collections.defaultdict(list)
pauses = []
cpu = collections.Counter()
selected = 0
for event in recorded_events():
    value = event['values']
    if not start <= instant(value['startTime']) < end:
        continue
    selected += 1
    frames = (value.get('stackTrace') or {}).get('frames', [])
    if event['type'] == 'jdk.SocketRead':
        project = next((method(frame) for frame in frames if frame['method']['type']['name'].startswith('com/ruoyi/')), 'no_project_frame')
        reads[project].append(milliseconds(value['duration']))
    elif event['type'] == 'jdk.GarbageCollection':
        pauses.append(milliseconds(value['longestPause']))
    elif event['type'] == 'jdk.ExecutionSample' and frames:
        cpu[method(frames[0])] += 1
result = dict(collectedAt=dt.datetime.now(dt.timezone.utc).isoformat(), sourceReport=report_path.name,
              sourceCommit=report['sourceCommit'], snapshotSha256=report['snapshotSha256'],
              recordingSha256=hashlib.sha256(recording.read_bytes()).hexdigest(),
              measurementStartedAt=report['measurementStartedAt'], measurementEndedAt=report['measurementEndedAt'],
              recordingBytes=recording.stat().st_size, selectedEventCount=selected,
              scope='仅筛选真实测量时间段内被 JFR 阈值记录的 SocketRead、GC 与 CPU 栈采样；不包含未达录制阈值的全部 SQL，不是单动作全链路延迟或唯一根因证明。各线程等待会重叠，不能把总和当作墙钟耗时。',
              socketReadsByNearestProjectFrame=[dict(frame=key, **distribution(values)) for key, values in sorted(reads.items(), key=lambda item:sum(item[1]), reverse=True)],
              gcLongestPausePerCollection=distribution(pauses),
              topExecutionSampleFrames=[dict(frame=key, samples=count) for key,count in cpu.most_common(15)],
              result='DIAGNOSTIC')
if verify_existing:
    saved = json.loads(output.read_text())
    for key in ['selectedEventCount', 'socketReadsByNearestProjectFrame', 'gcLongestPausePerCollection', 'topExecutionSampleFrames']:
        if saved[key] != result[key]:
            raise ValueError('流式复核与已保存诊断不一致：' + key)
    print('流式解码与实际诊断聚合一致；未覆盖既有证据')
else:
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('已保存脱敏诊断：' + output.name)
