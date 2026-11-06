/** 本机真实 WS 大厅负载；不代表牌局动作、TLS 或结算容量。 */
import assert from 'node:assert/strict';
import {readFile,writeFile,access} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import os from 'node:os';
import {createHash} from 'node:crypto';
import WebSocket from 'ws';
import {base,adminLogin,api,sql} from './test-client.mjs';

if(process.env.RUN_LOBBY_LOAD!=='1'||!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许显式启用的本地大厅负载');
const count=Number(process.env.LOAD_CONNECTIONS||1000),active=Number(process.env.LOAD_ACTIVE||200);
const seconds=Number(process.env.LOAD_SECONDS||300),rate=Number(process.env.LOAD_RATE||200);
assert.ok(Number.isInteger(count)&&count>=1&&count<=1500);
assert.ok(Number.isInteger(active)&&active>=1&&active<=count);
assert.ok(Number.isInteger(seconds)&&seconds>=10&&seconds<=3600);
assert.ok(Number.isInteger(rate)&&rate>=1&&rate<=500);
const reportName=process.env.LOAD_REPORT||'lobby-load.json';
assert.match(reportName,/^lobby-load(?:-[a-z0-9-]+)?\.json$/,'报告名称无效');
const reportFile=new URL('../docs/evidence/'+reportName,import.meta.url);
try{await access(reportFile);throw Error('保留既有负载结果，请设置新的 LOAD_REPORT 名称');}catch(error){if(error.code!=='ENOENT')throw error;}
const fixtures=(await readFile(new URL('../.local/load-guests.jsonl',import.meta.url),'utf8')).trim().split('\n').map(line=>{try{return JSON.parse(line);}catch{throw Error('测试凭据文件格式损坏');}}).slice(0,count);
assert.equal(fixtures.length,count);
for(const row of fixtures)assert.ok(Number.isSafeInteger(row.uid)&&/^[A-Za-z0-9_-]{43}$/.test(row.token),'测试凭据格式无效');
assert.equal(Number(sql(`SELECT COUNT(*) FROM player_role r JOIN user_account a ON a.id=r.account_id WHERE a.is_test=TRUE AND a.status='ACTIVE' AND r.uid IN (${fixtures.map(row=>row.uid).join(',')})`)),count,'只能使用已标记的测试账号');
const token=await adminLogin(),run=promisify(execFile);
const serverPid=(await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim();
assert.ok(/^[0-9]+$/.test(serverPid));
const buildCommit=(await run('git',['rev-parse','HEAD'])).stdout.trim();
const javaVersion=(await run('jcmd',[serverPid,'VM.version'])).stdout.trim();
const artifactSha256=createHash('sha256').update(await readFile(new URL('../ruoyi-admin/target/ruoyi-admin.jar',import.meta.url))).digest('hex');
const dockerContext=(await run('docker',['context','show'])).stdout.trim();
let portForwarder='unrecorded';
if(dockerContext==='colima'){try{portForwarder=/^portForwarder:\s*(ssh|grpc|none)\s*$/m.exec(await readFile(os.homedir()+'/.colima/default/colima.yaml','utf8'))?.[1]||'unrecorded';}catch{}}
const proxyProbe=await fetch(base+'/actuator/health',{signal:AbortSignal.timeout(3000)});assert.equal(proxyProbe.status,200);
let proxy;
if(proxyProbe.headers.get('x-liangshan-proxy')==='host-native'){
 const masterPid=(await readFile(new URL('../.local/nginx-native/nginx.pid',import.meta.url),'utf8')).trim();assert.match(masterPid,/^[0-9]+$/);
 const processArgs=(await run('ps',['-ww','-p',masterPid,'-o','args='])).stdout;assert.ok(processArgs.includes('deploy/nginx-host.conf'));
 const binary=(await run('which',['nginx'])).stdout.trim();const version=(await run(binary,['-v'])).stderr.trim();
 proxy={mode:'host-native',version,masterPid,launchNofile:Number((await readFile(new URL('../.local/nginx-native/nofile',import.meta.url),'utf8')).trim()),
  binarySha256:createHash('sha256').update(await readFile(binary)).digest('hex'),dockerContext,portForwarder,
  configSha256:createHash('sha256').update(await readFile(new URL('../deploy/nginx-host.conf',import.meta.url))).digest('hex')};
}else proxy={mode:'docker',image:(await run('docker',['inspect','--format','{{.Image}}','liangshan-nginx-1'])).stdout.trim(),
 ulimits:JSON.parse((await run('docker',['inspect','--format','{{json .HostConfig.Ulimits}}','liangshan-nginx-1'])).stdout),
 dockerContext,portForwarder,configSha256:createHash('sha256').update(await readFile(new URL('../deploy/nginx.conf',import.meta.url))).digest('hex')};
const engine=(await run('docker',['info','--format','{{.NCPU}} {{.MemTotal}} {{.ServerVersion}}'])).stdout.trim();
const mysqlDurability=sql('SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin').split('\t').map(Number);
const clients=[],latencies=[],errors={},samples=[],socketFailures=[],connectionLatencies=[];
let phase='connecting',sent=0,completed=0,pushes=0,closed=0,busy=0,missed=0,pings=0,pongs=0;
let stopped=false;process.once('SIGINT',()=>{stopped=true;});process.once('SIGTERM',()=>{stopped=true;});
const fail=code=>{errors[code]=(errors[code]||0)+1;};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const routes=[{api:'role',c:'syncCard'},{api:'club',c:'getClubs'},{api:'manager',c:'getNotices'},{api:'cfg',c:'getGame',game_id:'6003'}];
async function connect(fixture){
 const began=performance.now();
 const ws=new WebSocket(base.replace(/^http/,'ws')+'/',{origin:new URL(base).origin,handshakeTimeout:10000,autoPong:false});
 const client={ws,pending:null,session:null};clients.push(client);
 ws.on('error',error=>{if(phase!=='closing'){fail('socket_error');if(socketFailures.length<20)socketFailures.push({phase,detail:detail(error)});}});
 ws.on('ping',data=>{if(phase==='closing')return;pings++;ws.pong(data,true,error=>{if(error){if(phase!=='closing')fail('heartbeat_write');}else pongs++;});});
 ws.on('close',()=>{if(phase!=='closing')closed++;if(client.pending){clearTimeout(client.pending.timer);client.pending.reject(Error('closed'));client.pending=null;}});
 ws.on('message',raw=>{
  let packet;try{packet=JSON.parse(raw);}catch{fail('invalid_json');return;}
  if(packet.cmd){pushes++;return;}
  const waiter=client.pending;client.pending=null;
  if(!waiter){fail('extra_ack');return;}
  clearTimeout(waiter.timer);waiter.resolve(packet);
 });
 await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',error=>reject(Error('connect',{cause:error})));});
 const login=await request(client,'login',{api:'user',c:'reg',account:'tk_'+fixture.token,plat_id:'2'});
 assert.equal(login.state,0,'测试游客登录失败');assert.equal(login.loginData.role.uid,fixture.uid);
 assert.ok(/^[A-Za-z0-9_-]{43}$/.test(login.sessionToken),'会话格式无效');client.session=login.sessionToken;connectionLatencies.push(performance.now()-began);
 return client;
}
function request(client,path,reqData){
 return new Promise((resolve,reject)=>{
  if(client.pending||client.ws.readyState!==WebSocket.OPEN){reject(Error('not_ready'));return;}
  const waiter={resolve,reject,timer:setTimeout(()=>{if(client.pending===waiter)client.pending=null;client.ws.terminate();reject(Error('timeout'));},5000)};
  client.pending=waiter;
  client.ws.send(JSON.stringify({path,reqData}),error=>{if(error&&client.pending===waiter){clearTimeout(waiter.timer);client.pending=null;reject(Error('write'));}});
 });
}
const metricNames=['game.accepted.connections','game.authenticated.connections','game.inbound.bytes','game.business.queue','game.business.active','game.reply.pending','game.reply.active','game.reply.queue','game.push.pending','jvm.memory.used','process.cpu.usage'];
async function sample(){
 const entries=[];for(let offset=0;offset<metricNames.length;offset+=8)entries.push(...await Promise.all(metricNames.slice(offset,offset+8).map(async name=>{
  const response=await fetch(base+'/actuator/metrics/'+name,{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(3000)});
  assert.equal(response.status,200,`HTTP /actuator/metrics/${name}: ${response.status}`);
  const result=await response.json();
  if(!Array.isArray(result.measurements))throw Error('指标结构无效');
  return [name,result.measurements.map(row=>({statistic:row.statistic,value:row.value}))];
 })));
 const recent=latencies.slice(-1000).sort((a,b)=>a-b);
 samples.push({at:new Date().toISOString(),phase,sent,completed,closed,pings,pongs,recentP95Ms:recent.length?Number(recent[Math.ceil(recent.length*.95)-1].toFixed(3)):null,metrics:Object.fromEntries(entries)});
}
function detail(error){
 const upgrade=/^Unexpected server response: ([0-9]{3})$/.exec(String(error?.message||error?.cause?.message));if(upgrade)return 'WS_UPGRADE_HTTP_'+upgrade[1];
 const nestedUpgrade=/^Unexpected server response: ([0-9]{3})$/.exec(String(error?.cause?.message));if(nestedUpgrade)return 'WS_UPGRADE_HTTP_'+nestedUpgrade[1];
 const code=error?.code||error?.cause?.code;
 return /^HTTP \/actuator\/metrics\/[A-Za-z0-9.]+: [0-9]{3}/.exec(String(error?.message))?.[0]||String(error?.name||'Error')+(/^[A-Z0-9_]{1,40}$/.test(String(code))?':'+String(code):'');
}
let sampling=null,measurementStart=null,fatalDetail=null;
let startedAt,endedAt,measurementMs=0,fatal=false;
try{
 for(let i=0;i<count&&!stopped;i++){
  const start=performance.now();await connect(fixtures[i]);
  if((i+1)%100===0)console.log(`已认证 ${i+1}/${count} 个真实 WS 连接`);
  await sleep(Math.max(0,50-(performance.now()-start)));
 }
 if(stopped)throw Error('interrupted');
 phase='warmup';await sleep(3000);await sample();
 phase='measurement';startedAt=new Date().toISOString();const start=performance.now();measurementStart=start;
 let nextSample=start+5000,nextReport=start+30000,sequence=0;
 while(!stopped&&performance.now()-start<seconds*1000){
  const now=performance.now(),due=Math.min(seconds*rate,Math.floor((now-start)*rate/1000));
  // 调度落后超过一秒时记录遗漏，不通过无界补发队列掩盖负载机瓶颈。
  if(due-sequence>rate){missed+=due-sequence-rate;sequence=due-rate;}
  while(sequence<due){
   const index=sequence++,client=clients[index%active],route=routes[Math.floor(index/active)%routes.length];
   if(client.pending||client.ws.readyState!==WebSocket.OPEN){busy++;continue;}
   sent++;const began=performance.now();
   request(client,'logic',{...route,sessionToken:client.session}).then(packet=>{
    if(packet.state!==0){fail('business_'+String(packet.state));return;}
    if(route.c==='syncCard'&&(packet.card!==0||packet.diamond!==0)){fail('unexpected_balance');return;}
    latencies.push(performance.now()-began);completed++;
   }).catch(()=>fail('request_failed'));
  }
  if(now>=nextSample){if(!sampling)sampling=sample().catch(error=>{fail('metrics_failed');fatalDetail=detail(error);}).finally(()=>{sampling=null;});nextSample+=5000;}
  if(now>=nextReport){console.log(`测量 ${Math.floor((now-start)/1000)} 秒：发送 ${sent}，完成 ${completed}，断开 ${closed}，错误 ${Object.values(errors).reduce((a,b)=>a+b,0)}，最近查询 P95 ${samples.at(-1)?.recentP95Ms??'未知'}ms`);nextReport+=30000;}
  await sleep(2);
 }
 measurementMs=performance.now()-start;endedAt=new Date().toISOString();
 phase='drain';if(sampling)await sampling;for(let i=0;i<60&&clients.some(client=>client.pending);i++)await sleep(100);
 await sample();
}catch(error){fatal=true;fatalDetail=detail(error);if(measurementStart!==null&&measurementMs===0){measurementMs=performance.now()-measurementStart;endedAt=new Date().toISOString();}fail(stopped?'interrupted':'setup_or_metrics_failed');console.error(`负载流程失败：阶段 ${phase}，${fatalDetail}`);}
finally{
 phase='closing';for(const client of clients)client.ws.close();
 await sleep(500);for(const client of clients)if(client.ws.readyState!==WebSocket.CLOSED)client.ws.terminate();
}
latencies.sort((a,b)=>a-b);
const percentile=p=>latencies.length?Number(latencies[Math.min(latencies.length-1,Math.ceil(latencies.length*p)-1)].toFixed(3)):null;
const totalErrors=Object.values(errors).reduce((a,b)=>a+b,0);
connectionLatencies.sort((a,b)=>a-b);const connectPercentile=p=>connectionLatencies.length?Number(connectionLatencies[Math.min(connectionLatencies.length-1,Math.ceil(connectionLatencies.length*p)-1)].toFixed(3)):null;
const result={executedAt:new Date().toISOString(),scope:'本机明文 WS 大厅查询；无牌局动作、结算、TLS 或 24 小时稳定性结论',base,
 environment:{platform:os.platform(),arch:os.arch(),cpus:os.cpus().length,cpu:os.cpus()[0]?.model,totalMemoryBytes:os.totalmem(),node:process.version,
 java:javaVersion,git:buildCommit,artifactSha256,proxy,dockerCpuMemoryVersion:engine,mysqlDurability:{innodbFlushLogAtTrxCommit:mysqlDurability[0],syncBinlog:mysqlDurability[1],logBin:mysqlDurability[2]}},
 requested:{connections:count,active,idle:count-active,seconds,queriesPerSecond:rate,routes},startedAt,endedAt,measurementMs:Number(measurementMs.toFixed(3)),
 fatalDetail,socketFailures,hostCompetition:process.env.HOST_LOAD_COMPETITION||'none',connectionLatencyMs:{p50:connectPercentile(.5),p95:connectPercentile(.95),max:connectPercentile(1)},actual:{authenticated:clients.filter(client=>client.session).length,sent,completed,closed,pushes,busy,missed,heartbeats:{receivedPings:pings,sentPongs:pongs},errors,throughputPerSecond:measurementMs?Number((completed*1000/measurementMs).toFixed(3)):0,
 latencyMs:{p50:percentile(.5),p95:percentile(.95),p99:percentile(.99),max:percentile(1)}},samples,
 result:pongs===pings&&(seconds<30||pings>=count-active)&&samples.every(sample=>sample.metrics['game.authenticated.connections'][0]?.value>=count)&&percentile(.95)!==null&&percentile(.95)<=300&&completed>=seconds*rate*.99&&!fatal&&!stopped&&closed===0&&totalErrors===0&&busy===0&&missed===0&&completed===sent&&clients.length===count&&measurementMs>=seconds*1000?'PASS':'FAIL'};
await writeFile(reportFile,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({result:result.result,...result.actual,samples:samples.length}));
if(result.result!=='PASS')process.exitCode=1;
