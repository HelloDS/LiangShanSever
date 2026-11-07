/** 短测独立复核：不写完整一小时的规范验收报告，不覆盖旧结果。 */
import assert from 'node:assert/strict';
import {readFile,writeFile,access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {base,adminLogin,api} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地短测复核');
const name=process.argv[2];assert.match(name||'',/^lobby-load-[a-z0-9-]+\.json$/);
const output=new URL('../docs/evidence/'+name.replace('lobby-load-','lobby-trial-review-'),import.meta.url);
try{await access(output);throw Error('保留既有短测复核结果');}catch(error){if(error.code!=='ENOENT')throw error;}
const report=JSON.parse(await readFile(new URL('../docs/evidence/'+name,import.meta.url),'utf8'));
assert.equal(report.result,'PASS','原负载未通过，不能生成通过复核');
assert.equal(report.requested.connections,1000);assert.ok(report.requested.seconds>=10&&report.requested.seconds<3600);
assert.ok(report.measurementMs>=report.requested.seconds*1000);
const artifact=createHash('sha256').update(await readFile(new URL('../ruoyi-admin/target/ruoyi-admin.jar',import.meta.url))).digest('hex');
assert.equal(artifact,report.environment.artifactSha256,'测量后的运行 JAR 摘要已改变');
const names=['game.accepted.connections','game.authenticated.connections','game.inbound.bytes','game.business.queue','game.business.active','game.reply.pending','game.reply.active','game.reply.queue','game.push.pending'];
const token=await adminLogin();let drain;
for(let attempt=0;attempt<30;attempt++){
 const observed=await Promise.all(names.map(async name=>{const row=await api('/actuator/metrics/'+name,undefined,token);const value=row.measurements?.[0]?.value;assert.ok(Number.isFinite(value),'指标查询未完成');return [name,value];}));
 drain={asOf:new Date().toISOString(),metrics:Object.fromEntries(observed)};
 if(names.every(name=>drain.metrics[name]===0))break;
 await new Promise(resolve=>setTimeout(resolve,250));
}
const drained=names.every(name=>drain.metrics[name]===0);
await writeFile(output,JSON.stringify({executedAt:new Date().toISOString(),sourceReport:name,measurementMs:report.measurementMs,artifactSha256:artifact,sourceCommit:report.environment.git,
 scope:`本机千连接大厅 ${report.requested.seconds} 秒短测与九项测后资源检查；不是一小时、完整 WS 牌局或 24 小时验收。`,afterClose:drain,result:drained?'PASS':'FAIL'},null,2)+'\n',{flag:'wx'});
assert.ok(drained,'测后接入连接、预算或执行队列尚未全部排空');
console.log('短测原始结果通过，九项资源全部回零，JAR 摘要一致；保留一小时规范报告。');
