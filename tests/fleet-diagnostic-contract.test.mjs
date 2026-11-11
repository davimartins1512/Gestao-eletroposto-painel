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

test('v1.1 actively resolves only BYD camera IDs and callback signatures without opening the camera',()=>{
 const s=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/BydCameraIdProbe.java');
 const ui=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
 assert.match(s,/PathClassLoader/);
 assert.match(s,/getDeclaredMethod\("getCameraId",String\.class\)/);
 assert.match(s,/lookup\.invoke\(null,tag\)/);
 assert.match(s,/Modifier\.isStatic/);
 assert.match(s,/"pano_h","pano_l","apa","byd_apa"/);
 assert.match(s,/IPreviewCallback/);
 assert.match(ui,/19 · IDENTIFICAR CÂMERAS BYD/);
 assert.doesNotMatch(s,/(?:newInstance|openCamera|startPreview|stopPreview)\s*\(/);
 assert.doesNotMatch(s,/\.getRuntime\(\)|\.exec\(|\.loadLibrary\(/);
});

test('v1.2 OEM frame test runs only after explicit user confirmation, in isolated process with stop/close',()=>{
 const source=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
 const launcher=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
 assert.match(manifest,/android:name="\.OemFrameProbeActivity" android:exported="false" android:process=":oem_camera_probe"/);
 assert.match(launcher,/20 · TESTE INTEGRADO DE FOTO \+ MP4/);
 assert.match(launcher,/OEM_FRAMES/);
 assert.match(launcher,/ENSAIO OEM REAL · FRAMES/);
 assert.match(source,/new AlertDialog\.Builder\(this\)/);
 assert.match(source,/MAX_PREVIEW_MS=3500/);
 assert.match(source,/WATCHDOG_MS=45000/);
 assert.match(source,/getDeclaredMethod\("getCameraId",String\.class\)/);
 assert.match(source,/byTag\.invoke\(null,"pano_h"\)/);
 assert.match(source,/getDeclaredConstructor\(int\.class\)/);
 assert.match(source,/new Class<\?>\[\]\{callback\}/);
 assert.match(source,/getDeclaredMethod\("setPreviewCallback",callback\)/);
 assert.match(source,/getDeclaredMethod\("startPreview"\)/);
 assert.match(source,/getDeclaredMethod\("enablePreviewCallback",int\.class\)/);
 assert.match(source,/getDeclaredMethod\("stopPreview"\)/);
 assert.match(source,/getDeclaredMethod\("close"\)/);
 assert.match(source,/finally\{/);
 assert.match(source,/stopPreview\.invoke\(camera\)/);
 assert.match(source,/close\.invoke\(camera\)/);
 assert.match(source,/android\.os\.Process\.killProcess/);
 assert.doesNotMatch(source,/MediaRecorder|AudioRecord|MediaCodec|openOutputStream|FileOutputStream|HTTP|Socket/);
});

test('v1.3 pilot has manual photo, MP4 and disclosed optional audio on SAME muxer',()=>{
  const activity=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
  const recorder=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemAvRecorder.java');
  const snap=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemSnapshotWriter.java');
  const retain=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemMediaRetention.java');
  for(const exp of [/PILOTO 15s · FOTO \+ VÍDEO MP4 \(SEM ÁUDIO\)/,/PILOTO 15s · FOTO \+ VÍDEO MP4 \+ ÁUDIO/,
    /mode==2/,/ocupa|ocupantes/i,/mediaRecorder\.stop\(\)/,/OemSnapshotWriter\.save\(/])
      assert.match(activity,exp);
  assert.match(recorder,/new MediaMuxer\(/);
  assert.match(recorder,/MediaFormat\.createVideoFormat/);
  assert.match(recorder,/MediaFormat\.createAudioFormat/);
  assert.match(recorder,/new AudioRecord\(/);
  assert.match(recorder,/muxer\.writeSampleData/);
  assert.match(recorder,/COLOR_FormatYUV420SemiPlanar/);
  assert.match(recorder,/ArrayBlockingQueue/);
  assert.match(recorder,/OemMediaRetention\.clean/);
  assert.match(snap,/MediaStore\.Images\.Media/);
  assert.match(retain,/\.protected\./);
  assert.doesNotMatch(recorder,/Socket|HttpURLConnection|MediaRecorder\.AudioSource\.VOICE_CALL/);
  assert.doesNotMatch(activity,/INTERNET|remoteStartRecording/);
});

test('default future recording segmentation is 90 seconds and remains user-editable',()=>{
 const settings=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/StoragePreferencesActivity.java');
 assert.match(settings,/prefs\.getFloat\("segment_seconds",90f\)/);
 assert.match(settings,/Duração dos clipes MP4/);
 assert.match(settings,/numeric\("segment_seconds"/);
});

test('v1.4 manual clip coordinator preserves camera session and records rotation gaps',()=>{
  const c=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemClipSession.java');
  const a=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
  assert.match(c,/clipSeconds=Math\.max\(10,Math\.min\(600,selected\)\)/);
  assert.match(c,/prefs\.getFloat\("segment_seconds",90f\)/);
  assert.match(c,/new OemAvRecorder\(/);
  assert.match(c,/current=next/);
  assert.match(c,/callbackGaps\.incrementAndGet\(\)/);
  assert.match(c,/Clipes contínuos/);
  assert.match(c,/Lacuna|lacuna/);
  assert.match(a,/session\.accept\(data,width,height,bufferSize,nowNs\)/);
  assert.match(a,/clipSession\.stopAndWait\(/);
  assert.match(a,/setItems\(choices/);
  assert.match(a,/TESTAR CLIPES SEQUENCIAIS/);
  assert.doesNotMatch(c,/Class\.forName\(|getDeclaredMethod\(|\.startPreview\(/);
});

test('incident clips can be marked protected and excluded from circular cleanup',()=>{
 const retention=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemMediaRetention.java');
 const session=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemClipSession.java');
 const ui=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 assert.match(retention,/public static File protect\(File clip\)/);
 assert.match(retention,/\.protected\.mp4/);
 assert.match(retention,/!f\.getName\(\)\.contains\("\.protected\."\)/);
 assert.match(session,/protectClip\(int index\)/);
 assert.match(ui,/PROTEGER CLIPE DE OCORRÊNCIA/);
 assert.match(ui,/session\.protectClip\(which\)/);
});

test('local library survives UI restarts and constrains playback/export to own files',()=>{
 const library=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemRecordingLibrary.java');
 const screen=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemRecordingLibraryActivity.java');
 const launcher=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
 const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
 assert.match(library,/StoragePolicy\.inventory\(context,false\)/);
 assert.match(library,/validName/);
 assert.match(library,/getCanonicalPath/);
 assert.match(library,/public static File resolve\(/);
 assert.match(screen,/setVideoPath/);
 assert.match(screen,/OemVideoExporter\.copyToMovies/);
 assert.match(screen,/OemMediaRetention\.protect/);
 assert.match(screen,/REPRODUZIR/);
 assert.match(screen,/EXPORTAR ESTE MP4/);
 assert.match(screen,/PROTEGER COMO OCORRÊNCIA/);
 assert.match(launcher,/21 · GALERIA LOCAL/);
 assert.match(manifest,/OemRecordingLibraryActivity" android:exported="false"/);
});

test('v1.5 encoder checks finalized MP4 metadata and supports NV12 and planar I420',()=>{
 const recorder=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemAvRecorder.java');
 const verify=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemMp4Verifier.java');
 assert.match(recorder,/OemMp4Verifier\.verify\(partial,withAudio\)/);
 assert.match(recorder,/verified\.valid && partial\.renameTo\(complete\)/);
 assert.match(recorder,/COLOR_FormatYUV420SemiPlanar/);
 assert.match(recorder,/COLOR_FormatYUV420Planar/);
 assert.match(recorder,/scaleNv21ToI420/);
 assert.match(recorder,/firstAcceptedNs/);
 assert.match(recorder,/lastAcceptedNs/);
 assert.match(verify,/MediaMetadataRetriever/);
 assert.match(verify,/METADATA_KEY_DURATION/);
 assert.match(verify,/METADATA_KEY_VIDEO_WIDTH/);
 assert.match(verify,/METADATA_KEY_HAS_VIDEO/);
 assert.match(verify,/METADATA_KEY_HAS_AUDIO/);
 assert.match(verify,/!requiresAudio \|\| "yes"/);
});
test('v1.5 clip report exposes cross-clip frame timing instead of assuming gap-free recording',()=>{
 const clips=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemClipSession.java');
 assert.match(clips,/Intervalo ENTRE clipes/);
 assert.match(clips,/possible|possível/i);
 assert.match(clips,/firstAcceptedNs\(\)/);
 assert.match(clips,/lastAcceptedNs\(\)/);
});

test('v1.6 extended multi-clip pilot is adjustable with explicit audio and visible REC indicator',()=>{
 const media=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/StoragePreferencesActivity.java');
 const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 const clips=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemClipSession.java');
 assert.match(media,/numeric\("test_duration_minutes"/);
 assert.match(media,/prefs\.getFloat\("test_duration_minutes",5f\)/);
 assert.match(probe,/ENSAIO PROLONGADO · CLIPES MP4/);
 assert.match(probe,/mode==5\|\|mode==6/);
 assert.match(probe,/Math\.max\(2,Math\.min\(30,minutes\)\)/);
 assert.match(probe,/mode==2\|\|mode==4\|\|mode==6/);
 assert.match(probe,/sessionTicker/);
 assert.match(probe,/GRAVANDO ÁUDIO E VÍDEO/);
 assert.match(probe,/clipSession\.stopAndWait/);
 assert.match(clips,/StoragePolicy\.select\(context,prefs\)/);
 assert.match(clips,/Mudanças de armazenamento/);
 assert.match(clips,/Sem armazenamento gravável/);
});
test('v1.6 recorder setup errors go through the OEM camera teardown in finally',()=>{
 const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 const tryAt=probe.indexOf('    try{\n    if(mode>0)');
 const startAt=probe.indexOf('          clipSession.start();');
 const cleanupAt=probe.indexOf('    }finally{',startAt);
 assert.ok(tryAt>0&&startAt>tryAt&&cleanupAt>startAt);
 assert.match(probe,/stopPreview\.invoke\(camera\)/);
 assert.match(probe,/close\.invoke\(camera\)/);
});
