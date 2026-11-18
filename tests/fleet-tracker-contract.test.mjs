import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const read=(p)=>readFileSync(new URL('../'+p,import.meta.url),'utf8');
test('GPS test is only started after a user action and is visibly foreground',()=>{
  const s=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/TrackerService.java');
  const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
  assert.match(s,/MAX_DURATION_MS=180000L/);
  assert.match(s,/startForeground\(NOTIFICATION,note\)/);
  assert.match(s,/ACTION_STOP/);
  assert.match(s,/removeUpdates\(this\)/);
  assert.match(s,/unregisterListener\(this\)/);
  assert.match(manifest,/FOREGROUND_SERVICE/);
  assert.match(manifest,/android:foregroundServiceType="location"/);
  assert.match(manifest,/\.TrackerService" android:exported="false"/);
});
test('microphone recording is opt in and temporary',()=>{
  const s=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/AudioTestActivity.java');
  assert.match(s,/setPositiveButton\("Sim, autorizamos"/);
  assert.match(s,/RECORD_AUDIO/);
  assert.match(s,/temporary\.delete\(\)/);
  assert.match(s,/handler\.postDelayed\(finishTask,5000\)/);
});
test('GPS coordinates are only exported on explicit action, internet is absent',()=>{
  const src=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
  assert.match(src,/EXPORTAR LOG ÚNICO/);
  assert.match(src,/COORDENADAS PRECISAS/);
  assert.match(src,/new Intent\(this,TrackerService\.class\)\.setAction\(TrackerService\.ACTION_START\)/);
  assert.match(src,/private void saveTextFile/);
  assert.doesNotMatch(manifest,/android:name="android\.permission\.INTERNET"/);
});
