import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
export async function runtimeArtifact(){
 const pid=Number((await readFile(new URL('../.local/server.pid',import.meta.url),'utf8')).trim());
 let record;
 try{record=JSON.parse(await readFile(new URL('../.local/server-artifact.json',import.meta.url),'utf8'));}
 catch(error){if(error.code!=='ENOENT')throw error;return {relativePath:'ruoyi-admin/target/ruoyi-admin.jar',url:new URL('../ruoyi-admin/target/ruoyi-admin.jar',import.meta.url),pid};}
 assert.equal(record.pid,pid,'运行产物与进程登记不一致');
 assert.match(record.relativePath,/^\.local\/runtime\/[0-9a-f]{64}\.jar$/);
 assert.equal(record.relativePath,`.local/runtime/${record.sha256}.jar`);
 return {...record,url:new URL('../'+record.relativePath,import.meta.url)};
}
