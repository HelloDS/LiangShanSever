/** 显式本地故障夹具：源事件为测试 SQL 夹具，领取/消费/租约恢复使用真实运行 worker。 */
import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {once} from 'node:events';
import {base,guest,sql} from './test-client.mjs';

if(process.env.RUN_ROOM_CONSUMER_CRASH!=='1'||!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许显式启用的本地消费故障验收');
const stateFile=new URL('../.local/room-consumer-crash-state.json',import.meta.url);
const proofFile=new URL('../.local/room-consumer-crash-proof.json',import.meta.url);
const mode=process.argv[2];
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const digest=text=>createHash('sha256').update(text).digest('hex');
const count=query=>Number(sql(query));
const pid=Number((await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim());
assert.ok(Number.isSafeInteger(pid)&&pid>1);
if(mode==='prepare-and-kill'){
 assert.match(execFileSync('ps',['-p',String(pid),'-o','command='],{encoding:'utf8'}).trim(),/^java -jar ruoyi-admin\/target\/ruoyi-admin\.jar$/);
 const player=await guest({test:true});player.client.close();
 const room=crypto.randomUUID(),event=crypto.randomUUID(),rule='consumer-crash-fixture-v1';
 const before=digest('{"value":0}'),after=digest('{"value":1}');
 sql(`START TRANSACTION;INSERT INTO room_record(room_id,room_code,game_id,owner_uid,creator_uid,options_json,rule_version,is_test,created_at) VALUES('${room}','690002','6003',${player.uid},${player.uid},'{}','${rule}',TRUE,UTC_TIMESTAMP(6));INSERT INTO room_event(room_id,event_seq,owner_epoch,round_no,phase,event_kind,payload_json,request_hash,before_hash,after_hash,schema_version,engine_version,occurred_at,committed_at) VALUES('${room}',1,1,0,'WAITING','TEST_INCREMENT','{"delta":1}','${digest(event)}','${before}','${after}',1,'counter-v1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));INSERT INTO room_event_projection(room_id,event_count,last_version,last_occurred_at,processed_at) VALUES('${room}',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));COMMIT`);
 const lock=spawn('docker',['exec','-i','liangshan-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --unbuffered --default-character-set=utf8mb4 --batch --skip-column-names -u"$MYSQL_USER" "$MYSQL_DATABASE"'],{stdio:['pipe','pipe','pipe']});
 let output='',diagnostic='';lock.stdout.on('data',chunk=>{output+=chunk;});lock.stderr.on('data',chunk=>{diagnostic+=chunk;});
 const exited=once(lock,'exit');let killed=false;
 try{
  lock.stdin.write(`START TRANSACTION;SELECT room_id FROM room_event_projection WHERE room_id='${room}' FOR UPDATE;SELECT 'LOCK_READY';\n`);
  let deadline=Date.now()+5000;
  while(!output.includes('LOCK_READY')&&Date.now()<deadline){assert.equal(lock.exitCode,null,'锁夹具提前退出');await pause(25);}
  assert.ok(output.includes('LOCK_READY'),'未取得独立投影锁');
  const payload=JSON.stringify({eventSeq:1,roomId:room});
  sql(`INSERT INTO outbox_event(id,biz_key,event_type,schema_version,rule_version,payload,request_hash,retry_at,occurred_at) VALUES('${event}','room-event:${room}:1','ROOM_EVENT_COMMITTED',1,'${rule}','${payload}','${digest('ROOM_EVENT_COMMITTED\n1\n'+rule+'\n'+payload)}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))`);
  deadline=Date.now()+4000;let observed=null;
  while(Date.now()<deadline){
   const row=sql(`SELECT status,total_attempts,lease_token,lease_until FROM outbox_event WHERE id='${event}'`).split('\t');
   if(row[0]==='CLAIMED'){
    // 领取事务已经提交；NOWAIT 冲突证明另一个事务持有事件锁，消费者正在副作用事务中。
    try{sql(`SELECT id FROM outbox_event WHERE id='${event}' FOR UPDATE NOWAIT`);}
    catch(error){assert.match(String(error.stderr),/3572/);observed={status:row[0],attempts:Number(row[1]),leaseToken:row[2],leaseUntil:row[3],consumerRowLockObserved:true};break;}
   }
   await pause(25);
  }
  assert.ok(observed,'未在时限内观察到消费者事务持锁');assert.equal(observed.attempts,1);
  assert.equal(count(`SELECT COUNT(*) FROM consumer_receipt WHERE event_id='${event}'`),0);
  const state={preparedAt:new Date().toISOString(),pid,room,event,uid:player.uid,observed};
  await writeFile(stateFile,JSON.stringify(state)+'\n',{mode:0o600,flag:'wx'});
  process.kill(pid,'SIGKILL');killed=true;
  console.log('已观察到 CLAIMED 及消费者持锁，随后向已核验本地 Java PID 发送 SIGKILL；退出码仍须外部会话观察。');
 }finally{
  lock.stdin.end('ROLLBACK;\n');
  await Promise.race([exited,pause(3000).then(()=>{if(lock.exitCode===null)lock.kill('SIGTERM');})]);
  assert.equal(diagnostic,'','独立锁连接出现诊断');
 }
 assert.ok(killed);
 assert.equal(count(`SELECT COUNT(*) FROM consumer_receipt WHERE event_id='${event}'`),0);
 assert.equal(count(`SELECT event_count FROM room_event_projection WHERE room_id='${room}'`),0);
 assert.equal(sql(`SELECT status FROM outbox_event WHERE id='${event}'`),'CLAIMED');
 console.log('硬杀后消费收据与投影均未提交，持久租约保持 CLAIMED。');
}else if(mode==='verify'){
 const state=JSON.parse(await readFile(stateFile,'utf8')),proof=JSON.parse(await readFile(proofFile,'utf8'));
 assert.match(state.room,/^[0-9a-f-]{36}$/);assert.match(state.event,/^[0-9a-f-]{36}$/);
 assert.equal(proof.pid,state.pid);assert.equal(proof.signal,'SIGKILL');assert.equal(proof.observedExitCode,137);
 assert.ok(Date.parse(proof.observedAt)>=Date.parse(state.preparedAt));assert.notEqual(pid,state.pid);
 assert.throws(()=>process.kill(state.pid,0),error=>error.code==='ESRCH');
 let status,deadline=Date.now()+90000;
 while(Date.now()<deadline){status=sql(`SELECT status FROM outbox_event WHERE id='${state.event}'`);if(status==='DONE')break;await pause(500);}
 assert.equal(status,'DONE');assert.equal(count(`SELECT total_attempts FROM outbox_event WHERE id='${state.event}'`),2);
 assert.equal(count(`SELECT COUNT(*) FROM consumer_receipt WHERE event_id='${state.event}'`),1);
 assert.equal(count(`SELECT event_count FROM room_event_projection WHERE room_id='${state.room}'`),1);
 assert.equal(count(`SELECT last_version FROM room_event_projection WHERE room_id='${state.room}'`),1);
 assert.equal(count(`SELECT COUNT(*) FROM wallet_transaction WHERE reference_id='${state.room}'`),0);
 sql(`UPDATE room_record SET status='CLOSED',ended_at=UTC_TIMESTAMP(6) WHERE room_id='${state.room}' AND is_test=TRUE`);
 await writeFile(new URL('../docs/evidence/room-consumer-crash-recovery.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),scope:'显式 SQL 测试源事件，真实 worker 领取并在消费者事务持锁时 SIGKILL；不同 PID 等待原 60 秒租约到期后重领。不是公开牌局命令、数据库崩溃或提交 ACK 丢失验收。',beforeKill:{status:'CLAIMED',attempts:1,consumerRowLockObserved:true,committedReceipts:0,projectionCount:0},crashEvidence:proof,afterRestart:{status:'DONE',attempts:2,committedReceipts:1,projectionCount:1,lastVersion:1,walletTransactions:0},result:'PASS'},null,2)+'\n');
 console.log('租约到期后原事件第二次领取完成，收据与投影各一，未产生资产命令。');
}else throw Error('用法：prepare-and-kill|verify');
