import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
import {readFile,writeFile,unlink} from 'node:fs/promises';
import {base,guest,socket,sql} from './test-client.mjs';

assert.equal(process.env.RUN_CLUB_ROOM_RECOVERY,'1','需明确启用本地重启恢复验收');
assert.ok(['localhost','127.0.0.1'].includes(new URL(base).hostname));
const file=new URL('../.local/club-room-recovery-state.json',import.meta.url);
const clients=[];
const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
async function view(person) {
    const pushed=new Promise((resolve,reject)=>{
        const timer=setTimeout(()=>{person.client.ws.off('message',listen);reject(Error('未收到恢复快照'));},8000);
        function listen(raw){const packet=JSON.parse(raw);if(packet.cmd==='gameMessage'&&packet.tag==='initGame'){clearTimeout(timer);person.client.ws.off('message',listen);resolve(packet);}}
        person.client.ws.on('message',listen);
    });
    assert.equal((await person.send('game','checkGameCreate')).state,0);
    return pushed;
}
try {
    if(process.argv[2]==='prepare') {
        await writeFile(file,'{}\n',{flag:'wx',mode:0o600});
        for(let i=0;i<3;i++){const player=await guest({test:true});clients.push(player);}
        const owner=clients[0];
        const circle=await owner.send('club','createClub',{name:'恢复'+String(owner.uid).slice(-5)});assert.equal(circle.state,0);
        const club=circle.club.id;
        for(const person of clients.slice(1)){
            assert.equal((await person.send('club','apply',{id:club})).state,0);
            assert.equal((await owner.send('club','reviewApply',{id:club,targetUid:person.uid,val:1})).state,0);
        }
        const result=await owner.send('room','createRoom',{game_id:'6003',createData:{club_id:club,playNumber:6,cardType:4,aa:false},operationId:randomUUID()});
        assert.equal(result.state,0);const code=result.room.id,id=result.room.room_key;assert.match(id,/^[0-9a-f-]{36}$/);
        for(const person of clients)assert.equal((await person.send('room','joinRoom',{room_id:code})).state,0);
        for(const person of clients)assert.equal((await person.send('room','gameMessage',{tag:'ready'})).state,0);
        const bidding=await view(owner);
        const bidder=clients.find(person=>person.uid===bidding.game.currentUid);
        assert.equal((await bidder.send('room','gameMessage',{tag:'score',score:3})).state,0);
        const privateHashes={};
        for(const person of clients){const packet=await view(person);assert.equal(packet.game.currentState,2);privateHashes[person.uid]=digest(packet.cardsInfo[person.uid]);}
        const [hash,version,epoch]=sql(`SELECT state_hash,version,owner_epoch FROM room_runtime WHERE room_id='${id}'`).split('\t');
        const saved={club,code,id,hash,version:Number(version),epoch:Number(epoch),privateHashes,players:clients.map(person=>({uid:person.uid,token:person.token})),preparedAt:new Date().toISOString()};
        await writeFile(file,JSON.stringify(saved)+'\n',{mode:0o600});
        console.log(JSON.stringify({status:'PREPARED',club,code,version:saved.version,players:saved.players.map(person=>person.uid)}));
    }else if(['resume','resume-force'].includes(process.argv[2])) {
        const saved=JSON.parse(await readFile(file,'utf8'));
        assert.match(saved.id,/^[0-9a-f-]{36}$/);assert.ok(Number.isSafeInteger(saved.club));
        const [hash,version,epoch]=sql(`SELECT state_hash,version,owner_epoch FROM room_runtime WHERE room_id='${saved.id}'`).split('\t');
        assert.equal(hash,saved.hash,'重启后状态摘要改变');assert.equal(Number(version),saved.version);assert.ok(Number(epoch)>saved.epoch);
        for(const stored of saved.players){
            const client=await socket();const reg=await client.send('login',{api:'user',c:'reg',account:'tk_'+stored.token,plat_id:'2'});assert.equal(reg.state,0);assert.equal(reg.loginData.role.uid,stored.uid);
            const player={uid:stored.uid,client,send(api,c,fields={}){return client.send('logic',{api,c,...fields,sessionToken:reg.sessionToken});}};
            clients.push(player);
            const returned=await player.send('game','returnGame');assert.equal(returned.state,0);assert.equal(returned.room.room_key,saved.id);
            const packet=await view(player);assert.equal(packet.room_key,saved.id);assert.equal(packet.room_revision,saved.version);assert.equal(digest(packet.cardsInfo[player.uid]),saved.privateHashes[player.uid]);
        }
        assert.equal(sql(`SELECT COUNT(*) FROM wallet_transaction WHERE business_key='room:${saved.id}:fee'`),'1');
        const force=process.argv[2]==='resume-force';
        if(force) {
            assert.equal((await clients[1].send('room','forceDisbandRoom',{room_id:saved.code})).state,-90);
            const commands=[];
            const notice=new Promise((resolve,reject)=>{
                const timer=setTimeout(()=>reject(Error('未收到管理员强制解散通知')),8000);
                clients[1].client.ws.on('message',raw=>{
                    const packet=JSON.parse(raw);
                    if(packet.room_key===saved.id && ['disbandRoom','forceDisbandRoom'].includes(packet.cmd))commands.push(packet.cmd);
                    if(packet.room_key===saved.id && packet.cmd==='forceDisbandRoom'){clearTimeout(timer);resolve(packet);}
                });
            });
            assert.equal((await clients[0].send('room','forceDisbandRoom',{room_id:saved.code})).state,0);
            assert.equal((await notice).role.uid,clients[0].uid);assert.deepEqual(commands,['disbandRoom','forceDisbandRoom']);
        }else for(const player of clients)assert.equal((await player.send('room','voteEndGame',{result:1})).state,0);
        assert.equal((await clients[0].send('club','disbandClub',{club_id:saved.club})).state,0);
        assert.equal(sql(`SELECT balance FROM club_wallet_account WHERE club_id=${saved.club} AND currency='card'`),'97');
        const report={status:'PASS',closingMode:force?'staff-force':'unanimous-vote',roomId:saved.id,roomCode:saved.code,club:saved.club,players:saved.players.map(person=>person.uid),preparedAt:saved.preparedAt,finishedAt:new Date().toISOString(),stateHashUnchanged:true,privateHandsUnchanged:true,version:saved.version,previousEpoch:saved.epoch,recoveredEpoch:Number(epoch),feeTransactions:1,circleFinalCards:97};
        await writeFile('docs/evidence/club-full-20261107/'+(force?'room-force-recovery.json':'room-recovery.json'),JSON.stringify(report,null,2)+'\n');
        await unlink(file);console.log(JSON.stringify(report));
    }else throw Error('支持 prepare、resume 或 resume-force');
}finally{for(const player of clients)player.client.close();}
