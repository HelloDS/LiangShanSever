/** 源码只读兼容性诊断，不运行归档脚本、不修改 Creator 或开通兑换。 */
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const source='creator/assets/source-data/files/f8c877d2a7955b105dc3ccb2cc3bf2b7e17fc088d1ed81b680d8f1a9ee4c12c9.txt';
const bytes=await readFile(new URL('../../Client/'+source,import.meta.url)),text=bytes.toString('utf8');
const division=/\.prototype\.getShang=function\(t,n\)\{return\(t-this\.getYu\(t,n\)\)\/n\}/.exec(text)?.[0];
const remainder=/\.prototype\.getYu=function\(t,n\)\{return t%n\}/.exec(text)?.[0];assert.ok(division&&remainder,'既有算法已改变，需重新人工追踪');
assert.ok(text.includes('1 房卡 等于 "+this.rate+" 钻石'));
const ratio=.1,samples=[1,2,3,10,100].map(diamonds=>({diamonds,expectedCards:diamonds*10,clientRemainder:diamonds%ratio,clientCards:(diamonds-diamonds%ratio)/ratio}));
assert.equal(samples[0].clientCards,9);assert.ok(samples.every(row=>row.clientCards!==row.expectedCards));
await writeFile(new URL('../docs/evidence/exchange-client-compatibility.json',import.meta.url),JSON.stringify({executedAt:new Date().toISOString(),source,sha256:createHash('sha256').update(bytes).digest('hex'),sourceChanged:false,confirmedRule:'1 diamond = 10 cards',legacyRatioMeaning:'diamonds per card',requiredLegacyRatio:ratio,sourceExpressions:[division,remainder],samples,result:'INCOMPATIBLE'},null,2)+'\n');
console.log('已确认原页面在 1 钻石换 10 房卡时的浮点取余差异；客户端未修改，兑换未开放。');
