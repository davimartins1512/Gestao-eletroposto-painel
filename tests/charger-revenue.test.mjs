import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const ctx=vm.createContext({Intl,Date});
for(const file of ['project-ui.js','admin-history.js'])vm.runInContext(readFileSync(new URL('../'+file,import.meta.url),'utf8'),ctx);
const report=(sessions,filters={})=>ctx.AndreaoHistory.report(sessions,points,filters);
const points=[{id:'80',name:'80 kW Goiabeiras'},{id:'40',name:'40 kW Goiabeiras'},{id:'old',name:'Estação antiga',active:false}];
const row=(id,patch={})=>({id,charge_point_id:'80',connector_id:1,started_at:'2026-10-31T12:00:00Z',
  session_status:'finished',payment_status:'pending',total_amount:50,energy_kwh:20,amount_paid:0,...patch});
test('identical site names do not mix stations or the two nozzles',()=>{
  const sessions=[row('1'),row('2',{connector_id:2}),row('3',{charge_point_id:'40'})];
  assert.equal(report(sessions,{point:'80',connector:'2'}).rows[0].id,'2');
  assert.equal(report(sessions,{point:'80',connector:'2'}).totals.billed,5000);
  assert.equal(report(sessions).groups.length,3);
});
test('date ranges include the entire Brazil day across UTC midnight',()=>{
  const r=report([row('before',{started_at:'2026-10-31T02:59:59Z'}),row('start',{started_at:'2026-10-31T03:00:00Z'}),
    row('end',{started_at:'2026-11-01T02:59:59Z'}),row('after',{started_at:'2026-11-01T03:00:00Z'})],{from:'2026-10-31',to:'2026-10-31'});
  assert.deepEqual(Array.from(r.rows,s=>s.id),['end','start']);
});
test('wallet, grouped and manual payments count each session once and preserve partial outstanding amounts',()=>{
  const r=report([row('paid',{payment_status:'paid',amount_paid:0,paid_amount:0}),
    row('partial',{amount_paid:12,paid_amount:12}),row('canonical-zero',{amount_paid:0,paid_amount:50}),
    row('grouped',{payment_status:'paid',active_payment_batch_id:'batch',amount_paid:50}),
    row('wallet',{payment_status:'paid',payment_confirmation_source:'prepaid_wallet',amount_paid:50})]);
  assert.equal(r.totals.billed,25000);assert.equal(r.totals.settled,16200);assert.equal(r.totals.pending,8800);
  assert.equal(report(r.rows,{payment:'pending'}).rows.length,2);
});
test('live, review and cancelled sessions are separated from final revenue',()=>{
  const r=report([row('finished'),row('live',{session_status:'charging'}),row('review',{reconciliation_required:true}),
    row('cancelled',{session_status:'cancelled'})]);
  assert.equal(r.totals.count,1);assert.equal(r.totals.billed,5000);assert.equal(r.totals.energy,20);
  assert.equal(r.totals.review,5000);assert.equal(r.totals.reviewCount,1);assert.equal(r.totals.live,5000);
  assert.equal(report(r.rows,{payment:'review'}).rows[0].id,'review');
});
test('totals use all matching sessions beyond the old 200-row display limit and add cents exactly',()=>{
  const r=report(Array.from({length:1205},(_,i)=>row(String(i),{total_amount:0.10})));
  assert.equal(r.rows.length,1205);assert.equal(r.totals.count,1205);assert.equal(r.totals.billed,12050);
  assert.equal(r.groups[0].billed,r.totals.billed);
});
test('historical stations remain reportable and search/payment filters compose with station selection',()=>{
  const rows=[row('legacy',{charge_point_id:'old',plate:'ABC1D23'}),row('other',{plate:'ABC1D23'})];
  assert.equal(report(rows,{point:'old',query:'abc1d23',payment:'pending'}).rows[0].id,'legacy');
  assert.equal(report(rows,{point:'old',query:'missing'}).totals.billed,0);
});

test('sessions explicitly requiring no payment do not inflate revenue or outstanding amounts',()=>{
  const r=report([row('free',{payment_status:'not_required',amount_paid:0})]);
  assert.equal(r.totals.billed,0);assert.equal(r.totals.settled,0);assert.equal(r.totals.pending,0);
  assert.equal(r.totals.count,1);assert.equal(r.totals.energy,20);
});
