import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const read=p=>readFileSync(new URL('../'+p,import.meta.url),'utf8');
test('launcher is isolated from camera on startup',()=>{
  const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
  const launcher=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
  assert.match(manifest,/<activity android:name="\.FleetHomeActivity" android:exported="true">/);
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
  assert.match(manifest,/android:name="android\.permission\.INTERNET"/);
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
  assert.match(manifest,/android\.permission\.INTERNET/);
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
 assert.match(retention,/if\(!scopedMp4\(name\)\|\|name\.contains\("\.protected\."\)\)continue/);
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
 assert.match(clips,/Armazenamento indisponível após limpeza circular/);
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


test('v1.7 recovery never blindly promotes unfinished MP4 and avoids live files',()=>{
 const recovery=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemPartialRecovery.java');
 const verify=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemMp4Verifier.java');
 const gallery=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemRecordingLibraryActivity.java');
 assert.match(recovery,/MIN_AGE_MS=120000L/);
 assert.match(recovery,/\.mp4\.partial/);
 assert.match(recovery,/OemMp4Verifier\.verify\(input,false\)/);
 assert.match(recovery,/if\(!check\.valid\)/);
 assert.match(recovery,/if\(complete\.exists\(\)\)/);
 assert.match(recovery,/input\.renameTo\(complete\)/);
 assert.doesNotMatch(recovery,/input\.delete\(\)/);
 assert.match(verify,/METADATA_KEY_DURATION/);
 assert.match(gallery,/RECUPERAR MP4 TEMPORÁRIOS/);
 assert.match(gallery,/OemPartialRecovery\.recover/);
});

test('v1.7 gallery filters by age, shows clip timestamps, and records bounded session journal',()=>{
 const gallery=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemRecordingLibraryActivity.java');
 const journal=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemSessionJournal.java');
 const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 assert.match(gallery,/Última 1 hora/);
 assert.match(gallery,/Últimos 7 dias/);
 assert.match(gallery,/entry\.lastModified>=start/);
 assert.match(gallery,/dd\/MM\/yyyy HH:mm:ss/);
 assert.match(gallery,/VER ÚLTIMO RELATÓRIO DE GRAVAÇÃO/);
 assert.match(gallery,/OemSessionJournal\.latest/);
 assert.match(journal,/getFilesDir\(\)/);
 assert.match(journal,/MAX_TEXT_BYTES=32768/);
 assert.match(journal,/KEEP=30/);
 assert.match(journal,/new FileOutputStream\(temp\)/);
 assert.match(journal,/renameTo\(finalFile\)/);
 assert.match(probe,/OemSessionJournal\.write/);
 assert.doesNotMatch(journal,/HttpURLConnection|Socket|INTERNET|MediaRecorder/);
});


test('v1.8 finished recorders are not retained with megabyte-sized callback buffers',()=>{
 const s=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemClipSession.java');
 assert.match(s,/private static final class ClipResult/);
 assert.match(s,/private final List<ClipResult> closed=new ArrayList<>\(\)/);
 assert.match(s,/closed\.add\(new ClipResult\(r\)\)/);
 assert.match(s,/long first=clip\.firstNs/);
 assert.match(s,/long last=previous\.lastNs/);
 assert.doesNotMatch(s,/List<OemAvRecorder> closed/);
 assert.doesNotMatch(s,/closed\.add\(r\)/);
});

test('v1.8 OEM checkpoint survives process cancellation without collecting media',()=>{
 const cp=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemProbeCheckpoint.java');
 const p=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 const l=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
 assert.match(cp,/android\.util\.AtomicFile/);
 assert.match(cp,/fleet_oem_last_checkpoint\.txt/);
 assert.match(cp,/MAX_BYTES=2048/);
 assert.match(cp,/atomic\.finishWrite\(stream\)/);
 assert.match(cp,/atomic\.failWrite\(stream\)/);
 assert.match(cp,/public static String read\(/);
 assert.match(p,/lastCheckpointSeconds>=10/);
 assert.match(p,/WATCHDOG · processo de teste encerrado/);
 assert.match(p,/OemProbeCheckpoint\.save/);
 assert.match(p,/@Override public void onBackPressed\(\)/);
 assert.match(p,/Interromper/);
 assert.match(l,/OemProbeCheckpoint\.read/);
 assert.match(l,/22 · VER ÚLTIMO ESTADO DA GRAVAÇÃO OEM/);
 assert.match(l,/Isso NÃO confirma falha nos arquivos MP4/);
 assert.doesNotMatch(cp,/Socket|HttpURLConnection|MediaRecorder|MediaCodec|CameraManager/);
});


test('consolidated recording is bounded by operator quota, TTL and reserved free disk',()=>{
 const retention=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemMediaRetention.java');
 const policy=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetRetentionPlanner.java');
 const math=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetStorageMath.java');
 const preferences=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/StoragePreferencesActivity.java');
 const clips=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemClipSession.java');
 const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 assert.match(retention,/FleetRetentionPlanner\.calculate/);
 assert.match(retention,/!scopedMp4\(name\)/);
 assert.match(retention,/actualProtected/);
 assert.match(retention,/protectedEvent/);
 assert.match(retention,/return total<quota/);
 assert.match(policy,/c\.protectedIncident\)continue/);
 assert.match(policy,/beforeBytes,afterBytes,protectedBytes/);
 assert.match(math,/DEFAULT_QUOTA_GB=8f/);
 assert.match(math,/expectedGiB/);
 assert.match(preferences,/ECONÔMICO/);
 assert.match(preferences,/EQUILIBRADO/);
 assert.match(preferences,/showEstimate/);
 assert.match(clips,/OemMediaRetention\.clean\(context,prefs,selected\.folder\)/);
 assert.match(probe,/lastValidFrameMs/);
 assert.match(probe,/stalledMs>10000L/);
 assert.match(probe,/currentSession\.isRunning/);
 assert.doesNotMatch(retention,/MediaRecorder|Socket|HttpURLConnection/);
});


test('v2.0 provides honest local Fleet operations view and physically bounded recording',()=>{
 const activity=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetHealthActivity.java');
 const probe=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemFrameProbeActivity.java');
 const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
 const launcher=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
 const recorder=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemAvRecorder.java');
 const retention=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemMediaRetention.java');
 const sampler=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/Nv21Downsampler.java');
 assert.match(launcher,/23 · CENTRAL DO GRAVADOR/);
 assert.match(manifest,/FleetHealthActivity" android:exported="false"/);
 assert.match(activity,/OemRecordingLibrary\.list/);
 assert.match(activity,/OemMediaRetention\.enoughSpace/);
 assert.match(activity,/FleetStorageMath\.expectedGiB/);
 assert.match(activity,/NÃO VALIDADO/);
 assert.match(activity,/SEM FONTE AUTENTICADA/);
 assert.match(activity,/NÃO INTEGRADA/);
 assert.doesNotMatch(activity,/new AVMCamera|startPreview\(|getCameraId\(/);
 assert.match(probe,/stalledMs>10000L/);
 assert.match(recorder,/sampling=new Nv21Downsampler/);
 assert.match(recorder,/sampling\.writeNv12/);
 assert.match(recorder,/sampling\.writeI420/);
 assert.doesNotMatch(recorder,/byte\[\] planar=new byte\[/);
 assert.match(sampler,/maxIndex/);
 assert.match(sampler,/writeNv12/);
 assert.match(sampler,/writeI420/);
 assert.match(retention,/max_storage_gb/);
 assert.match(retention,/FleetRetentionPlanner\.calculate/);
});


test('v2.2 limits INTERNET permission to user-started inbound private-Wi-Fi server',()=>{
 const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
 const policy=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetDataPolicy.java');
 const state=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetNetworkStatus.java');
 const data=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetDataControlActivity.java');
 const launch=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/LaunchActivity.java');
 assert.match(manifest,/uses-permission android:name="android\.permission\.INTERNET"/);
 assert.match(manifest,/FleetDataControlActivity" android:exported="false"/);
 assert.match(launch,/24 · DADOS/);
 assert.match(state,/TrafficStats\.getUidTxBytes/);
 assert.match(state,/TrafficStats\.getUidRxBytes/);
 assert.match(state,/getActiveNetwork\(\)/);
 assert.match(state,/NET_CAPABILITY_VALIDATED/);
 assert.match(state,/NET_CAPABILITY_NOT_METERED/);
 assert.match(data,/Settings\.ACTION_WIFI_SETTINGS/);
 assert.match(data,/OemRecordingLibraryActivity/);
 assert.match(data,/INICIAR|DISPONIBILIZAR MP4/);
 assert.doesNotMatch(data,/URL\(|HttpURLConnection|Socket|upload\(|fetch\(/);
 assert.match(policy,/NO_HOTSPOT_CONFIRMATION/);
 assert.match(policy,/NO_WIFI/);
 assert.match(policy,/CELLULAR_TRANSPORT/);
 assert.match(policy,/NO_MONTHLY_BUDGET/);
 assert.match(policy,/SESSION_EXPIRED/);
});

test('selected accident clip is handled by an offline-only preparation/export flow',()=>{
 const gallery=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/OemRecordingLibraryActivity.java');
 const data=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetDataControlActivity.java');
 assert.match(gallery,/PREPARAR ESSE VÍDEO PARA O CELULAR \(SEM ENVIAR\)/);
 assert.match(gallery,/selected_local_clip/);
 assert.match(data,/OemRecordingLibrary\.resolve/);
 assert.match(data,/OemVideoExporter\.copyToMovies/);
 assert.match(data,/EXPORTAR CÓPIA DO VÍDEO ESCOLHIDO \(OFFLINE\)/);
 assert.match(data,/conectar ao hotspot sozinho NÃO envia/);
});


test('full integrated home and one-click acceptance connect actual modules without privileged vehicle state',()=>{
 const manifest=read('android-fleet/app/src/main/AndroidManifest.xml');
 const home=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetHomeActivity.java');
 const acceptance=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetAcceptanceActivity.java');
 const profile=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetProfileActivity.java');
 const trip=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetTripActivity.java');
 assert.match(manifest,/FleetHomeActivity" android:exported="true"/);
 for(const path of ['FleetAcceptanceActivity','FleetTripActivity','FleetProfileActivity']){
  assert.match(manifest,new RegExp(path+'\\\" android:exported=\\\"false\\\"'));
  assert.match(home,new RegExp(path+'\\.class'));
 }
 for(const path of ['OemFrameProbeActivity','OemRecordingLibraryActivity','FleetDataControlActivity','FleetHealthActivity','StoragePreferencesActivity']){
  assert.match(home,new RegExp(path+'\\.class'));
 }
 assert.match(acceptance,/OemRecordingLibrary\.list/);
 assert.match(acceptance,/OemMp4Verifier\.verify/);
 assert.match(acceptance,/StoragePolicy\.inventory/);
 assert.match(acceptance,/FleetNetworkStatus\.read/);
 assert.match(acceptance,/TrackerService\.PREFS/);
 assert.match(acceptance,/NÃO INTEGRADO/);
 assert.doesNotMatch(acceptance,/startPreview\(|new AVMCamera|startForegroundService\(/);
 assert.match(profile,/Dolphin Mini/);
 assert.match(profile,/Yuan Plus/);
 assert.match(profile,/Dolphin Plus/);
 assert.match(trip,/TrackerService\.ACTION_START/);
 assert.match(trip,/TrackerService\.ACTION_STOP/);
 assert.match(trip,/ACTION_CREATE_DOCUMENT/);
});

test('manual phone-hotspot incident transfer stays LAN-only, short-lived and token-protected',()=>{
 const lan=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetLanTransferServer.java');
 const data=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetDataControlActivity.java');
 const qr=read('android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetQrCode.java');
 assert.match(lan,/new SecureRandom\(\)/);
 assert.match(lan,/ServerSocket/);
 assert.match(lan,/server\.bind\(new InetSocketAddress\(ip,0\),1\)/);
 assert.match(lan,/original\.equals\(cm\.getActiveNetwork\(\)\)/);
 assert.match(lan,/TRANSPORT_WIFI/);
 assert.match(lan,/TRANSPORT_CELLULAR/);
 assert.match(lan,/while\(remaining>0 && !closed/);
 assert.match(lan,/used=true/);
 assert.match(lan,/WINDOW_MS=10\*60\*1000L/);
 assert.doesNotMatch(lan,/HttpURLConnection|URL\.openConnection|SocketChannel\.open|Network\.openConnection/);
 assert.match(data,/FleetLanTransferServer\.create/);
 assert.match(data,/lanServer|lan=/);
 assert.match(data,/FleetQrCode\.make/);
 assert.match(data,/stopLan\(\)/);
 assert.match(qr,/new QRCodeWriter/);
});
