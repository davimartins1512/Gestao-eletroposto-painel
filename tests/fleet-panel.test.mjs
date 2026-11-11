import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
const read = file => readFileSync(new URL('../'+file,import.meta.url),'utf8');

test('fleet panel script parses under Node',()=>{
  execFileSync(process.execPath,['--check',new URL('../fleet.js',import.meta.url).pathname],{stdio:'pipe'});
});
test('fleet module is accessible from admin navigation',()=>{
  assert.match(read('painel.html'),/href="fleet\.html"/);
  assert.match(read('fleet.html'),/src="fleet\.js"/);
  assert.match(read('fleet.html'),/href="fleet\.css"/);
});
test('fleet interface never invents a working camera or SOH reading',()=>{
  const script=read('fleet.js');
  assert.match(script,/Não integrada/);
  assert.match(script,/Sem leitura|ainda não existe integração de telemetria verificada/);
  assert.match(script,/source/);
  assert.doesNotMatch(script,/getUserMedia\(|MediaStream\(|remoteStart|remoteStop|immobiliz/i);
});
test('telemetry is separated from manual fleet events',()=>{
  const script=read('fleet.js');
  assert.match(script,/fleet_telemetry/);
  assert.match(script,/fleet_entries/);
  assert.doesNotMatch(script,/from\('fleet_telemetry'\)\.insert/);
});
test('fleet feature toggles are per vehicle, do not actuate devices or fake live features',()=>{
  const script=read('fleet.js');
  const html=read('fleet.html');
  assert.match(script,/fleet_feature_settings/);
  assert.match(script,/fleet_feature_status/);
  assert.match(script,/fleet_recording_state/);
  assert.match(script,/fleet_charge_locations/);
  assert.match(script,/desired_enabled:enabled/);
  assert.match(script,/Não faz o veículo gravar/);
  assert.match(script,/code==='cabin_microphone'/);
  assert.match(script,/location_source:'manual', verification_status:'unverified'/);
  assert.match(script,/data-action="add-charge"/);
  assert.doesNotMatch(script,/from\('fleet_feature_status'\)\.upsert/);
  assert.doesNotMatch(script,/from\('fleet_recording_state'\)\.upsert/);
});
