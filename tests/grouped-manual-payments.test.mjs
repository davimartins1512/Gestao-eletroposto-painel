import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const html=readFileSync(new URL('../painel.html',import.meta.url),'utf8');
const start=html.indexOf(' let manualPaymentContext = null;'),end=html.indexOf('function renderOcpp()',start);
function harness({received=20,scope='session',changed=false}={}){
  const requests=[],alerts=[],modals=[];let reloads=0;
  const inputs={manualPaymentScope:{value:scope},manualPaymentAmount:{value:received},manualPaymentMethod:{value:'cash'},manualPaymentNote:{value:' received '},manualPaymentDue:{value:''},manualPaymentConfirm:{disabled:false}};
  const ctx=vm.createContext({Number,Math,BACKEND_URL:'https://example.invalid',document:{getElementById:id=>inputs[id]},
    getAdminAccessToken:async()=>'synthetic',esc:value=>String(value),money:value=>'R$ '+Number(value).toFixed(2),
    showModal:(title,body)=>modals.push({title,body}),alert:message=>alerts.push(message),closeModal(){},loadAll:async()=>{reloads++;},
    fetch:async(url,options)=>{requests.push({url,options});return{ok:!changed||options.method!=='POST',json:async()=>options.method==='POST'?
      (changed?{success:false,error:'A cobrança mudou. Atualize a ficha.'}:{success:true,sessions_paid:scope==='batch'?2:1}):
      {success:true,session:{id:'s1',transaction_id:1,session_status:'finished',payment_status:'pending',total_amount:30,amount_paid:10},batch:{id:'b1',session_count:2,outstanding_amount:50}}};}
  });
  vm.runInContext(html.slice(start,end)+'\nthis.open=openManualPayment;this.submit=confirmManualPayment;this.scope=updateManualPaymentScope;',ctx);
  return{ctx,requests,alerts,modals,inputs,reloads:()=>reloads};
}
test('grouped sessions remain receivable and the preview offers individual or group settlement',async()=>{
  const h=harness();await h.ctx.open('s1');assert.match(h.modals[0].body,/Somente esta recarga/);assert.match(h.modals[0].body,/Grupo completo/);
  await h.ctx.submit('s1');const body=JSON.parse(h.requests[1].options.body);assert.equal(body.received_amount,20);assert.equal(body.payment_method,'cash');assert.equal(body.pay_batch,false);assert.equal(body.expected_batch_id,'b1');
  assert.equal(h.reloads(),1);
});
test('whole group scope fills and sends the complete pending amount',async()=>{
  const h=harness({scope:'batch'});await h.ctx.open('s1');h.ctx.scope();assert.equal(h.inputs.manualPaymentAmount.value,'50.00');
  await h.ctx.submit('s1');const body=JSON.parse(h.requests[1].options.body);assert.equal(body.pay_batch,true);assert.equal(body.received_amount,50);
});
test('a partial or invalid receipt cannot mark an entire charge paid',async()=>{
  for(const received of [19,0,1.001,'invalid']){const h=harness({received});await h.ctx.open('s1');await h.ctx.submit('s1');assert.equal(h.requests.length,1);assert.equal(h.reloads(),0);}
});
test('a changed group reports the conflict and permits a later corrected attempt',async()=>{
  const h=harness({changed:true});await h.ctx.open('s1');await h.ctx.submit('s1');assert.match(h.alerts[0],/cobrança mudou/);assert.equal(h.inputs.manualPaymentConfirm.disabled,false);assert.equal(h.reloads(),0);
});
