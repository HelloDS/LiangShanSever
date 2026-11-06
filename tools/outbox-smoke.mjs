import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {once} from 'node:events';
import {base,api,adminLogin,guest,markTest,sql,socket} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地持久任务验收');
const checks=[],check=name=>{checks.push(name);console.log('通过：'+name);};
const admin=await adminLogin(),player=await guest({test:true});markTest(player.uid);
const closing=once(player.client.ws,'close'),key=crypto.randomUUID();
const first=await api(`/game-admin/players/${player.uid}/kick`,{reason:'本地持久撤销通知验收'},admin,{'Idempotency-Key':key});
assert.equal(first.code,200);await closing;
check('真实管理踢人提交后关闭旧连接');
const again=await api(`/game-admin/players/${player.uid}/kick`,{reason:'本地持久撤销通知验收'},admin,{'Idempotency-Key':key});
assert.equal(again.data.operationId,first.data.operationId);
assert.equal(Number(sql(`SELECT session_version FROM user_account a JOIN player_role r ON r.account_id=a.id WHERE r.uid=${player.uid}`)),1);
assert.equal(Number(sql(`SELECT COUNT(*) FROM outbox_event WHERE biz_key='player-revoked:${player.uid}:1'`)),1);
check('管理重试复用原命令且只有一个持久事件');
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
let task;
for(let i=0;i<30;i++){
 const result=await api(`/game-admin/outbox?start=${today}&end=${today}&pageSize=200`,undefined,admin);
 assert.equal(result.code,200);task=result.rows.find(row=>row.businessKey===`player-revoked:${player.uid}:1`);
 if(task?.status==='DONE')break;
 await new Promise(resolve=>setTimeout(resolve,250));
}
assert.equal(task?.status,'DONE');assert.ok(task.totalAttempts>=1);assert.ok(!('payload' in task));
check('独立后台 worker 领取并完成已提交的撤销事件');
const replacement=await socket();
try{
 const login=await replacement.send('login',{api:'user',c:'reg',account:'tk_'+player.token,plat_id:'2'});
 assert.equal(login.state,0);assert.equal(login.loginData.role.uid,player.uid);
 const balance=await replacement.send('logic',{api:'role',c:'syncCard',sessionToken:login.sessionToken});
 assert.deepEqual(balance,{state:0,card:0,diamond:0});
 check('同一游客可按新会话版本重登，权威余额仍为零');
 const denied=await api('/game-admin/outbox/backlog',undefined,login.sessionToken);assert.equal(denied.code,401);
 check('游戏会话不能读取持久任务后台');
}finally{replacement.close();}
const backlog=await api('/game-admin/outbox/backlog',undefined,admin);assert.equal(backlog.code,200);
const metrics=await api('/actuator/metrics/game.outbox.completed',undefined,admin);assert.ok(metrics.measurements[0].value>=1);
check('积压状态与实际完成指标可采集');
await writeFile(new URL('../docs/evidence/outbox-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,checks,result:'PASS'},null,2)+'\n');
console.log(`共 ${checks.length} 项通过。`);
