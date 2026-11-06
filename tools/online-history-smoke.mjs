/** 等待真实分钟 worker 保存观察，不注入历史人数或伪造停机采样。 */
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {base,api,adminLogin,guest,markTest,sql} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地在线历史验收');
const checks=[],check=name=>{checks.push(name);console.log('通过：'+name);},token=await adminLogin();
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date()),path='/game-admin/online/history?date='+today;
const first=await api(path,undefined,token);assert.equal(first.code,200);assert.equal(first.data.zone,'Asia/Shanghai');assert.equal(first.data.samplingEnabled,true);
const existing=new Set(first.data.rows.map(row=>row.sampleMinute));let result;
for(let attempt=0;attempt<16;attempt++){
 result=await api(path,undefined,token);assert.equal(result.code,200);
 if(result.data.rows.some(row=>!existing.has(row.sampleMinute)))break;
 await new Promise(resolve=>setTimeout(resolve,5000));
}
const added=result.data.rows.filter(row=>!existing.has(row.sampleMinute));assert.ok(added.length>0,'真实分钟采样尚未产生');
check('真实 worker 在分钟边界产生持久观察，没有脚本补写历史');
for(const row of result.data.rows){assert.ok(row.sampleMinute.endsWith('Z'));assert.ok(row.observedAt.endsWith('Z'));assert.equal(new Date(row.sampleMinute).getUTCSeconds(),0);assert.ok(['AVAILABLE','REBUILDING','UNAVAILABLE'].includes(row.dataStatus));if(row.dataStatus==='AVAILABLE')assert.ok(Number.isInteger(row.onlineCount)&&row.onlineCount>=0);else assert.equal(row.onlineCount,null);}
assert.ok(result.data.rows.length<=1440);assert.equal(sql('SELECT COUNT(*) FROM online_minute_history WHERE data_status<>\'AVAILABLE\' AND online_count IS NOT NULL'),'0');
check('采样含真实时间与有效状态，未知人数明确为 null');
const tomorrow=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date(Date.now()+86400000));assert.equal((await api('/game-admin/online/history?date='+tomorrow,undefined,token)).code,400);check('未来日期查询由服务端拒绝');
const player=await guest({test:true});markTest(player.uid);try{assert.equal((await api(path,undefined,player.session)).code,401);check('游戏会话不能读取后台在线历史');}finally{player.client.close();}
await writeFile(new URL('../docs/evidence/online-history-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,date:today,newObservations:added.map(({sampleMinute,observedAt,dataStatus,onlineCount})=>({sampleMinute,observedAt,dataStatus,onlineCount})),checks,result:'PASS'},null,2)+'\n');
console.log(`在线历史 HTTP/Admin ${checks.length} 项通过。`);
