import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const read=name=>readFileSync(new URL('../'+name,import.meta.url),'utf8');
const ctx=vm.createContext({Intl,Date});vm.runInContext(read('project-ui.js'),ctx);
const ui=ctx.AndreaoUI;
test('financial days and months follow Brazil midnight instead of UTC',()=>{
  assert.equal(ui.operationalDay('2026-10-07T01:59:59Z'),'2026-10-06');
  assert.equal(ui.operationalDay('2026-10-07T03:00:00Z'),'2026-10-07');
  assert.equal(ui.operationalDay('2026-11-01T01:00:00Z'),'2026-10-31');
  assert.equal(ui.operationalDay('2026-10-07'),'2026-10-07');
  assert.equal(ui.operationalDay('invalid'),'');
});
test('charging page shows unavailable for faulted connectors even while online',()=>{
  for(const status of ['Faulted','Unavailable','Reserved','unknown'])assert.equal(ui.connectorUnavailable({status,online:true}),true);
  assert.equal(ui.connectorUnavailable({status:'Available',error_code:'OtherError',online:true}),true);
  assert.equal(ui.connectorUnavailable({status:'Available',error_code:'NoError',online:true}),false);
});
test('pending balance uses amount_paid without counting stale paid_amount twice',()=>{
  assert.equal(ui.sessionPaidAmount({amount_paid:5,paid_amount:1}),5);
  assert.equal(ui.sessionPaidAmount({amount_paid:0,paid_amount:5}),0);
  assert.equal(ui.sessionPaidAmount({paid_amount:5}),5);
});
test('both HTML pages load operational helpers before the scripts that use them',()=>{
  for(const file of ['painel.html','carregar.html']){
    const html=read(file);const script=html.search(/src="project-ui\.js(?:\?[^"]*)?"/);
    assert.ok(script>=0,file);assert.ok(script<html.indexOf('AndreaoUI.'),file);
  }
});

function worker(fetchImpl=async()=>new Response('asset')){
  const listeners={},put=[];
  vm.runInNewContext(read('sw.js'),{URL,Response,self:{registration:{scope:'https://example.invalid/'},location:{origin:'https://example.invalid'},addEventListener(name,fn){listeners[name]=fn;},skipWaiting:async()=>{},clients:{claim:async()=>{}}},fetch:fetchImpl,caches:{open:async()=>({put:async(request,response)=>put.push({url:request.url,body:await response.text()})}),match:async()=>new Response('offline asset')},Set});
  function request(url,options={}){
    let result;listeners.fetch({request:new Request(url,options),respondWith(promise){result=promise;}});return result;
  }
  return{request,put};
}
test('service worker leaves API, authenticated and cross-origin requests outside its cache',()=>{
  const w=worker(()=>assert.fail('worker must not intercept'));
  for(const [url,options] of [['https://example.invalid/api/admin/whatsapp/status',{}],['https://backend.invalid/api/customer/session',{}],['https://example.invalid/painel.html',{headers:{authorization:'Bearer test'}}],['https://example.invalid/painel.html',{cache:'no-store'}],['https://example.invalid/painel.html?token=test',{}]])assert.equal(w.request(url,options),undefined,url);
});
test('static assets cache normally, but private responses never enter offline storage',async()=>{
  const w=worker();const response=await w.request('https://example.invalid/project-ui.js');assert.equal(await response.text(),'asset');assert.equal(w.put.length,1);
  const privateWorker=worker(async()=>new Response('private',{headers:{'cache-control':'private, no-store'}}));
  await privateWorker.request('https://example.invalid/painel.html');assert.equal(privateWorker.put.length,0);
});
test('static offline fallback never substitutes HTML for API requests',async()=>{
  const w=worker(async()=>{throw new Error('offline');});assert.equal(await (await w.request('https://example.invalid/painel.html')).text(),'offline asset');
  assert.equal(w.request('https://example.invalid/api/admin/whatsapp/status'),undefined);
});
