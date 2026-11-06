/** 只读取真实负载结果，并在负载结束后检查资源排空；不会修改账户或资金。 */
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {base,adminLogin,api} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地负载复核');
const reportName=process.env.LOAD_REPORT||'lobby-load.json';assert.match(reportName,/^lobby-load(?:-[a-z0-9-]+)?\.json$/);
const report=JSON.parse(await readFile(new URL('../docs/evidence/'+reportName,import.meta.url),'utf8'));
assert.equal(report.result,'PASS','负载未通过，保留失败证据，不生成通过报告');
assert.ok(report.requested.connections===1000&&report.requested.seconds>=3600&&report.measurementMs>=3600000,'只能总结完整千连接 60 分钟结果');
function values(name){return report.samples.flatMap(sample=>sample.metrics[name]||[]).map(row=>row.value).filter(Number.isFinite);}
function range(name,scale=1){const data=values(name);return data.length?{min:Number((Math.min(...data)/scale).toFixed(3)),max:Number((Math.max(...data)/scale).toFixed(3)),first:Number((data[0]/scale).toFixed(3)),last:Number((data.at(-1)/scale).toFixed(3))}:null;}
const summary={authenticated:range('game.authenticated.connections'),accepted:range('game.accepted.connections'),inboundBytes:range('game.inbound.bytes'),businessQueue:range('game.business.queue'),businessActive:range('game.business.active'),replyPending:range('game.reply.pending'),replyActive:range('game.reply.active'),replyQueue:range('game.reply.queue'),pendingPushes:range('game.push.pending'),jvmUsedMiB:range('jvm.memory.used',1024*1024),processCpuFraction:range('process.cpu.usage')};
const token=await adminLogin();let drain;
for(let attempt=0;attempt<20;attempt++){
 const names=['game.accepted.connections','game.authenticated.connections','game.inbound.bytes','game.business.queue','game.business.active','game.reply.pending','game.reply.active','game.reply.queue','game.push.pending'];
 const result=await Promise.all(names.map(async name=>{const metric=await api('/actuator/metrics/'+name,undefined,token);return [name,metric.measurements[0]?.value];}));
 drain={asOf:new Date().toISOString(),metrics:Object.fromEntries(result)};
 if(['game.inbound.bytes','game.business.queue','game.business.active','game.reply.pending','game.reply.active','game.reply.queue','game.push.pending'].every(name=>drain.metrics[name]===0))break;
 await new Promise(resolve=>setTimeout(resolve,250));
}
const drained=['game.inbound.bytes','game.business.queue','game.business.active','game.reply.pending','game.reply.active','game.reply.queue','game.push.pending'].every(name=>drain.metrics[name]===0);
await writeFile(new URL('../docs/evidence/lobby-load-review.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),scope:report.scope,measurementSummary:summary,afterClose:drain,result:drained?'PASS':'FAIL'},null,2)+'\n');
assert.ok(drained,'负载结束后预算或执行队列没有排空');
const actual=report.actual,env=report.environment;
const markdown=`# 本地大厅千连接验收\n\n执行开始：${report.startedAt}；结束：${report.endedAt}。实际测量 ${(report.measurementMs/60000).toFixed(3)} 分钟。\n\n1000 个真实已认证 WS，其中 800 空闲、200 活跃，每秒目标 200 次大厅查询。账号通过正常认证流程建立并标记 is_test，不进入运营统计。使用原 HTTP/WS 协议和代理入口，每连接最多一个在途普通请求；心跳单独计数。\n\n| 项目 | 实际结果 |\n| --- | --- |\n| 普通查询发送 / 完成 | ${actual.sent} / ${actual.completed} |\n| 完成吞吐 | ${actual.throughputPerSecond} 次/秒 |\n| 端到端 P50 / P95 / P99 | ${actual.latencyMs.p50} / ${actual.latencyMs.p95} / ${actual.latencyMs.p99} ms |\n| 最长查询 | ${actual.latencyMs.max} ms |\n| 意外断连 / 错误 | ${actual.closed} / ${Object.values(actual.errors).reduce((a,b)=>a+b,0)} |\n| 收到 Ping / 发出 Pong | ${actual.heartbeats.receivedPings} / ${actual.heartbeats.sentPongs} |\n| 业务队列最大采样值 | ${summary.businessQueue?.max??'未知'} |\n| 入站字节最大采样值 | ${summary.inboundBytes?.max??'未知'} |\n| JVM 已用内存采样区间 | ${summary.jvmUsedMiB?.min??'未知'}–${summary.jvmUsedMiB?.max??'未知'} MiB |\n\nJVM 内存值包含堆和非堆，不等于回收后的存活量，不能仅凭该区间宣称没有内存泄漏。负载结束后入站预算、业务队列/执行数、异步响应票据/编码队列/执行数、待写推送均回零；剩余接入/认证连接分别为 ${drain.metrics['game.accepted.connections']} / ${drain.metrics['game.authenticated.connections']}。\n\n环境：${env.cpu}，主机 ${env.cpus} 个逻辑 CPU，${(env.totalMemoryBytes/1024**3).toFixed(1)} GiB 内存；Node ${env.node}。Docker CPU/内存字节/版本：${env.dockerCpuMemoryVersion}；本机转发 ${env.proxy.portForwarder}，代理 nofile 见原始 JSON。运行 JAR SHA-256：\`${env.artifactSha256}\`，启动时源码提交 \`${env.git}\`。MySQL innodb_flush_log_at_trx_commit=${env.mysqlDurability.innodbFlushLogAtTrxCommit}，sync_binlog=${env.mysqlDurability.syncBinlog}，log_bin=${env.mysqlDurability.logBin}。\n\n## 范围\n\n查询包括 role/syncCard、club/getClubs、manager/getNotices、cfg/getGame(6003)，都经过实际会话验证及相应数据查询。该组负载没有执行牌局状态提交、结算、奖励、回放归档或混合写入，不能转换成每秒相同数量的状态事件；也不是 WSS、公网体验、500 次/秒动作突发或 24 小时稳定性结论。\n\n前三次失败记录原样保留：507 连接处的代理句柄不足、千连接后的指标上游超时/查询积压、本机 SSH 转发丢失。对应修正后从头执行本次完整测量，不拼接失败时长。完整采样见 evidence/${reportName}，排空与汇总见 evidence/lobby-load-review.json。\n`;
await writeFile(new URL('../docs/大厅容量验收.md',import.meta.url),markdown);
console.log(JSON.stringify({result:'PASS',queries:actual.completed,p95Ms:actual.latencyMs.p95,afterClose:drain.metrics}));
