import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const html=readFileSync(new URL('../carregar.html',import.meta.url),'utf8');
test('the same wallet moves into the charging screen and back without losing its input',()=>{
  const elements={walletTopupHelp:{},walletReservedLabel:{},walletBox:{value:20}};
  for(const id of ['walletHomeSlot','chargingWalletSlot'])elements[id]={appendChild(box){box.parentElement=this;this.child=box;}};
  const ctx=vm.createContext({$:id=>elements[id]});
  vm.runInContext(html.slice(html.indexOf('function positionWallet('),html.indexOf('function showScreen('))+'\nthis.move=positionWallet;',ctx);
  ctx.move('charging');assert.equal(elements.walletBox.parentElement,elements.chargingWalletSlot);
  assert.match(elements.walletTopupHelp.textContent,/sem encerrar a carga/);
  ctx.move('charging');assert.equal(elements.chargingWalletSlot.child,elements.walletBox);
  ctx.move('confirm');assert.equal(elements.walletBox.parentElement,elements.walletHomeSlot);
  assert.equal(elements.walletBox.value,20);
  assert.equal((html.match(/id="walletBox"/g)||[]).length,1);
});
test('inline charging code compiles with active wallet polling and existing session recovery',()=>{
  const scripts=[...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).filter(s=>s.trim());
  for(const code of scripts)new vm.Script(code);
  assert.match(html,/id="chargingWalletSlot"/);assert.match(html,/id="walletHomeSlot"/);
});
