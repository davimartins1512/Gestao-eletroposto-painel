import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const read=p=>readFileSync(new URL('../'+p,import.meta.url),'utf8');
test('launcher is isolated from camera on startup',()=>{
  const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
  const launcher=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  assert.match(manifest,/<activity android:name="\.LaunchActivity" android:exported="true">/);
  assert.match(manifest,/<activity android:name="\.MainActivity" android:exported="false"/);
  const before=launcher.split('private void cameraTest()')[0];
  assert.doesNotMatch(before,/getCameraIdList\(/);
  assert.doesNotMatch(before,/getSystemService\(CAMERA_SERVICE\)/);
});
test('all basic diagnostic categories are included',()=>{
  const launcher=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  for(const method of ['systemTest','batteryTest','sensorTest','cameraTest','locationTest','microphoneTest','connectionTest','bydTest','showPreviousCrash']){
    assert.match(launcher,new RegExp('private void '+method+'\\('));
  }
  assert.match(launcher,/SOH bateria de tração: NÃO VERIFICADO/);
  assert.match(launcher,/Coordenadas NÃO incluídas no relatório/);
});
test('offline and permission aware',()=>{
  const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
  assert.doesNotMatch(manifest,/android:name="android\.permission\.INTERNET"/);
  assert.match(manifest,/ACCESS_FINE_LOCATION/);
  assert.match(manifest,/RECORD_AUDIO/);
  assert.match(manifest,/ACCESS_NETWORK_STATE/);
});