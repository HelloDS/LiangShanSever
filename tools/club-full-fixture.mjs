import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {setTimeout as delay} from 'node:timers/promises';
import {base,guest,socket,sql} from './test-client.mjs';

assert.equal(process.env.RUN_CLUB_FULL_FIXTURE,'1','需明确启用本地亲友圈验收');
assert.ok(['localhost','127.0.0.1'].includes(new URL(base).hostname));
const state=JSON.parse(await readFile(new URL('../.local/club-full-browser-state.json',import.meta.url),'utf8'));
assert.ok(Number.isSafeInteger(state.uid)&&Number.isSafeInteger(state.clubId));
assert.equal(sql(`SELECT owner_uid FROM club WHERE id=${state.clubId} AND is_test=TRUE`),String(state.uid));
const file=new URL('../.local/club-full-applicants.json',import.meta.url);

if(process.argv[2]==='prepare'){
    const fixtures=[];
    await writeFile(file,'[]\n',{flag:'wx',mode:0o600});
    for(let index=0;index<5;index++){
        const player=await guest({test:true});
        try{
            fixtures.push({uid:player.uid,token:player.token});
            await writeFile(file,JSON.stringify(fixtures)+'\n',{mode:0o600});
            assert.equal((await player.send('club','apply',{id:state.clubId})).state,0);
        }finally{player.client.close();}
    }
    console.log('五名实际测试角色已提交入圈申请：'+fixtures.map(row=>row.uid).join(', '));
}else if(process.argv[2]==='inspect'){
    console.log('实际成员角色：\n'+sql(`SELECT uid,member_role FROM club_member WHERE club_id=${state.clubId} ORDER BY uid`));
    console.log('圈资产：\n'+sql(`SELECT currency,balance,frozen FROM club_wallet_account WHERE club_id=${state.clubId} ORDER BY currency`));
    console.log('已提交操作：\n'+sql(`SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.state')),COUNT(*) FROM player_operation WHERE actor_uid=${state.uid} GROUP BY 1`));
}else if(process.argv[2]==='play'){
    const code=process.argv[3];
    assert.match(code,/^[0-9]{6}$/);
    assert.equal(sql(`SELECT COUNT(*) FROM room_record WHERE room_code='${code}' AND club_id=${state.clubId} AND status='WAITING'`),'1');
    const fixtures=JSON.parse(await readFile(file,'utf8')).slice(3,5);
    assert.equal(fixtures.length,2);
    const companions=[];
    let finished;
    try {
        for(const fixture of fixtures){
            assert.ok(Number.isSafeInteger(fixture.uid));
            const client=await socket();
            const companion={client,uid:fixture.uid};
            companions.push(companion);
            const login=await client.send('login',{api:'user',c:'reg',account:'tk_'+fixture.token,plat_id:'2'});
            assert.equal(login.state,0);
            assert.equal(Number(login.loginData.role.uid),fixture.uid);
            companion.send=(api,c,fields={})=>client.send('logic',{api,c,...fields,sessionToken:login.sessionToken});
            assert.equal((await companion.send('room','joinRoom',{room_id:code})).state,0);
            assert.equal((await companion.send('game','checkGameCreate')).state,0);
            client.ws.on('message',raw=>{
                const packet=JSON.parse(raw);
                if(packet.cmd==='gameMessage')companion.view=packet;
            });
        }
        for(const companion of companions){
            const ready=await companion.send('room','gameMessage',{tag:'ready'});
            assert.ok(ready.state===0||ready.msg==='玩家已经准备');
        }
        console.log('陪测角色均已准备，等待预览页玩家操作');
        const deadline=Date.now()+10*60_000;
        let revision=-1;
        while(Date.now()<deadline){
            await delay(300);
            const view=companions[0].view;
            if(!view||view.room_revision<=revision)continue;
            const {game}=view;
            if(!game)continue;
            if(game.currentState===0&&game.currentCount>1){finished=game;break;}
            const companion=companions.find(person=>person.uid===Number(game.currentUid));
            if(!companion||companion.view?.room_revision!==view.room_revision)continue;
            let action;
            if(game.currentState===1)action={tag:'score',score:3};
            else if(game.currentState===2){
                if(game.outCards)action={tag:'buchu'};
                else {
                    const cards=companion.view.cardsInfo[companion.uid];
                    assert.ok(Array.isArray(cards)&&cards.length);
                    const group=cards.filter(card=>card[1]===cards[0][1]);
                    action={tag:'chupai',outCards:group,opCardType:group.length===4?12:group.length};
                }
            }
            if(!action)continue;
            revision=view.room_revision;
            const result=await companion.send('room','gameMessage',action);
            assert.equal(result.state,0,JSON.stringify({action:action.tag,state:result.state,msg:result.msg}));
        }
        assert.ok(finished,'没有收到首局终局');
        assert.equal(Object.values(finished.money).reduce((sum,value)=>sum+value,0),0);
        console.log(JSON.stringify({roomCode:code,round:finished.currentCount-1,scores:finished.money}));
    }finally{for(const companion of companions)companion.client.close();}
}else throw Error('支持 prepare、inspect 或 play <房号>');
