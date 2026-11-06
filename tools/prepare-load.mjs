/** 本地容量验收的真实游客准备；遵循正常认证限流，不调用旧站点。 */
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {open,readFile,appendFile,chmod,unlink} from 'node:fs/promises';
import {base,api,sql,markTest} from './test-client.mjs';
if(process.env.RUN_LOAD_FIXTURES!=='1'||!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅在明确启用的本地负载夹具流程中运行');
const count=Number(process.argv[2]||1000);assert.ok(Number.isSafeInteger(count)&&count>0&&count<=2000);
const file=new URL('../.local/load-guests.jsonl',import.meta.url),lock=new URL('../.local/load-prepare.lock',import.meta.url);
const lease=await open(lock,'wx',0o600);await lease.writeFile(String(process.pid));
try{
 let prepared=0;
 try{const contents=await readFile(file,'utf8');for(const line of contents.trim().split('\n').filter(Boolean)){const fixture=JSON.parse(line);assert.match(fixture.token,/^[A-Za-z0-9_-]{43}$/);assert.ok(Number.isSafeInteger(fixture.uid));prepared++;}}catch(e){if(e.code!=='ENOENT')throw e;}
 if(prepared>0)await chmod(file,0o600);
 while(prepared<count){
  const result=await api('/api/auth/guest',{});
  if(result.state===-4){await new Promise(resolve=>setTimeout(resolve,5000));continue;}
  assert.equal(result.state,0);assert.match(result.token,/^[A-Za-z0-9_-]{43}$/);
  const hash=createHash('sha256').update(result.token).digest('hex');
  const uid=Number(sql(`SELECT r.uid FROM guest_credential g JOIN player_role r ON r.account_id=g.account_id WHERE g.token_hash='${hash}'`));assert.ok(uid>0);markTest(uid);
  await appendFile(file,JSON.stringify({uid,token:result.token})+'\n',{mode:0o600});prepared++;
  if(prepared%50===0||prepared===count)console.log(`已准备 ${prepared}/${count} 个真实测试游客，未计入运营统计`);
  await new Promise(resolve=>setTimeout(resolve,1250));
 }
}finally{await lease.close();await unlink(lock);}
