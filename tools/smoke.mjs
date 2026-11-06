import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { once } from 'node:events';
import WebSocket from 'ws';

const base = process.env.GAME_BASE_URL || 'http://localhost:8080';
const env = Object.fromEntries((await readFile(new URL('../.local/local.env',import.meta.url),'utf8')).trim().split('\n').map(line=>line.split('=')));
const checks = [];
async function json(path,body,token) {
  const response = await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{'Authorization':'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  assert.equal(response.status,200,`HTTP ${path}: ${response.status}`);
  return response.json();
}
function check(name) { checks.push(name); console.log('通过：'+name); }
async function socket() {
  const ws=new WebSocket(base.replace(/^http/,'ws')+'/',{origin:new URL(base).origin});
  await once(ws,'open');
  const responses=[];
  const waiters=[];
  ws.on('message',message=>{const packet=JSON.parse(message);assert.ok(!packet.cmd);const waiter=waiters.shift();if(waiter)waiter(packet);else responses.push(packet);});
  const next=()=>new Promise((resolve,reject)=>{if(responses.length)return resolve(responses.shift());const timer=setTimeout(()=>reject(new Error('响应超时')),5000);waiters.push(packet=>{clearTimeout(timer);resolve(packet);});});
  return {ws,next,send(path,data){ws.send(JSON.stringify({path,reqData:data}));return next();}};
}
const config=await json('/api/auth/config');
assert.deepEqual(config,{state:0,guest:true,wx:false,sms:false});check('HTTP 游戏专用 JSON 字段与布尔类型');
const guest=await json('/api/auth/guest',{});assert.equal(guest.state,0);assert.match(guest.token,/^[A-Za-z0-9_-]{43}$/);
assert.equal((await json('/api/auth/check',{token:guest.token})).state,0);check('真实游客创建及持久凭据校验');
const first=await socket();
const reg=await first.send('login',{api:'user',c:'reg',account:'tk_'+guest.token,plat_id:'2'});
assert.equal(reg.state,0);assert.ok(Number.isInteger(reg.loginData.role.uid));assert.ok(Array.isArray(reg.loginData.notices));assert.ok(Array.isArray(reg.loginData.paoMaDeng));
const uid=reg.loginData.role.uid;
assert.equal(reg.loginData.role.card,0);assert.equal(reg.loginData.role.diamond,0);check('WS 注册与数据库真实角色/零余额');
first.ws.send(JSON.stringify({path:'logic',reqData:{api:'role',c:'syncCard',sessionToken:reg.sessionToken}}));
first.ws.send(JSON.stringify({path:'logic',reqData:{api:'role',c:'getRoleInfo',targetUid:uid,sessionToken:reg.sessionToken}}));
assert.deepEqual(await first.next(),{state:0,card:0,diamond:0});
const info=await first.next();assert.equal(info.role.uid,uid);assert.ok(!('card' in info.role));check('同连接流水请求 FIFO、一次响应及公开资料裁剪');
const closed=once(first.ws,'close');
const second=await socket();
assert.equal((await second.send('login',{api:'user',c:'resetfd',sessionToken:reg.sessionToken})).state,0);
await closed;check('resetfd 新连接替换旧连接');
const admin=await json('/login',{username:'admin',password:env.ADMIN_INITIAL_PASSWORD});
assert.equal(admin.code,200);assert.ok(admin.token);
const adminInfo=await json('/getInfo',undefined,admin.token);assert.equal(adminInfo.code,200);check('若依原生 JWT/Redis 管理员登录');
assert.equal((await json('/getInfo',undefined,reg.sessionToken)).code,401);check('游戏令牌不能用于后台认证');
for(const path of ['/admin/','/admin/login','/admin/system/user']) {
 const response=await fetch(base+path,{headers:{Accept:'text/html'}});assert.equal(response.status,200,path);assert.ok((await response.text()).includes('/admin/'));
}check('Admin 子路径及刷新回退');
assert.equal((await fetch(base+'/admin/missing.js')).status,404);check('不存在的静态文件不被页面回退吞掉');
const third=await socket();
const again=await third.send('login',{api:'user',c:'reg',account:'tk_'+guest.token,plat_id:'2'});assert.equal(again.loginData.role.uid,uid);check('同游客凭据恢复同一 UID');
const loggedOut=once(third.ws,'close');
assert.equal((await json('/api/auth/logout',{token:guest.token})).state,0);await loggedOut;
assert.notEqual((await json('/api/auth/check',{token:guest.token})).state,0);check('退出撤销持久凭据、关联会话及连接');
const denied=new WebSocket(base.replace(/^http/,'ws')+'/',{origin:'https://untrusted.example'});
denied.on('error',()=>{});
const rejected=await once(denied,'unexpected-response');assert.equal(rejected[1].statusCode,403);rejected[1].resume();denied.terminate();check('WS 握手 Origin 拒绝');
await writeFile(new URL('../docs/evidence/initial-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,checks,result:'PASS'},null,2)+'\n');
console.log(`共 ${checks.length} 项通过。`);
