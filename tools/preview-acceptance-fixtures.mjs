/** 用户提供的本地预览页验收夹具；仅创建标记测试的既有圈，不模拟公开收费创建。 */
import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {readFile,writeFile,unlink} from 'node:fs/promises';
import {base,sql,guest,socket,api,adminLogin} from './test-client.mjs';
if(process.env.RUN_PREVIEW_ACCEPTANCE!=='1'||!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许明确启用的本地预览验收');
const state=JSON.parse(await readFile(new URL('../.local/preview7456-state.json',import.meta.url),'utf8'));
assert.ok(Number.isSafeInteger(state.uid)&&state.uid>0);
const file=new URL('../.local/preview7456-club.json',import.meta.url),mode=process.argv[2];
if(mode==='prepare-club'){
 assert.equal(sql(`SELECT a.is_test FROM user_account a JOIN player_role r ON r.account_id=a.id WHERE r.uid=${state.uid}`),'1');
 assert.equal(sql(`SELECT COUNT(*) FROM club_member m JOIN club c ON c.id=m.club_id WHERE m.uid=${state.uid} AND c.status='ACTIVE'`),'0');
 const a=await guest({test:true}),b=await guest({test:true});
 try{
  const name='验收'+crypto.randomUUID().replaceAll('-','').slice(0,6);
  const id=Number(sql(`START TRANSACTION;INSERT INTO club(name,owner_uid,max_members,max_managers,is_test,created_at,updated_at) VALUES('${name}',${state.uid},1000,1,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));SET @preview_club_id=LAST_INSERT_ID();INSERT INTO club_member(club_id,uid,member_role,joined_at) VALUES(@preview_club_id,${state.uid},'OWNER',UTC_TIMESTAMP(6));INSERT INTO club_wallet_account(club_id,currency) VALUES(@preview_club_id,'card'),(@preview_club_id,'diamond');COMMIT;SELECT @preview_club_id`));
  assert.ok(Number.isSafeInteger(id)&&id>0);
  await writeFile(file,JSON.stringify({clubId:id,name,ownerUid:state.uid,a:{uid:a.uid,token:a.token},b:{uid:b.uid,token:b.token}})+'\n',{mode:0o600,flag:'wx'});
  for(const applicant of [a,b])assert.equal((await applicant.send('club','apply',{id})).state,0);
  console.log(JSON.stringify({clubId:id,name,ownerUid:state.uid,applicantUids:[a.uid,b.uid],pendingApplications:2,scope:'显式既有测试圈，零资产，无生产创建收费'}));
 }catch(error){for(const applicant of [a,b])await api('/api/auth/logout',{token:applicant.token});throw error;}
 finally{a.client.close();b.client.close();}
}else if(mode==='a-apply-leave'){
 const fixture=JSON.parse(await readFile(file,'utf8'));assert.equal(fixture.ownerUid,state.uid);
 const client=await socket();
 try{
  const login=await client.send('login',{api:'user',c:'reg',account:'tk_'+fixture.a.token,plat_id:'2'});assert.equal(login.state,0);assert.equal(login.loginData.role.uid,fixture.a.uid);
  assert.equal((await client.send('logic',{api:'club',c:'applyLeave',id:fixture.clubId,sessionToken:login.sessionToken})).state,0);
  console.log('申请人 A 已通过真实游戏接口发出退圈申请：'+fixture.a.uid);
 }finally{client.close();}
}else if(mode==='verify-club-wire'){
 const fixture=JSON.parse(await readFile(file,'utf8'));assert.equal(fixture.ownerUid,state.uid);
 const client=await socket();
 try{
  const login=await client.send('login',{api:'user',c:'reg',account:'tk_'+fixture.b.token,plat_id:'2'});
  assert.equal(login.state,0);assert.equal(login.loginData.role.uid,fixture.b.uid);
  const send=(c,fields)=>client.send('logic',{api:'club',c,...fields,sessionToken:login.sessionToken});
  const empty=await send('getRoomInfos',{room_ids:[]});
  assert.deepEqual({state:empty.state,room:empty.room,roles:empty.roles},{state:0,room:{},roles:{}});
  assert.equal((await send('getRoomList',{club_id:fixture.clubId})).automatic_room_enabled,false);
  assert.equal((await send('getRoomInfos',{room_ids:['690003']})).state,-18);
  const profile=(await send('getRoles',{club_id:fixture.clubId,uids:[fixture.b.uid]})).roles[fixture.b.uid];
  assert.deepEqual([profile.strength,profile.ore,profile.is_partner],[0,0,0]);
  console.log('真实 WS 验证通过：空房间页、非空查询关闭、成员关闭功能字段为零。');
 }finally{client.close();}
}else if(mode==='inspect'){
 const fixture=JSON.parse(await readFile(file,'utf8'));assert.equal(fixture.ownerUid,state.uid);
 assert.ok(Number.isSafeInteger(fixture.clubId));
 console.log('圈成员计数/实际行数/资产合计：'+sql(`SELECT c.member_count,(SELECT COUNT(*) FROM club_member m WHERE m.club_id=c.id),(SELECT SUM(balance) FROM club_wallet_account w WHERE w.club_id=c.id) FROM club c WHERE c.id=${fixture.clubId} AND c.is_test=TRUE`));
 console.log('申请状态：\n'+sql(`SELECT uid,kind,status FROM club_application WHERE club_id=${fixture.clubId} ORDER BY uid,kind`));
}else if(mode==='cleanup-club'){
 const fixture=JSON.parse(await readFile(file,'utf8'));assert.equal(fixture.ownerUid,state.uid);assert.ok(Number.isSafeInteger(fixture.clubId));
 assert.equal(sql(`SELECT owner_uid FROM club WHERE id=${fixture.clubId} AND is_test=TRUE`),String(state.uid));
 sql(`UPDATE club SET status='SUSPENDED' WHERE id=${fixture.clubId} AND is_test=TRUE`);
 for(const applicant of [fixture.a,fixture.b])assert.equal((await api('/api/auth/logout',{token:applicant.token})).state,0);
 await unlink(file);
 console.log('本次既有测试圈已停用，测试申请人凭据已撤销，事务与审计保留。');
}else if(mode==='prepare-room'){
 const player=await guest({test:true});
 try{
  const roomId=randomUUID(),uid=player.uid,roomCode='690003';
  const snapshot=JSON.stringify({private:'preview-fixture-not-public'}),digest=createHash('sha256').update(snapshot).digest('hex');
  const roomFile=new URL('../.local/preview7456-room.json',import.meta.url);
  await writeFile(roomFile,JSON.stringify({roomId,uid,roomCode})+'\n',{mode:0o600,flag:'wx'});
  sql(`START TRANSACTION;INSERT INTO room_record(room_id,room_code,game_id,owner_uid,creator_uid,options_json,rule_version,is_test,created_at) VALUES('${roomId}','${roomCode}','6003',${uid},${uid},'{}','preview-export-fixture',TRUE,UTC_TIMESTAMP(6));INSERT INTO room_runtime(room_id,state_hash,snapshot_json,snapshot_hash,schema_version,engine_version,updated_at) VALUES('${roomId}','${digest}','${snapshot}','${digest}',1,'preview-export-fixture',UTC_TIMESTAMP(6));COMMIT;`);
  console.log(JSON.stringify({roomId,uid,roomCode,scope:'仅房间查询和导出元数据，不代表公开建房'}));
 }finally{player.client.close();await api('/api/auth/logout',{token:player.token});}
}else if(mode==='verify-room-export-api'){
 const {roomId,uid,roomCode}=JSON.parse(await readFile(new URL('../.local/preview7456-room.json',import.meta.url),'utf8'));
 assert.match(roomId,/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
 assert.ok(Number.isSafeInteger(uid)&&uid>0);
 const token=await adminLogin(),day=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
 const query={ownerUid:uid,start:day,end:day};
 const list=await api('/game-admin/rooms?'+new URLSearchParams(query),undefined,token);
 assert.equal(list.total,1);assert.equal(list.rows[0].roomId,roomId);
 const detail=await api('/game-admin/rooms/'+roomId,undefined,token);
 assert.equal(detail.code,200);
 assert.doesNotMatch(JSON.stringify(detail),/preview-fixture-not-public|snapshotJson|stateHash/);
 const body={query,reason:'7456预览测试房间元数据导出核验'},headers={'Idempotency-Key':randomUUID()};
 const first=await api('/game-admin/room-exports',body,token,headers);
 assert.equal(first.code,200);
 const {jobId}=first.data.result;
 assert.equal((await api('/game-admin/room-exports',body,token,headers)).data.result.jobId,jobId);
 let job;
 for(let attempt=0;attempt<40;attempt++){
  job=(await api('/game-admin/room-exports/'+jobId,undefined,token)).data;
  if(job.status==='DONE')break;
  assert.ok(['READY','RUNNING'].includes(job.status));await new Promise(resolve=>setTimeout(resolve,250));
 }
 assert.equal(job.status,'DONE');assert.equal(job.rowCount,1);
 const response=await fetch(base+'/game-admin/room-exports/'+jobId+'/download',{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(5000)});
 assert.equal(response.status,200);assert.equal(response.headers.get('cache-control'),'no-store');
 assert.ok(response.headers.get('content-type')?.startsWith('text/csv'));
 const bytes=Buffer.from(await response.arrayBuffer()),csv=bytes.toString('utf8'),rows=csv.trimEnd().split('\r\n');
 assert.deepEqual(rows[0].slice(1).split(','),['roomId','roomCode','gameId','ownerUid','creatorUid','clubId','status','isTest','roundNo','createdAt','endedAt','ruleVersion']);
 assert.ok(csv.startsWith('\uFEFF'));assert.equal(rows.length,2);
 assert.ok(rows[1].includes(roomId)&&rows[1].includes(roomCode));assert.doesNotMatch(csv,/preview-fixture-not-public|snapshotJson|stateHash/);
 const result={executedAt:new Date().toISOString(),scope:'实际后台 HTTP 创建、worker 生成、API 下载；显式测试元数据',jobId,roomId,roomCode,rowCount:job.rowCount,bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),result:'PASS'};
 await writeFile(new URL('../docs/evidence/preview-7456-20261107/room-export-api.json',import.meta.url),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result));
}else if(mode==='cleanup-room'){
 const roomFile=new URL('../.local/preview7456-room.json',import.meta.url);
 const {roomId,uid}=JSON.parse(await readFile(roomFile,'utf8'));
 assert.match(roomId,/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
 assert.ok(Number.isSafeInteger(uid)&&uid>0);
 assert.equal(sql(`SELECT COUNT(*) FROM room_record WHERE room_id='${roomId}' AND owner_uid=${uid} AND is_test=TRUE`),'1');
 sql(`START TRANSACTION;DELETE FROM room_runtime WHERE room_id='${roomId}';DELETE FROM room_record WHERE room_id='${roomId}' AND owner_uid=${uid} AND is_test=TRUE;COMMIT;`);
 assert.equal(sql(`SELECT COUNT(*) FROM room_record WHERE room_id='${roomId}'`),'0');
 await unlink(roomFile);
 console.log('仅清理本次房间元数据夹具，保留后台任务和审计。');
}else throw Error('仅支持 prepare-club、a-apply-leave、verify-club-wire、inspect、cleanup-club、prepare-room、verify-room-export-api、cleanup-room');
