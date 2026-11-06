import assert from 'node:assert/strict';
import {once} from 'node:events';
import net from 'node:net';
import WebSocket from 'ws';
import {writeFile} from 'node:fs/promises';
import {adminLogin,api,base} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地传输验收');
const checks=[],token=await adminLogin();
const open=async()=>{const ws=new WebSocket(base.replace(/^http/,'ws')+'/',{origin:new URL(base).origin});ws.on('error',()=>{});await once(ws,'open');return ws;};
const ws=await open();
try{
 const responses=[];const received=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('普通响应超时')),8000);ws.on('message',raw=>{responses.push(JSON.parse(raw));if(responses.length===32){clearTimeout(timer);resolve();}});});
 for(let i=0;i<32;i++)ws.send('{');await received;assert.equal(responses.length,32);assert.ok(responses.every(row=>row.state===-4&&!row.cmd));
 const metric=await api('/actuator/metrics/game.inbound.bytes',undefined,token);assert.equal(metric.measurements[0].value,0);checks.push('32 个流水解析失败各一条普通响应，处理后全局入站预算回零');
}finally{ws.close();}
const binary=await open();const closed=once(binary,'close');binary.send(Buffer.from([1,2,3]));assert.equal((await closed)[0],1003);checks.push('二进制业务帧按协议拒绝关闭');
const forbidden=new WebSocket(base.replace(/^http/,'ws')+'/',{origin:'http://not-allowed.invalid'});forbidden.on('error',()=>{});
await new Promise((resolve,reject)=>{forbidden.once('open',()=>reject(Error('非法 Origin 被接受')));forbidden.once('unexpected-response',(_,response)=>{assert.equal(response.statusCode,403);response.resume();forbidden.terminate();resolve();});});checks.push('统一代理入口拒绝非白名单 Origin');
const raw=net.connect({host:'127.0.0.1',port:8081});raw.on('error',()=>{});await once(raw,'connect');const started=performance.now();
try{const ended=once(raw,'close',{signal:AbortSignal.timeout(15000)});raw.write('GET / HTTP/1.1\r\nHost: localhost\r\n');await ended;const elapsed=performance.now()-started;assert.ok(elapsed>=9000&&elapsed<15000);checks.push('慢 HTTP 头从 TCP 接入起约 10 秒关闭，不绕过握手期限');}finally{raw.destroy();}
await writeFile(new URL('../docs/evidence/transport-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,checks,result:'PASS'},null,2)+'\n');console.log(`传输资源 HTTP/WS ${checks.length} 项通过`);
