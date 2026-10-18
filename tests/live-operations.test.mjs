import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const code=readFileSync(new URL('../admin-live.js',import.meta.url),'utf8');
function harness(){
  const ctx=vm.createContext({window:{},Date,Promise});vm.runInContext(code,ctx);
  const calls=[],applied=[],timers=[],errors=[];
  let state={userId:'admin',eligible:true},busy=false,gate=null,fail=false;
  const client={from(table){let since=null;return{select(){return this;},gte(_key,value){since=value;return this;},order(){return this;},async range(){calls.push({table,since});if(gate)await gate;if(fail)return{error:new Error('offline')};return{data:table==='ocpp_sessions'?[{id:'live-session',updated_at:'2026-10-18T00:10:00Z'}]:[{id:table}]};}};}};
  const live=ctx.window.AndreaoLive.create({getClient:()=>client,getState:()=>state,isBusy:()=>busy,
    apply:x=>applied.push(x),onError:e=>errors.push(e),schedule:(fn,delay)=>{timers.push({fn,delay});return timers.length;},cancel(){}});
  return{live,calls,applied,timers,errors,state:x=>state=x,busy:x=>busy=x,gate:x=>gate=x,fail:()=>fail=true};
}
test('live refresh reads only stations, connectors and sessions changed since the loaded snapshot',async()=>{
  const h=harness();h.live.seed([{updated_at:'2026-10-18T00:09:00Z'}]);await h.live.refresh();
  assert.deepEqual(h.calls.map(c=>c.table).sort(),['charge_points','connectors','ocpp_sessions']);
  assert.equal(h.calls.find(c=>c.table==='ocpp_sessions').since,'2026-10-18T00:07:00.000Z');
  assert.equal(h.applied[0].sessions[0].id,'live-session');assert.equal(h.timers.at(-1).delay,20000);
});
test('hidden or unrelated pages, signed-out users and a full load do not poll',async()=>{
  const h=harness();h.state({userId:'admin',eligible:false});await h.live.refresh();
  h.state({userId:null,eligible:true});await h.live.refresh();
  h.state({userId:'admin',eligible:true});h.busy(true);await h.live.refresh();assert.equal(h.calls.length,0);
});
test('overlapping foreground refreshes share one request and a new full snapshot wins',async()=>{
  const h=harness();let release;h.gate(new Promise(r=>release=r));
  const first=h.live.refresh();const second=h.live.refresh();assert.equal(first,second);assert.equal(h.calls.length,3);
  h.live.seed([{updated_at:'2026-10-18T00:11:00Z'}]);release();await first;assert.equal(h.applied.length,0);
});
test('a sign-out or account change discards an old in-flight snapshot',async()=>{
  const h=harness();let release;h.gate(new Promise(r=>release=r));const task=h.live.refresh();
  h.state({userId:'another-admin',eligible:true});release();await task;assert.equal(h.applied.length,0);
});
test('network failures preserve the displayed snapshot and back off without overlapping requests',async()=>{
  const h=harness();h.fail();await h.live.refresh();assert.equal(h.applied.length,0);
  assert.equal(h.errors.length,1);assert.equal(h.timers.at(-1).delay,40000);
  h.live.stop();await h.live.refresh();assert.equal(h.calls.length,3);
});
