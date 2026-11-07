/** 本机显式测试元数据；真实 HTTP 任务、生成和下载，不冒充公开建房。 */
import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
import {readFile,writeFile,unlink} from 'node:fs/promises';
import {base,api,adminLogin,guest,sql} from './test-client.mjs';
if(process.env.RUN_ROOM_EXPORT_FIXTURE!=='1'||!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('需明确启用本地房间导出夹具');
const mode=process.argv[2],stateFile=new URL('../.local/room-export-state.json',import.meta.url);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const uuid=value=>assert.match(value,/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
async function download(id,token){const response=await fetch(base+`/game-admin/room-exports/${id}/download`,{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(5000)});assert.equal(response.status,200);assert.ok(response.headers.get('content-type')?.startsWith('text/csv'));assert.equal(response.headers.get('cache-control'),'no-store');return Buffer.from(await response.arrayBuffer());}
if(mode==='prepare'){
 let state;try{state=JSON.parse(await readFile(stateFile,'utf8'));}catch(error){if(error.code!=='ENOENT')throw error;}
 if(!state){
  const player=await guest({test:true});try{
   state={roomId:randomUUID(),uid:player.uid,key:randomUUID(),preparedAt:new Date().toISOString(),pid:(await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim()};
   await writeFile(stateFile,JSON.stringify(state)+'\n',{mode:0o600,flag:'wx'});
   const snapshot='{"private":"export-fixture-not-public"}',digest=hash(snapshot);
   sql(`START TRANSACTION;INSERT INTO room_record(room_id,room_code,game_id,owner_uid,creator_uid,options_json,rule_version,is_test,created_at) VALUES('${state.roomId}','690001','6003',${state.uid},${state.uid},'{}','export-fixture-v1',TRUE,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE);INSERT INTO room_runtime(room_id,state_hash,snapshot_json,snapshot_hash,schema_version,engine_version,updated_at) VALUES('${state.roomId}','${digest}','${snapshot}','${digest}',1,'export-fixture-v1',UTC_TIMESTAMP(6));COMMIT;`);
  }finally{player.client.close();}
 }
 uuid(state.roomId);uuid(state.key);assert.ok(Number.isSafeInteger(state.uid)&&state.uid>0);assert.equal(sql(`SELECT COUNT(*) FROM room_record WHERE room_id='${state.roomId}' AND owner_uid=${state.uid} AND is_test=TRUE`),'1');
 const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date()),yesterday=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date(Date.now()-86400000));
 state.query??={ownerUid:state.uid,start:yesterday,end:today};await writeFile(stateFile,JSON.stringify(state)+'\n',{mode:0o600});
 const token=await adminLogin(),checks=[],check=name=>{checks.push(name);console.log('通过：'+name);};
 const body={query:state.query,reason:'本地房间导出与权限验收'};
 const first=await api('/game-admin/room-exports',body,token,{'Idempotency-Key':state.key});assert.equal(first.code,200);state.jobId=first.data.result.jobId;state.operationId=first.data.operationId;uuid(state.jobId);uuid(state.operationId);await writeFile(stateFile,JSON.stringify(state)+'\n',{mode:0o600});
 const retry=await api('/game-admin/room-exports',body,token,{'Idempotency-Key':state.key});assert.equal(retry.code,200);assert.equal(retry.data.result.jobId,state.jobId);
 assert.equal(sql(`SELECT COUNT(*) FROM admin_operation o JOIN admin_audit a ON a.operation_id=o.operation_id WHERE o.operation_id='${state.operationId}' AND o.status='COMPLETED'`),'1');check('真实创建接口重复请求复用同一任务及其唯一业务审计');
 let job;for(let attempt=0;attempt<40;attempt++){const result=await api('/game-admin/room-exports/'+state.jobId,undefined,token);assert.equal(result.code,200);job=result.data;if(job.status==='DONE')break;assert.ok(['READY','RUNNING'].includes(job.status),'实际生成未完成');await new Promise(resolve=>setTimeout(resolve,250));}
 assert.equal(job.status,'DONE');assert.equal(job.rowCount,1);assert.ok(new Date(job.expiresAt)-new Date(job.completedAt)===86400000);check('实际 worker 完成一行测试房间元数据，文件有效期为完成后 24 小时');
 const csv=await download(state.jobId,token),text=csv.toString('utf8');assert.ok(text.startsWith('\uFEFFroomId,roomCode'));assert.ok(text.includes(state.roomId));assert.equal(text.trimEnd().split('\r\n').length,2);assert.ok(!text.includes('export-fixture-not-public'));
 for(const field of ['leaseToken','scopeHash','filterJson','snapshotJson','guest_credential'])assert.ok(!JSON.stringify(job).includes(field)&&!text.includes(field));
 state.fileSha256=hash(csv);state.fileBytes=csv.length;await writeFile(stateFile,JSON.stringify(state)+'\n',{mode:0o600});check('下载包含实际记录、固定白名单及 no-store，不包含私有快照、任务令牌或路径');
 const list=await api('/game-admin/room-exports',undefined,token);assert.equal(list.code,200);assert.equal(list.data.generationEnabled,true);assert.ok(list.data.jobs.rows.some(row=>row.id===state.jobId));
 assert.equal((await api('/game-admin/room-exports',{query:{...state.query,roomCode:'bad'},reason:body.reason},token,{'Idempotency-Key':randomUUID()})).code,400);check('任务列表可查询，非法筛选由服务端拒绝');
 const player=await guest({test:true});try{assert.equal((await api('/game-admin/room-exports',undefined,player.session)).code,401);assert.equal((await api(`/game-admin/room-exports/${state.jobId}/download`,undefined,player.session)).code,401);}finally{player.client.close();}check('游戏会话不能读取后台任务或领取 CSV');
 await writeFile(new URL('../docs/evidence/room-export-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),scope:'显式测试元数据夹具的真实 HTTP 创建、worker 生成及下载；不代表公开房间创建或十并发导出容量',base,jobId:state.jobId,operationId:state.operationId,rowCount:job.rowCount,fileBytes:csv.length,fileSha256:state.fileSha256,checks,result:'PASS'},null,2)+'\n');
}else if(mode==='checkpoint'){
 const state=JSON.parse(await readFile(stateFile,'utf8'));uuid(state.jobId);const csv=await download(state.jobId,await adminLogin());assert.equal(hash(csv),state.fileSha256);
 state.beforeCrashPid=(await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim();state.beforeCrashObservedAt=new Date().toISOString();await writeFile(stateFile,JSON.stringify(state)+'\n',{mode:0o600});console.log('已在待终止进程中再次实际下载原 CSV，记录对应 PID 与观察时刻。');
}else if(mode==='verify-restart'){
 const state=JSON.parse(await readFile(stateFile,'utf8'));uuid(state.jobId);const pid=(await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim();assert.notEqual(pid,state.pid);
 let crashEvidence=null;if(state.beforeCrashPid){
  const proof=JSON.parse(await readFile(new URL('../.local/outbox-crash-proof.json',import.meta.url),'utf8'));assert.equal(String(proof.pid),state.beforeCrashPid);assert.equal(proof.signal,'SIGKILL');assert.equal(proof.observedExitCode,137);assert.ok(Date.parse(proof.observedAt)>=Date.parse(state.beforeCrashObservedAt));assert.notEqual(pid,state.beforeCrashPid);
  crashEvidence={pid:proof.pid,signal:proof.signal,observedExitCode:proof.observedExitCode,observedAt:proof.observedAt};
 }
 const csv=await download(state.jobId,await adminLogin());assert.equal(hash(csv),state.fileSha256);assert.equal(csv.length,state.fileBytes);assert.equal(sql(`SELECT COUNT(*) FROM room_export_job WHERE id='${state.jobId}' AND status='DONE'`),'1');
 await writeFile(new URL('../docs/evidence/room-export-restart.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),scope:'已完成 CSV 在不同 Java PID 重启后重新鉴权下载，字节摘要一致；如含硬杀证据则已在被杀 PID 中再次实际下载；不覆盖生成中的文件恢复或主机掉电',oldPid:state.beforeCrashPid||state.pid,newPid:pid,beforeCrashObservedAt:state.beforeCrashObservedAt||null,crashEvidence,jobId:state.jobId,fileSha256:state.fileSha256,result:'PASS'},null,2)+'\n');console.log('已完成 CSV 在不同 PID 重启后仍可按当前授权下载，摘要一致。');
}else if(mode==='cleanup'){
 const state=JSON.parse(await readFile(stateFile,'utf8'));uuid(state.roomId);
 assert.equal(sql(`SELECT COUNT(*) FROM room_record WHERE room_id='${state.roomId}' AND is_test=TRUE`),'1');
 sql(`START TRANSACTION;DELETE FROM room_runtime WHERE room_id='${state.roomId}';DELETE FROM room_record WHERE room_id='${state.roomId}' AND is_test=TRUE;COMMIT;`);
 assert.equal(sql(`SELECT COUNT(*) FROM room_record WHERE room_id='${state.roomId}'`),'0');await unlink(stateFile);console.log('显式测试元数据已移除；保留真实任务与业务审计。');
}else throw Error('用法：room-export-smoke.mjs prepare|checkpoint|verify-restart|cleanup');
