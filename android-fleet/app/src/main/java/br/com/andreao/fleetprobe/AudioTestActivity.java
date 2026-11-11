package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

/** Teste de 5 segundos, explicitamente autorizado. Nunca mantém o áudio gravado. */
public final class AudioTestActivity extends Activity {
  private final Handler handler=new Handler(Looper.getMainLooper());
  private MediaRecorder recorder;
  private File temporary;
  private TextView status;
  private boolean active=false;
  private int peak=0;
  private static final int AUDIO_PERMISSION=300;
  private final Runnable finishTask=()->stop(true);
  private final Runnable sample=new Runnable(){
    @Override public void run(){
      if(!active||recorder==null)return;
      try{peak=Math.max(peak,recorder.getMaxAmplitude());}catch(Throwable ignored){}
      handler.postDelayed(this,500);
    }
  };
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    LinearLayout layout=new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
    TextView headline=new TextView(this);headline.setTextSize(22);
    headline.setText("ANDREAO Fleet · microfone");
    layout.addView(headline);
    status=new TextView(this);status.setTextSize(16);
    status.setText("Apenas com o carro parado. O teste grava 5 segundos localmente e apaga o áudio. NÃO é iniciado automaticamente. Todos os ocupantes devem concordar.");
    layout.addView(status);
    Button start=new Button(this);start.setText("Autorizar e testar por 5 segundos");
    start.setOnClickListener(v->confirm());layout.addView(start);
    Button stop=new Button(this);stop.setText("Cancelar teste / fechar");
    stop.setOnClickListener(v->{stop(false);finish();});layout.addView(stop);
    setContentView(layout);
  }
  private void confirm(){
    if(active)return;
    new AlertDialog.Builder(this).setTitle("Permissão para testar o microfone")
      .setMessage("O veículo está estacionado, TODOS os ocupantes foram informados e concordaram com a captura temporária de 5 segundos? A gravação será apagada ao final.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Sim, autorizamos",(d,w)->{
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
          requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION);
        else start();
      }).show();
  }
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==AUDIO_PERMISSION){
      if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)start();
      else status.setText("Acesso ao microfone negado; nada foi gravado.");
    }
  }
  private void start(){
    if(active)return;
    try{
      temporary=File.createTempFile("andreao_mic_test_",".3gp",getCacheDir());
      recorder=new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
      recorder.setOutputFile(temporary.getAbsolutePath());
      recorder.prepare();
      recorder.start();
      active=true;peak=0;
      status.setText("Teste ativo: gravando localmente por no máximo 5 segundos...");
      handler.postDelayed(sample,500);
      handler.postDelayed(finishTask,5000);
    }catch(Throwable e){
      status.setText("Falha no microfone: "+e.getClass().getSimpleName()+" · "+e.getMessage());
      stop(false);
    }
  }
  private void stop(boolean finished){
    handler.removeCallbacks(sample);
    handler.removeCallbacks(finishTask);
    boolean ok=active;
    active=false;
    if(recorder!=null){
      try{recorder.stop();}catch(Throwable e){ok=false;}
      try{recorder.reset();recorder.release();}catch(Throwable ignored){}
      recorder=null;
    }
    if(temporary!=null){try{temporary.delete();}catch(Throwable ignored){}temporary=null;}
    if(finished)status.setText(ok
      ?"Teste do microfone concluído. Pico medido: "+peak+". Arquivo temporário apagado. Pico zero pode ser silêncio, microfone bloqueado ou indisponível."
      :"Teste não concluído. Nenhum áudio foi mantido.");
  }
  @Override protected void onStop(){stop(false);super.onStop();}
  @Override protected void onDestroy(){stop(false);super.onDestroy();}
}
