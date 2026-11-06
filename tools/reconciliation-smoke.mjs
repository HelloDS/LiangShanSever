/** 验证本地实际只读核对，不注入差异、不调整资产。 */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {base,api,adminLogin,guest,markTest,sql} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地账本核对验收');
const checks=[],check=name=>{checks.push(name);console.log('通过：'+name);},token=await adminLogin();
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
const before={balance:sql('SELECT COALESCE(SUM(balance+frozen),0) FROM wallet_account'),clubBalance:sql('SELECT COALESCE(SUM(balance+frozen),0) FROM club_wallet_account'),systemBalance:sql('SELECT COALESCE(SUM(balance),0) FROM wallet_system_account'),transactions:sql('SELECT COUNT(*) FROM wallet_transaction'),ledger:sql('SELECT COUNT(*) FROM wallet_ledger')};
const key=randomUUID(),reason='本地本日账本核对验收';
const first=await api('/game-admin/reconciliation/today',{reason},token,{'Idempotency-Key':key});assert.equal(first.code,200);
const again=await api('/game-admin/reconciliation/today',{reason},token,{'Idempotency-Key':key});assert.equal(again.code,200);assert.equal(again.data.operationId,first.data.operationId);
const id=first.data.result.run.id;assert.match(id,/^[0-9a-f-]{36}$/);
assert.equal(sql(`SELECT COUNT(*) FROM wallet_reconciliation_run WHERE scheduled_date='${today}'`),'1');
check('重复管理员请求复用本日任务与原命令结果');
let detail;
for(let attempt=0;attempt<180;attempt++){
 const result=await api(`/game-admin/reconciliation/${id}?status=MISMATCH`,undefined,token);assert.equal(result.code,200);detail=result.data;
 if(['DONE','DONE_WITH_ISSUES','FAILED'].includes(detail.run.status))break;
 await new Promise(resolve=>setTimeout(resolve,250));
}
assert.ok(['DONE','DONE_WITH_ISSUES'].includes(detail.run.status),'核对尚未完成或任务失败');
assert.equal(detail.run.checkedCount,detail.run.matchedCount+detail.run.mismatchedCount);assert.equal(detail.total,detail.run.mismatchedCount);
assert.equal(detail.run.checkedCount,Number(sql(`SELECT COUNT(*) FROM wallet_reconciliation_detail WHERE run_id='${id}'`)));
assert.ok(detail.run.completedAt.endsWith('Z'));check('真实 worker 完成账户核对，进度及差异数量与数据库一致');
const matched=await api(`/game-admin/reconciliation/${id}?status=MATCH`,undefined,token);assert.equal(matched.code,200);
for(const row of [...detail.rows,...matched.data.rows])for(const field of ['expectedAvailable','actualAvailable','expectedFrozen','actualFrozen','ownerId'])assert.equal(typeof row[field],'string');
check('资产数值与主体编号以精确字符串输出，时间带明确时区');
for(const path of ['/game-admin/reconciliation?start=2026-01-01&end=2026-03-01',`/game-admin/reconciliation?start=${today}&end=${today}&status=BOGUS`,`/game-admin/reconciliation?start=${today}&end=${today}&pageSize=201`])assert.equal((await api(path,undefined,token)).code,400);
check('日期、状态及分页边界由服务端拒绝');
const player=await guest({test:true});markTest(player.uid);
try{assert.equal((await api(`/game-admin/reconciliation?start=${today}&end=${today}`,undefined,player.session)).code,401);check('游戏会话不能读取平台财务核对');}finally{player.client.close();}
assert.equal(sql('SELECT COALESCE(SUM(balance+frozen),0) FROM wallet_account'),before.balance);assert.equal(sql('SELECT COUNT(*) FROM wallet_transaction'),before.transactions);assert.equal(sql('SELECT COUNT(*) FROM wallet_ledger'),before.ledger);
assert.equal(sql('SELECT COALESCE(SUM(balance+frozen),0) FROM club_wallet_account'),before.clubBalance);assert.equal(sql('SELECT COALESCE(SUM(balance),0) FROM wallet_system_account'),before.systemBalance);
check('核对没有改变资产、账本或资金交易数量');
await writeFile(new URL('../docs/evidence/reconciliation-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,run:{id,status:detail.run.status,checked:detail.run.checkedCount,matched:detail.run.matchedCount,mismatched:detail.run.mismatchedCount},checks,result:'PASS'},null,2)+'\n');
console.log(`本日账本核对 HTTP/Admin ${checks.length} 项通过。`);
