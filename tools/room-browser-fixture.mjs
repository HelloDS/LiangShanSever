/** 临时复用已停用的本地 QA 账号，清理时恢复原菜单并撤回范围。 */
import assert from 'node:assert/strict';
import {readFile,writeFile,unlink} from 'node:fs/promises';
import {base,sql,api} from './test-client.mjs';
if(process.env.RUN_ROOM_BROWSER_FIXTURE!=='1'||!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw Error('需要显式启用本地浏览器夹具');
const fixture=JSON.parse(await readFile(new URL('../.local/report-browser-test.json',import.meta.url),'utf8'));
assert.match(String(fixture.userId),/^[1-9][0-9]{2,8}$/);assert.match(String(fixture.roleId),/^[1-9][0-9]{2,8}$/);
assert.match(fixture.userName,/^qa_support_[a-z0-9_]+$/);
const userId=Number(fixture.userId),roleId=Number(fixture.roleId),statePath=new URL('../.local/room-browser-state.json',import.meta.url),mode=process.argv[2];
const kind=process.argv[3]||'rooms';if(!['rooms','reconciliation','audit','dashboard'].includes(kind))throw Error('仅支持房间、账本核对、业务审计或看板只读夹具');
const menu={rooms:3008,reconciliation:3009,audit:3031,dashboard:3013}[kind];
assert.equal(sql(`SELECT user_name FROM sys_user WHERE user_id=${userId}`),fixture.userName);
if(mode==='prepare'){
 assert.equal(sql(`SELECT status FROM sys_user WHERE user_id=${userId}`),'1');
 assert.equal(sql(`SELECT status FROM sys_role WHERE role_id=${roleId}`),'1');
 assert.equal(sql(`SELECT COUNT(*) FROM admin_data_scope WHERE principal_id=${userId}`),'0');
 const menus=sql(`SELECT menu_id FROM sys_role_menu WHERE role_id=${roleId}`).split('\n').filter(Boolean).map(Number);
 await writeFile(statePath,JSON.stringify({userId,roleId,menus,kind}),{mode:0o600,flag:'wx'});
 sql(`UPDATE sys_user SET status='0' WHERE user_id=${userId};UPDATE sys_role SET status='0' WHERE role_id=${roleId};DELETE FROM sys_role_menu WHERE role_id=${roleId};INSERT INTO sys_role_menu(role_id,menu_id) VALUES(${roleId},3000),(${roleId},${menu});INSERT INTO admin_data_scope(principal_id,scope_type,scope_id) VALUES(${userId},'PLATFORM','*')`);
 const login=await api('/login',{username:fixture.userName,password:fixture.password});assert.equal(login.code,200);
 const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
 assert.equal((await api(`/game-admin/${kind}?start=${today}&end=${today}`,undefined,login.token)).code,200);
 assert.equal((await api(kind==='dashboard'?'/game-admin/players':'/game-admin/dashboard',undefined,login.token)).code,403);
 console.log('仅'+({rooms:'房间',reconciliation:'账本核对',audit:'业务审计',dashboard:'运营看板'}[kind])+'查询的临时 QA 已启用：'+fixture.userName);
}else if(mode==='revoke'){
 const state=JSON.parse(await readFile(statePath,'utf8'));assert.equal(state.userId,userId);assert.equal(state.roleId,roleId);
 sql(`DELETE FROM admin_data_scope WHERE principal_id=${userId}`);console.log('临时 QA 数据范围已撤回，保留菜单用于下一请求拒绝验收');
}else if(mode==='cleanup'){
 const state=JSON.parse(await readFile(statePath,'utf8'));assert.equal(state.userId,userId);assert.equal(state.roleId,roleId);assert.ok(state.menus.every(Number.isSafeInteger));
 sql(`UPDATE sys_user SET status='1' WHERE user_id=${userId};UPDATE sys_role SET status='1' WHERE role_id=${roleId};DELETE FROM admin_data_scope WHERE principal_id=${userId};DELETE FROM sys_role_menu WHERE role_id=${roleId}`);
 if(state.menus.length)sql('INSERT INTO sys_role_menu(role_id,menu_id) VALUES '+state.menus.map(menu=>`(${roleId},${menu})`).join(','));
 assert.equal(sql(`SELECT status FROM sys_user WHERE user_id=${userId}`),'1');assert.equal(sql(`SELECT COUNT(*) FROM admin_data_scope WHERE principal_id=${userId}`),'0');
 await unlink(statePath);console.log('临时 QA 已停用、范围已撤回、原菜单已恢复');
}else throw Error('仅支持 prepare、revoke 或 cleanup');
