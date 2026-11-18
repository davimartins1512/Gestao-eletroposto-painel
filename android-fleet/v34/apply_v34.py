from pathlib import Path
import sys
root=Path(sys.argv[1])
java=root/'app/src/main/java/br/com/andreao/fleetprobe'
act=java/'FleetCloudSyncActivity.java'
home=java/'FleetHomeActivity.java'
manifest=root/'app/src/main/AndroidManifest.xml'
gradle=root/'app/build.gradle'
for f in [act,home,manifest,gradle]:
    assert f.is_file(),str(f)
s=act.read_text()
marker='    button(root,"PARAR SINCRONIZACAO SUPERVISIONADA",this::stopSupervised);'
assert s.count(marker)==1
addition='''    button(root,"ATIVAR SINCRONIZACAO AUTOMATICA NESTE WI-FI",()->{
      if(!agree.isChecked()||metered.isChecked()){
        update("Exige consentimento e Wi-Fi nao medido, sem hotspot celular.");return;
      }
      try{
        String ssid=FleetWifiAutoSyncJob.connectedSsid(this);
        if(ssid==null){
          update("SSID indisponivel. Autorize localizacao e habilite localizacao no Android.");
          if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=
              android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},34018);
          return;
        }
        FleetCloudUploader.Config cfg=configured();
        if(cfg.allowMeteredWifi)throw new IllegalStateException("Wi-Fi medido nao permitido");
        FleetCloudSecretStore.save(this,cfg);
        FleetWifiAutoSyncJob.enable(this,ssid);
        deviceToken.setText("");
        update("Autorizado somente no Wi-Fi "+ssid+". Tentativas periodicas dependem da central ligada.");
      }catch(Exception ex){update("Nao ativado: "+ex.getMessage());}
    });
    button(root,"DESATIVAR SINCRONIZACAO AUTOMATICA",()->{
      FleetWifiAutoSyncJob.disable(this);
      update("Envio automatico desligado. Os MP4s locais nao foram alterados.");
    });
'''
s=s.replace(marker,marker+'\n'+addition)
needle='      FleetCloudSecretStore.clear(this);'
assert s.count(needle)==1
s=s.replace(needle,'      FleetWifiAutoSyncJob.disable(this);\n'+needle)
act.write_text(s)
s=home.read_text()
needle='    SharedPreferences prefs=getSharedPreferences(FleetProfileActivity.PREFS,MODE_PRIVATE);'
assert s.count(needle)==1
home.write_text(s.replace(needle,'    FleetWifiAutoSyncJob.restoreWhenAppOpens(this);\n'+needle))
s=manifest.read_text()
needle='<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>'
assert s.count(needle)==1
s=s.replace(needle,needle+'\n  <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>')
needle='<service android:name=".FleetCloudSyncService" android:exported="false" android:foregroundServiceType="dataSync" />'
assert s.count(needle)==1
s=s.replace(needle,needle+'\n    <service android:name=".FleetWifiAutoSyncJob" android:permission="android.permission.BIND_JOB_SERVICE" android:exported="true" />')
manifest.write_text(s)
s=gradle.read_text()
needle="versionCode 33; versionName '3.3.0-readonly-telemetry'"
assert s.count(needle)==1
gradle.write_text(s.replace(needle,"versionCode 34; versionName '3.4.0-approved-wifi'"))
print("Android v3.4 patch applied")
