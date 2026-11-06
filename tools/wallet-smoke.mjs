import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {guest,markTest,adminLogin,api,sql,base} from './test-client.mjs';
const checks=[],player=await guest({test:true});markTest(player.uid);
try{
 const token=await adminLogin(),today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date()),path=`/game-admin/wallets/PERSONAL/${player.uid}/card`;
 const ledger=await api(path+`?start=${today}&end=${today}`,undefined,token);assert.equal(ledger.code,200);assert.deepEqual(ledger.account,{available:'0',frozen:'0',version:'0'});assert.equal(ledger.total,0);checks.push('真实零余额账户与空账本，不注入展示资金');
 const key=randomUUID(),reason='本地零余额账本核对验收';
 const first=await api(path+'/reconcile',{reason},token,{'Idempotency-Key':key}),retry=await api(path+'/reconcile',{reason},token,{'Idempotency-Key':key});assert.equal(first.code,200);assert.equal(first.data.result.report.status,'MATCH');assert.equal(first.data.operationId,retry.data.operationId);
 assert.equal(sql(`SELECT COUNT(*) FROM wallet_reconciliation WHERE owner_type='PERSONAL' AND owner_id=${player.uid}`),'1');checks.push('重复核对仅一条结果与审计，账实相符');
 assert.equal((await api(path+'?start=2026-01-01&end=2026-03-01',undefined,token)).code,400);checks.push('超过 31 天的流水查询被拒绝');
 for(const [ap,c,fields] of [['role','exchangeCard',{number:1}],['role','exchangeDiamond',{number:1}],['cfg','getExchangeRate',{}],['club','transferToClub',{club_id:100001,card:1,diamond:1}],['club','exchangeDiamond',{club_id:100001,number:1}]])assert.equal((await player.send(ap,c,fields)).state,-18);
 checks.push('未知兑换/划转规则明确拒绝，不伪造汇率或扣入资产');
 assert.equal(sql(`SELECT SUM(balance+frozen) FROM wallet_account WHERE uid=${player.uid}`),'0');assert.equal(sql(`SELECT COUNT(*) FROM wallet_ledger WHERE owner_type='PERSONAL' AND owner_id=${player.uid}`),'0');checks.push('所有核对和拒绝操作后余额仍零且无虚构流水');
}finally{player.client.close();}
await writeFile(new URL('../docs/evidence/wallet-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,checks,result:'PASS'},null,2)+'\n');console.log(`钱包 HTTP/WS/Admin ${checks.length} 项通过`);
