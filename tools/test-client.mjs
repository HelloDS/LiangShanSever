import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { once } from 'node:events';
import { execFileSync } from 'node:child_process';
import {createHash} from 'node:crypto';
import WebSocket from 'ws';

export const base=process.env.GAME_BASE_URL||'http://localhost:8080';
export const environment=Object.fromEntries((await readFile(new URL('../.local/local.env',import.meta.url),'utf8')).trim().split('\n').map(line=>line.split('=')));
export async function api(path,body,token,extraHeaders={}) {
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{...(body!==undefined?{'Content-Type':'application/json'}:{}),...(token?{Authorization:'Bearer '+token}:{}),...extraHeaders},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(8000)});
  assert.equal(response.status,200,`HTTP ${path}: ${response.status}`);
  return response.json();
}
export async function adminLogin(){const result=await api('/login',{username:'admin',password:environment.ADMIN_INITIAL_PASSWORD});assert.equal(result.code,200);return result.token;}
export async function socket(){
  const ws=new WebSocket(base.replace(/^http/,'ws')+'/',{origin:new URL(base).origin});await once(ws,'open');
  const waiters=[];
  ws.on('message',raw=>{const response=JSON.parse(raw);if(response.cmd)return;const waiter=waiters.shift();if(waiter){clearTimeout(waiter.timer);waiter.resolve(response);}else throw new Error('多余普通响应');});
  ws.on('close',()=>{for(const waiter of waiters.splice(0)){clearTimeout(waiter.timer);waiter.reject(new Error('连接已关闭'));}});
  return {ws,send(path,data){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('WS 响应超时')),8000);waiters.push({resolve,reject,timer});ws.send(JSON.stringify({path,reqData:data}));});},close(){ws.close();}};
}
export async function guest({test=false}={}){
  const identity=await api('/api/auth/guest',{});assert.equal(identity.state,0);
  if(test){
    // 登录连接和分钟在线采样之前标记，不能等登录后再追认成测试身份。
    const hash=createHash('sha256').update(identity.token).digest('hex');
    const uid=Number(sql(`SELECT r.uid FROM guest_credential g JOIN player_role r ON r.account_id=g.account_id WHERE g.token_hash='${hash}'`));
    assert.ok(Number.isSafeInteger(uid)&&uid>0);markTest(uid);
  }
  const client=await socket();const reg=await client.send('login',{api:'user',c:'reg',account:'tk_'+identity.token,plat_id:'2'});assert.equal(reg.state,0);
  return {token:identity.token,session:reg.sessionToken,uid:reg.loginData.role.uid,client,reg,send(api,c,fields={}){return client.send('logic',{api,c,...fields,sessionToken:reg.sessionToken});}};
}
export function sql(query){return execFileSync('docker',['exec','liangshan-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 --batch --skip-column-names -u"$MYSQL_USER" "$MYSQL_DATABASE" -e "$1"','mysql-test',query],{encoding:'utf8'}).trim();}
export function markTest(uid){assert.match(String(uid),/^\d+$/);sql(`UPDATE user_account a JOIN player_role r ON r.account_id=a.id SET a.is_test=TRUE WHERE r.uid=${uid}`);}
