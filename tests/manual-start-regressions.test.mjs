import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const html=readFileSync(new URL('../painel.html',import.meta.url),'utf8');
const helpers=readFileSync(new URL('../project-ui.js',import.meta.url),'utf8');
const start=html.indexOf('async function manualStartCharge('),end=html.indexOf('  function openEditWalletBalance(',start);
function harness({value='',cash=false,state={phase:'charging',success:true}}={}){
  const elements={manualStartConnector:{value:'1'},manualStartCustomer:{value:'c1'},manualStartVehicle:{value:'v1',disabled:false},manualStartLimit:{value},manualStartCash:{checked:cash}};
  const requests=[],alerts=[],timers=[];let loads=0;
  const ctx=vm.createContext({console,AbortSignal,BACKEND_URL:'https://example.invalid',document:{getElementById:id=>elements[id]},
    supa:{auth:{getSession:async()=>({data:{session:{access_token:'synthetic'}}})}},
    fetch:async(url,options)=>{requests.push({url,options});return{ok:true,json:async()=>options?.method==='POST'?{success:true,authorization_id:'a1'}:state};},
    closeModal(){},alert:message=>alerts.push(message),setTimeout(fn,ms){timers.push({fn,ms});},loadAll:async()=>{loads++;},money:amount=>'R$ '+Number(amount).toFixed(2)});
  vm.runInContext(helpers,ctx);vm.runInContext(html.slice(start,end)+'\nthis.submit=manualStartCharge;this.watch=watchManualStartAuthorization;',ctx);
  return {ctx,requests,alerts,timers,loads:()=>loads};
}
test('operator can leave the charge unlimited or explicitly record R$ 50 cash',async()=>{
  for(const [value,cash,expected] of [['',false,null],['50,00',false,50],['50',true,50]]){
    const h=harness({value,cash});await h.ctx.submit('CP1');
    assert.equal(h.requests.length,1);const body=JSON.parse(h.requests[0].options.body);
    assert.equal(body.charge_limit_amount,expected);assert.equal(body.cash_received,cash);assert.equal(body.customer_id,'c1');assert.equal(body.vehicle_id,'v1');
  }
});
test('invalid budget or missing cash amount cannot send a start command',async()=>{
  for(const [value,cash] of [['0',false],['1.001',false],['',true]]){
    const h=harness({value,cash});await h.ctx.submit('CP1');assert.equal(h.requests.length,0);assert.equal(h.alerts.length,1);
  }
});
test('a confirmed start refreshes the dashboard; an expired start reports the actual failure',async()=>{
  for(const phase of ['charging','expired']){
    const h=harness({state:{success:true,phase,rejection_reason:'StartTransaction não recebido dentro do prazo.'}});
    const waiting=h.ctx.watch('a1');assert.equal(h.requests.length,0);h.timers[0].fn();await waiting;
    assert.equal(h.loads(),1);assert.equal(h.alerts.length,phase==='expired'?1:0);
    if(phase==='expired')assert.match(h.alerts[0],/StartTransaction/);
  }
});
