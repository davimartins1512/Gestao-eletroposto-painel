import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const html=readFileSync(new URL('../painel.html',import.meta.url),'utf8');
function harness(){
  const alerts=[],calls=[];let reply,release;
  const elements={walletReleaseReason:{value:'Carga não iniciou'},walletReleaseConfirm:{disabled:false}};
  const ctx=vm.createContext({Set,Number,Date,JSON,esc:s=>String(s).replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&#39;'),
    money:n=>'R$ '+Number(n).toFixed(2),showModal:(title,content)=>{reply={title,content};},
    document:{getElementById:id=>elements[id]},alert:m=>alerts.push(m),getAdminAccessToken:async()=>'test-token',
    loadOcppCustomers:async()=>{},openCustomerWallet:async()=>{},BACKEND_URL:'https://test.invalid',
    fetch:async(url,options)=>{calls.push({url,options});return await new Promise(r=>release=r);}});
  vm.runInContext(html.slice(html.indexOf('const walletReleaseBusy='),html.indexOf('async function openCustomerWallet('))+
    '\nthis.section=walletReservationSection;this.open=openWalletReservationRelease;this.submit=releaseWalletReservation;',ctx);
  return{ctx,calls,alerts,elements,reply:()=>reply,finish:res=>release(res)};
}
test('wallet shows individual holds, blocked reasons and escaped audit text',()=>{
  const h=harness();const content=h.ctx.section('customer',[
    {id:'one',reserved_amount:40,created_at:'2026-11-21',charger_name:'<script>',block_reason:null},
    {id:'two',reserved_amount:60,created_at:'2026-11-21',block_reason:'reservation_has_session'}
  ],[{released_at:'2026-11-21',details:{amount:5,reason:'<img onerror="alert(1)">'}}]);
  assert(content.includes('Liberar reserva'));assert(content.includes('Carga vinculada'));assert(content.includes('&lt;script&gt;'));
  assert(!content.includes('<img'));assert.equal((content.match(/onclick="openWalletReservationRelease/g)||[]).length,1);
});
test('release asks for a reason and prevents a double click from submitting twice',async()=>{
  const h=harness();h.ctx.open('customer','reservation',40);assert(h.reply().content.includes('Motivo da liberação'));
  const first=h.ctx.submit('customer','reservation');await new Promise(setImmediate);
  await h.ctx.submit('customer','reservation');assert.equal(h.calls.length,1);assert(h.elements.walletReleaseConfirm.disabled);
  assert.deepEqual(JSON.parse(h.calls[0].options.body),{reason:'Carga não iniciou'});
  h.finish({ok:true,json:async()=>({success:true,released_amount:40})});await first;
  assert(h.alerts[0].includes('40.00'));assert.equal(h.elements.walletReleaseConfirm.disabled,false);
});
test('unknown or rejected response does not retry or announce success',async()=>{
  const h=harness();const task=h.ctx.submit('customer','reservation');await new Promise(setImmediate);
  h.finish({ok:false,json:async()=>({message:'Carga em andamento'})});await task;
  assert.equal(h.calls.length,1);assert.equal(h.alerts[0],'Carga em andamento');
});
