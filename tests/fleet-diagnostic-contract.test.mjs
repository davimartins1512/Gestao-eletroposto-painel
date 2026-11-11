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
test('one-touch aggregate diagnostic runs all eight checks and exports',()=>{
  const s=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  assert.match(s,/EXECUTAR TODOS OS TESTES E SALVAR LOG/);
  const method=s.slice(s.indexOf('private void performAllChecks()'),s.indexOf('private void safeRun('));
  for(const m of ['systemTest','batteryTest','sensorTest','cameraTest','locationTest','microphoneTest','connectionTest','bydTest']) assert.match(method,new RegExp('this::'+m));
  assert.match(method,/exportReport\(\)/);
  assert.doesNotMatch(method,/openCameraTool|startRecording/);
  assert.match(s,/if\(req==REQUEST_ALL\)\{performAllChecks\(\);return;\}/);
});

test('v0.7 OEM probe is read-only, limited to known columns, and does not claim BMS',()=>{
  const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/BydReadOnlyProbe.java');
  assert.match(probe,/content:\/\/com\.byd\.carStatusProvider\/car_status/);
  assert.match(probe,/car_status_maintenance_mile/);
  assert.match(probe,/car_status_issue_num/);
  assert.match(probe,/N(?:AO|ÃO) confirma SOC ou SOH/);
  assert.match(probe,/getContentResolver\(\)\.query\(/);
  assert.doesNotMatch(probe,/\.insert\(|\.update\(|\.delete\(|getContentResolver\(\)\.call\(/);
});
test('v0.7 mobile data counters are not a carrier quota claim',()=>{
  const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/NetworkDataProbe.java');
  assert.match(probe,/TrafficStats\.getMobileRxBytes\(\)/);
  assert.match(probe,/NAO sao quantidade de GB restante/);
  const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
  assert.doesNotMatch(manifest,/android\.permission\.INTERNET/);
  const launch=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  assert.match(launch,/15 · BYD CarStatus/);
  assert.match(launch,/16 · Uso de dados móveis/);
  assert.match(launch,/new Thread\(/);
});

test('v0.8 has editable media and removable storage options, and never invokes camera automatically',()=>{
  const p=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/StoragePreferencesActivity.java');
  const s=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/StoragePolicy.java');
  const launch=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
  assert.match(launch,/17 · CONFIGURAR ARMAZENAMENTO/);
  assert.match(manifest,/StoragePreferencesActivity/);
  for(const k of ['storage_target','removable_autoswitch','fallback_internal','max_storage_gb',
    'reserve_free_gb','retention_hours','segment_seconds','video_quality','fps',
    'bitrate_mbps','live_quality','max_mobile_data_gb','mobile_data_alert_pct','parked_min_voltage_12v']){
    assert.match(p,new RegExp(k));
  }
  assert.match(s,/getExternalFilesDirs/);
  assert.match(s,/isExternalStorageRemovable/);
  assert.match(s,/File\.createTempFile/);
  assert.match(s,/getUsableSpace/);
  assert.doesNotMatch(s,/android\.permission\.MANAGE_EXTERNAL_STORAGE|Runtime\.getRuntime|su -c|\.exec\(/);
});

test('v0.9 checks only OEM camera compatibility; never opens a camera',()=>{
  const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/BydCameraCompatibility.java');
  const launcher=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  assert.ok(launcher.includes('18 · DIAGNOSTICAR CÂMERAS + bmmcamera.jar'));
  for(const marker of ['android.hardware.AVMCamera','android.hardware.BmmCameraInfo',
    'android.hardware.IBYDCameraService','bmmcamera.jar','getCameraId',
    'android.hardware.camera2.CameraManager']){
     assert.ok(probe.includes(marker) || (marker.includes('CameraManager')&&probe.includes('CameraManager')),
       'missing '+marker);
  }
  assert.match(probe,/Class\.forName\(/);
  assert.doesNotMatch(probe,/\.invoke\(/);
  assert.doesNotMatch(probe,/startPreview\(\s*\)|MediaRecorder\(|openCamera\(/);
  assert.doesNotMatch(probe,/getRuntime\(\)|\.exec\(|System\.loadLibrary|dlopen/);
  assert.match(probe,/Nenhum vídeo ou áudio capturado/);
  assert.match(probe,/câmera de ré/);
});

test('v1.0 probes bmmcamera.jar using PathClassLoader in metadata-only mode',()=>{
 const s=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/BydCameraCompatibility.java');
 assert.match(s,/new dalvik\.system\.PathClassLoader\(/);
 assert.match(s,/\/system\/framework\/bmmcamera\.jar/);
 assert.match(s,/Class\.forName\(name,false,loader\)/);
 assert.match(s,/appendJarDiscovery\(out,context\)/);
 assert.doesNotMatch(s,/\.invoke\(|\.newInstance\(|\.exec\(|getRuntime\(\)|System\.loadLibrary/);
});
