/** 本地实际管理命令/审计关联验收，不注入审计记录或资金。 */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {once} from 'node:events';
import {writeFile} from 'node:fs/promises';
import {base,api,adminLogin,guest,markTest,sql} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地业务审计验收');
const checks=[],check=name=>{checks.push(name);console.log('通过：'+name);},token=await adminLogin();
const player=await guest({test:true});markTest(player.uid);
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
const key=randomUUID(),reason='本地业务审计精确关联验收',closed=once(player.client.ws,'close');
const first=await api(`/game-admin/players/${player.uid}/kick`,{reason},token,{'Idempotency-Key':key});assert.equal(first.code,200);await closed;
const again=await api(`/game-admin/players/${player.uid}/kick`,{reason},token,{'Idempotency-Key':key});assert.equal(again.code,200);assert.equal(first.data.operationId,again.data.operationId);
const operation=first.data.operationId;assert.match(operation,/^[a-f0-9-]{36}$/);
const list=await api(`/game-admin/audit?start=${today}&end=${today}&operationId=${operation}`,undefined,token);assert.equal(list.code,200);assert.equal(list.total,1);assert.equal(list.rows[0].target,String(player.uid));
assert.equal(list.rows[0].operationStatus,'COMPLETED');assert.equal(list.rows[0].operationId,operation);check('实际命令重复请求复用一项操作及其唯一审计');
const detail=await api(`/game-admin/audit/${list.rows[0].id}`,undefined,token);assert.equal(detail.code,200);const record=detail.data.record;
assert.equal(record.reason,reason);assert.equal(record.operationId,operation);assert.ok(record.createdAt.endsWith('Z'));assert.equal(record.before.sessionVersion,'0');assert.equal(record.after.sessionVersion,'1');
for(const forbidden of ['requestHash','requestKey','resultJson','beforeJson','afterJson','sessionToken','password'])assert.ok(!JSON.stringify(record).includes(forbidden));
assert.ok(!JSON.stringify(record).includes(key));assert.equal(typeof record.id,'string');assert.equal(typeof record.operatorId,'string');check('详情含业务摘要与明确时间，不包含凭据或任意私有载荷');
assert.equal(sql(`SELECT COUNT(*) FROM admin_audit WHERE operation_id='${operation}'`),'1');assert.equal(sql(`SELECT session_version FROM user_account a JOIN player_role r ON r.account_id=a.id WHERE r.uid=${player.uid}`),'1');check('审计、完成状态与实际业务副作用均为一次');
for(const path of ['/game-admin/audit?start=2026-01-01&end=2026-03-01',`/game-admin/audit?start=${today}&end=${today}&pageSize=201`,`/game-admin/audit?start=${today}&end=${today}&operationId=bad`])assert.equal((await api(path,undefined,token)).code,400);
check('服务端拒绝超范围日期、分页与无效命令编号');
const other=await guest({test:true});markTest(other.uid);try{assert.equal((await api(`/game-admin/audit?start=${today}&end=${today}`,undefined,other.session)).code,401);check('游戏会话不能查询平台业务审计');}finally{other.client.close();}
await writeFile(new URL('../docs/evidence/audit-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,operationId:operation,auditId:record.id,checks,result:'PASS'},null,2)+'\n');
console.log(`业务审计 HTTP/Admin ${checks.length} 项通过。`);
