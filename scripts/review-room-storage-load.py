#!/usr/bin/env python3
"""只复核完整实际结果；短测或失败结果不得生成一小时通过报告。"""
import argparse
import datetime
import json
from pathlib import Path
import re
from room_load_paths import verify_snapshot

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--input', type=Path, default=root / 'docs/evidence/room-storage-load.json')
args = parser.parse_args()
result = json.loads(args.input.read_text())


def require(condition, message):
    if not condition:
        raise SystemExit('拒绝生成通过报告：' + message)


require(result.get('result') == 'PASS', '原始负载未通过')
requested, actual = result['requested'], result['actual']
require(requested['seconds'] >= 3600 and result['measurementMs'] >= 3600000, '不足完整 60 分钟')
require(requested['rooms'] == 200 and requested['players'] == 600 and requested['commitsPerSecond'] == 200, '不是本组既定负载')
require(actual['sent'] == actual['completed'] >= requested['seconds'] * 200 * .99, '完成数量不足或存在未确认提交')
require(all(actual[name] == 0 for name in ['failed', 'busy', 'missed']), '存在错误、忙碌跳过或漏发')
admin_sessions = requested.get('adminSessions', 0)
require(admin_sessions in [0, 10], '后台会话数不符合本组设定')
if admin_sessions:
    admin = result.get('adminReads', {})
    require(admin.get('sessions') == 10 and admin.get('intervalSeconds') == 10, '未保存十会话真实 HTTP 读取记录')
    require(admin.get('sent') == admin.get('completed') and admin.get('completed', 0) >= 10 * (requested['seconds'] // 10 - 1), '后台读取数量不足')
    require(all(admin.get(key, -1) == 0 for key in ['failed', 'busy', 'pending']), '后台读取失败、跳过或未排空')
    require(admin.get('latencyMs', {}).get('p95', 999999) <= 300, '后台读取 P95 超出预算')
require(actual['played'] + actual['passed'] + actual['testRedeals'] == actual['completed'], '动作分类数量不守恒')
require(actual['latencyMs']['p95'] <= 300, '内部持久确认 P95 超出 300ms')
require(result['sampleFailures'] == 0 and result['maxObservedOutboxLagSeconds'] <= 10, '采样失败或 outbox 超出预算')
require(all(value == 0 for value in result['pending'].values()), '队列未排空')
require(all(result['outboxAfterDrain'][key] == 0 for key in ['ready', 'claimed', 'dead']), '持久任务未处理完毕')
require(all(result.get('outboxCounters', {}).get(key, -1) == 0 for key in ['failed', 'lease.lost', 'poll.failed']), '消费或轮询错误计数非零/未记录')
totals = result['persistentTotals']
require(len({totals[key] for key in ['events', 'outboxDone', 'consumerReceipts', 'projectedEvents']}) == 1, '事件/消费收据/投影数量不一致')
require(totals['walletTransactions'] == 0, '本组不应存在资金命令')
require(totals['events'] == requested['rooms'] + result['warmup']['completed'] + actual['completed'], '初始/预热/测量事件数量不一致')
require(all(value == 1 for value in result['durability'].values()), '持久性设置不符合本组要求')
require(re.fullmatch('[0-9a-f]{40}', result['sourceCommit']) and re.fullmatch('[0-9a-f]{64}', result['snapshotSha256']), '代码来源或快照摘要缺失')
artifacts = []
for manifest in (root / '.local').glob('room-storage-snapshot-*/manifest.json'):
    metadata = json.loads(manifest.read_text())
    if metadata['sha256'] == result['snapshotSha256'] and metadata['sourceCommit'] == result['sourceCommit']:
        verify_snapshot(manifest.parent, root)
        artifacts.append(str(manifest.parent))
require(bool(artifacts), '没有匹配且完整的冻结编译产物')
samples = result['samples']
require(len(samples) >= 700 and len(result['recoverySamples']) == 10, '采样或恢复检查不足')


def interval(name, scale=1):
    values = [float(sample[name]) / scale for sample in samples]
    return dict(min=round(min(values), 3), max=round(max(values), 3), first=round(values[0], 3), last=round(values[-1], 3))


histogram = result['eventPayloadSizeHistogram']
require(sum(row['events'] for row in histogram) == totals['events'], '事件字节分布不完整')
threshold = totals['events'] * .95
seen = 0
payload_p95 = None
for row in sorted(histogram, key=lambda row: row['bytes']):
    seen += row['events']
    if seen >= threshold:
        payload_p95 = row['bytes']
        break
payload_mean = sum(row['bytes'] * row['events'] for row in histogram) / totals['events']
growth = result['mysqlDirectoryAfterBytes'] - result['mysqlDirectoryBeforeBytes']
recovery = result['recoverySamples']
summary = dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), result='PASS', scope=result['scope'],
               sourceCommit=result['sourceCommit'], snapshotSha256=result['snapshotSha256'], artifactVerified=True,
               heapMiB=interval('heapUsedBytes', 1024**2), nonHeapMiB=interval('nonHeapUsedBytes', 1024**2),
               threads=interval('threads'), roomSqlActive=interval('roomSqlactive'), roomSqlWaiters=interval('roomSqlwaiters'),
               generalSqlActive=interval('generalSqlactive'), generalSqlWaiters=interval('generalSqlwaiters'),
               outboxReady=interval('outboxReady'), maxOutboxLagSeconds=result['maxObservedOutboxLagSeconds'],
               eventPayloadMeanBytes=round(payload_mean, 3), eventPayloadP95Bytes=payload_p95,
               mysqlDirectoryGrowthBytes=growth, recoveryMaxMs=round(max(row['milliseconds'] for row in recovery), 3),
               recoveryMaxTailEvents=max(row['tailEvents'] for row in recovery))
(root / 'docs/evidence/room-storage-review.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
latency = actual['latencyMs']
markdown = f'''# 内部房间持久写入一小时复核

测量开始：{result['measurementStartedAt']}；结束：{result['measurementEndedAt']}。单调时钟测量 {result['measurementMs']/60000:.3f} 分钟。

200 个内部房间、600 个持久测试身份，每秒目标 200 次状态提交。已确定地主的牌局通过合法单张/过牌推进，结束后按测试规则轮换地主并保存新发牌结果。每次动作都经过房间邮箱、真实 MySQL 事务、提交确认与状态安装；不含 WS、叫分、费用或积分结算。

本组后台会话数：{admin_sessions}。若启用十会话，使用隔离库真实管理员的十个独立 JWT，经 HTTP 权限链每十秒查询真实房间、指定玩家及看板；不包含圈在线排序或异步导出，不能当成方案完整混合负载通过。实际后台统计见原始结果 adminReads。

| 项目 | 实际结果 |
| --- | --- |
| 已发 / 完成 | {actual['sent']} / {actual['completed']} |
| 单张 / 过牌 / 测试换局 | {actual['played']} / {actual['passed']} / {actual['testRedeals']} |
| 完成吞吐 | {actual['throughput']:.3f} 次/秒 |
| 内部确认 P50 / P95 / P99 | {latency['p50']:.3f} / {latency['p95']:.3f} / {latency['p99']:.3f} ms |
| 最长确认 / 超过 300ms 次数 | {latency['max']:.3f} ms / {result['slowCommitCount']} |
| 错误 / 忙碌 / 漏发 | {actual['failed']} / {actual['busy']} / {actual['missed']} |
| 最老 outbox 最大采样值 | {result['maxObservedOutboxLagSeconds']:.3f} 秒 |
| 持久事件 / DONE / 收据 / 投影 | {totals['events']} / {totals['outboxDone']} / {totals['consumerReceipts']} / {totals['projectedEvents']} |
| 事件载荷平均 / P95 | {payload_mean:.3f} / {payload_p95} 字节 |
| 十个房间恢复最长耗时 / 最大尾事件数 | {summary['recoveryMaxMs']} ms / {summary['recoveryMaxTailEvents']} |

事件总数包含 200 个初始发牌、预热及测量阶段；不是同等数量的完整终局或积分结算。钱包命令为零。测后内存队列、I/O、房间提交结果核对调度及 outbox 均排空，十个抽查房间从真实检查点/事件重放后与原状态一致。冻结类与依赖重新计算摘要匹配。

JVM 堆已用采样 {summary['heapMiB']['min']}–{summary['heapMiB']['max']} MiB，非堆 {summary['nonHeapMiB']['min']}–{summary['nonHeapMiB']['max']} MiB；这些不是回收后的存活量，不能单凭区间认定没有泄漏。房间/普通 SQL 等待线程最大采样值分别为 {summary['roomSqlWaiters']['max']} / {summary['generalSqlWaiters']['max']}。

全过程 MySQL 目录净增 {growth/1024**2:.3f} MiB，包含初始化、表/索引、binlog、redo、临时文件和系统库，不能直接当作精确单事件写放大或真实业务日量。information_schema 大小和行数为估计，原值保存在原始 JSON；本组没有执行归档或 24 小时运行。

环境：{result['environment']['os']} / {result['environment']['arch']}，JVM 可见 {result['environment']['availableProcessors']} 个 CPU，堆上限 {result['environment']['maxHeapBytes']/1024**2:.0f} MiB，Java {result['environment']['java']}。MySQL flushAtCommit / syncBinlog / logBin 均为 1；固定 MySQL/Redis 镜像摘要见原始结果。源码提交 `{result['sourceCommit']}`，冻结快照 SHA-256 `{result['snapshotSha256']}`。

这是一组内部持久机械出牌负载，不等于方案中的千人 WS、200 次/秒完整合法牌局与结算、后台混合写入、WSS、公网或 24 小时验收。前三轮短测及其失败、竞争测试干扰和判定差异原样保留，见房间持久写入测量.md；不拼接运行时长。
'''
(root / 'docs/房间持久写入验收.md').write_text(markdown)
print(f"已复核完整内部持久负载：{actual['completed']} 次，P95 {latency['p95']:.3f}ms，PASS")
