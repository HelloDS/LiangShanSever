import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {setTimeout as delay} from 'node:timers/promises';
import {base,guest,sql,api} from './test-client.mjs';

assert.equal(process.env.RUN_CLUB_ROOM_ACCEPTANCE,'1','需明确启用本地房间验收');
assert.ok(['localhost','127.0.0.1'].includes(new URL(base).hostname));
const people=[];
const report={startedAt:new Date().toISOString(),transport:'original-websocket',game:'6003',rounds:0,actions:0};
try {
    for(let index=0;index<3;index++)people.push(await guest({test:true}));
    const [owner,a,b]=people;
    const circle=await owner.send('club','createClub',{name:'线验'+String(owner.uid).slice(-5)});
    assert.equal(circle.state,0);const club=circle.club.id;report.club=club;report.players=people.map(person=>person.uid);
    for(const person of [a,b]) {
        assert.equal((await person.send('club','apply',{id:club})).state,0);
        assert.equal((await owner.send('club','reviewApply',{id:club,targetUid:person.uid,val:1})).state,0);
    }
    assert.equal((await owner.send('partner','addPartner',{club_id:club,uids:[a.uid]})).state,0);
    assert.equal((await owner.send('partner','addPartnerMember',{club_id:club,partner_uid:a.uid,member_uids:[b.uid],is_flush:0})).state,0);
    const operationId=randomUUID();
    const fields={game_id:'6003',createData:{club_id:club,playNumber:6,cardType:4,aa:false},operationId};
    const creation=await owner.send('room','createRoom',fields);assert.equal(creation.state,0);
    const code=creation.room.id;report.roomCode=code;const permanentKey=creation.room.room_key;assert.match(permanentKey,/^[0-9a-f-]{36}$/);
    const replay=await owner.send('room','createRoom',fields);assert.equal(replay.room.id,code);
    const latest=new Map(),revisions=new Map();
    for(const person of people)person.client.ws.on('message',raw=>{const packet=JSON.parse(raw);if(packet.cmd==='gameMessage')latest.set(person.uid,packet);});
    for(const person of people)assert.equal((await person.send('room','joinRoom',{room_id:code})).state,0);
    for(const person of people)assert.equal((await person.send('game','checkGameCreate')).state,0);
    async function action(person,fields) {
        const waiting=people.map(target=>new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>{target.client.ws.off('message',listen);reject(Error('未收到牌桌推送'));},8000);
            function listen(raw) {const packet=JSON.parse(raw);if(packet.cmd==='gameMessage' && packet.sender===person.uid && (packet.tag===fields.tag || fields.tag==='ready' && packet.tag==='startGame')){clearTimeout(timer);target.client.ws.off('message',listen);resolve(packet);}}
            target.client.ws.on('message',listen);
        }));
        const ack=await person.send('room','gameMessage',fields);assert.equal(ack.state,0,JSON.stringify(ack));
        const packets=await Promise.all(waiting);
        for(let index=0;index<people.length;index++) {
            const packet=packets[index],self=people[index].uid;
            assert.equal(packet.room_key,permanentKey);assert.ok(Number.isSafeInteger(packet.room_revision));assert.ok(packet.room_revision>(revisions.get(self)??-1));revisions.set(self,packet.room_revision);
            for(const other of people)if(other.uid!==self && packet.game.currentState!==0)assert.equal(typeof packet.cardsInfo[other.uid],'number','其他玩家手牌泄露');
            latest.set(self,packet);
        }
        report.actions++;
        await delay(15);
    }
    for(let round=1;round<=6;round++) {
        for(const person of people)await action(person,{tag:'ready'});
        let game=latest.get(owner.uid).game;
        assert.equal(game.currentState,1);
        await action(people.find(person=>person.uid===game.currentUid),{tag:'score',score:3});
        let count=0;
        while(true) {
            game=latest.get(owner.uid).game;if(game.currentState!==2)break;
            const person=people.find(target=>target.uid===game.currentUid);
            const view=latest.get(person.uid);
            if(view.game.outCards)await action(person,{tag:'buchu'});
            else {
                const cards=view.cardsInfo[person.uid];assert.ok(Array.isArray(cards)&&cards.length);
                await action(person,{tag:'chupai',outCards:[cards[0]],opCardType:1});
            }
            assert.ok(++count<200);
        }
        assert.equal(Object.values(game.money).reduce((sum,value)=>sum+value,0),0);
        report.rounds=round;
    }
    const room=sql(`SELECT room_id FROM room_record WHERE room_code='${code}' AND club_id=${club}`);
    assert.match(room,/^[0-9a-f-]{36}$/);report.roomId=room;
    assert.equal(sql(`SELECT COUNT(*) FROM wallet_transaction WHERE business_key='room:${room}:fee'`),'1');
    assert.equal(sql(`SELECT balance FROM club_wallet_account WHERE club_id=${club} AND currency='card'`),'97');
    assert.equal(sql(`SELECT COUNT(*) FROM player_room_binding WHERE room_id='${room}'`),'0');
    const date=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai'}).format(new Date());
    const history=await owner.send('club','getClubZhanJi',{id:club,date});assert.equal(history.state,0);assert.equal(history.zhanjis.length,1);
    const record=history.zhanjis[0];assert.equal(record.playNumber,6);report.recordId=record.record_id;
    const team=await a.send('partner','getClubMemberGameRecords',{club_id:club,partner_uid:a.uid,member_uid:b.uid,date,page:1});assert.equal(team.state,0);assert.equal(team.total,1);
    const movie=await owner.send('movie','getMovie',{movie_id:room});assert.equal(movie.state,0);assert.equal(movie.movie.data.length,6);
    for(const round of movie.movie.data)assert.ok(round.opreate.length>0&&round.game&&round.cards);
    assert.equal((await owner.send('club','disbandClub',{club_id:club})).state,0);
    report.monotonicRevisions=true;report.feeTransactions=1;report.cardCost=3;report.circleFinalCards=97;report.partnerRecords=1;report.movieRounds=6;report.finishedAt=new Date().toISOString();report.status='PASS';
    await writeFile('docs/evidence/club-full-20261107/room-wire.json',JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify(report));
}finally{for(const person of people)person.client.close();}
