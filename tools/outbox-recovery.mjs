/** 分 prepare/verify 两步，由操作者在两步间重启本地 Java；测试游客只用于恢复验收。 */
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {once} from 'node:events';
import {base,api,adminLogin,guest,markTest,sql,socket} from './test-client.mjs';
if(process.env.RUN_RECOVERY_FIXTURES!=='1'||!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许明确启用的本地恢复验收');
const kind=process.env.RECOVERY_KIND||'normal';if(!['normal','hard-kill'].includes(kind))throw Error('不支持的恢复场景');
const mode=process.argv[2],file=new URL(kind==='normal'?'../.local/outbox-recovery-fixture.json':'../.local/outbox-crash-fixture.json',import.meta.url);
if(mode==='prepare'){
 const player=await guest({test:true});markTest(player.uid);const admin=await adminLogin(),closing=once(player.client.ws,'close');
 const operation=await api(`/game-admin/players/${player.uid}/kick`,{reason:'本地暂停领取与重启恢复验收'},admin,{'Idempotency-Key':crypto.randomUUID()});assert.equal(operation.code,200);await closing;
 const row=sql(`SELECT id,status FROM outbox_event WHERE biz_key='player-revoked:${player.uid}:1'`).split('\t');
 assert.equal(row[1],'READY','prepare 必须在明确关闭 worker 的 Java 进程中执行');
 await writeFile(file,JSON.stringify({preparedAt:new Date().toISOString(),pid:(await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim(),uid:player.uid,token:player.token,eventId:row[0],operationId:operation.data.operationId})+'\n',{mode:0o600});
 console.log('已提交真实撤销命令，持久事件处于 READY；现在按对应场景结束并重启 Java。');
}else if(mode==='verify'){
 const fixture=JSON.parse(await readFile(file,'utf8'));
 assert.match(fixture.eventId,/^[0-9a-f-]{36}$/);assert.match(fixture.operationId,/^[0-9a-f-]{36}$/);assert.ok(Number.isSafeInteger(fixture.uid));
 const pid=(await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim();assert.notEqual(pid,fixture.pid,'必须实际重启 Java');
 let crashEvidence=null;
 if(kind==='hard-kill'){
  const proof=JSON.parse(await readFile(new URL('../.local/outbox-crash-proof.json',import.meta.url),'utf8'));
  assert.equal(String(proof.pid),fixture.pid);assert.equal(proof.signal,'SIGKILL');assert.equal(proof.observedExitCode,137);assert.ok(Date.parse(proof.observedAt)>=Date.parse(fixture.preparedAt));
  assert.throws(()=>process.kill(Number(fixture.pid),0),error=>error.code==='ESRCH','被终止的旧进程仍存在，不能记为硬杀恢复');
  crashEvidence={pid:proof.pid,signal:proof.signal,observedExitCode:proof.observedExitCode,observedAt:proof.observedAt};
  assert.equal(sql(`SELECT COUNT(*) FROM admin_operation o JOIN admin_audit a ON a.operation_id=o.operation_id WHERE o.operation_id='${fixture.operationId}' AND o.status='COMPLETED'`),'1');
 }
 const admin=await adminLogin();let status;
 for(let i=0;i<30;i++){
  status=sql(`SELECT status FROM outbox_event WHERE id='${fixture.eventId}'`);if(status==='DONE')break;
  await new Promise(resolve=>setTimeout(resolve,250));
 }
 assert.equal(status,'DONE');
 assert.equal(Number(sql(`SELECT COUNT(*) FROM outbox_event WHERE biz_key='player-revoked:${fixture.uid}:1'`)),1);
 assert.equal(Number(sql(`SELECT session_version FROM user_account a JOIN player_role r ON r.account_id=a.id WHERE r.uid=${fixture.uid}`)),1);
 const client=await socket();
 try{
  const login=await client.send('login',{api:'user',c:'reg',account:'tk_'+fixture.token,plat_id:'2'});assert.equal(login.state,0);assert.equal(login.loginData.role.uid,fixture.uid);
  assert.deepEqual(await client.send('logic',{api:'role',c:'syncCard',sessionToken:login.sessionToken}),{state:0,card:0,diamond:0});
 }finally{client.close();}
 const backlog=await api('/game-admin/outbox/backlog',undefined,admin);assert.equal(backlog.code,200);
 await writeFile(new URL(kind==='normal'?'../docs/evidence/outbox-recovery.json':'../docs/evidence/outbox-crash-recovery.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),preparedAt:fixture.preparedAt,crashEvidence,scope:kind==='normal'?'关闭领取后真实命令写入 READY，经正常 Java 进程重启恢复；不是硬杀进程或提交 ACK 丢失故障验收':'关闭领取后真实命令已确认写入 READY，观察到 SIGKILL/137 后由不同 PID 恢复；不覆盖 CLAIMED 租约中断、MySQL 崩溃或提交 ACK 丢失',checks:['暂停 worker 时管理事务与 outbox 持久化','不同 Java PID 重启后原事件进入 DONE','事件仍一条、账户会话版本仍一次递增','原持久游客凭据恢复同一 UID，余额仍为零'],result:'PASS'},null,2)+'\n');
 console.log('重启后原事件完成，身份、版本和零余额核验通过。');
}else throw Error('用法：outbox-recovery.mjs prepare|verify');
