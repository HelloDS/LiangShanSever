/** 只验证真实空房间记录与权限；不注入房间、手牌、费用或结算。 */
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {base,api,adminLogin,guest,markTest,sql} from './test-client.mjs';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('仅允许本地房间基础设施验收');
const checks=[],check=name=>{checks.push(name);console.log('通过：'+name);};
const token=await adminLogin(),today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
const path=`/game-admin/rooms?start=${today}&end=${today}`;
const rooms=await api(path,undefined,token);assert.equal(rooms.code,200);assert.ok(Array.isArray(rooms.rows));
assert.equal(rooms.total,Number(sql(`SELECT COUNT(*) FROM room_record WHERE game_id='6003' AND created_at>=CONVERT_TZ('${today} 00:00:00','+08:00','+00:00') AND created_at<CONVERT_TZ('${today} 00:00:00','+08:00','+00:00')+INTERVAL 1 DAY`)));
assert.equal(rooms.total,0,'此脚本仅验收尚无运行房间的阶段，不以注入记录冒充对局');
check('后台按真实数据库返回空房间记录，不提供演示房间');
for(const suffix of ['&roomCode=123','&ownerUid=0','&status=BOGUS','&pageSize=201'])assert.equal((await api(path+suffix,undefined,token)).code,400);
assert.equal((await api('/game-admin/rooms?start=2026-01-01&end=2026-03-01',undefined,token)).code,400);
check('房号、玩家、状态、分页和日期边界均由服务端验证');
assert.equal((await api('/game-admin/rooms/00000000-0000-0000-0000-000000000001',undefined,token)).code,403);
check('不存在或无授权的永久实例不返回私有状态');
for(const [pool,maximum] of [['general',16],['room',8]]){
 const metric=await api('/actuator/metrics/game.sql.maximum?tag=pool:'+pool,undefined,token);assert.equal(metric.measurements[0].value,maximum);
}
check('部署后的普通与房间 Druid 池容量分别为 16 与 8');
const player=await guest();markTest(player.uid);
try{
 assert.equal((await api(path,undefined,player.session)).code,401);
 assert.equal((await player.send('room','createRoom',{game_id:'6003',createData:{cardType:4,playNumber:6,aa:true,groupid:0}})).state,-2);
 assert.equal(sql(`SELECT SUM(balance+frozen) FROM wallet_account WHERE uid=${player.uid}`),'0');
 assert.equal(sql('SELECT COUNT(*) FROM room_record'),'0');
 check('游戏会话不能读取管理房间，尚未开放的建房拒绝且不扣资产');
}finally{player.client.close();}
await writeFile(new URL('../docs/evidence/room-smoke.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),base,checks,result:'PASS'},null,2)+'\n');
console.log(`房间 HTTP/WS/Admin ${checks.length} 项通过。`);
