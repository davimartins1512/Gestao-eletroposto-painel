package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
  private static final int CAMERA_PERMISSION=40, AUDIO_PERMISSION=41, EXPORT=42;
  CameraManager manager;
  CameraDevice device;
  CameraCaptureSession session;
  MediaRecorder recorder;
  Surface previewSurface;
  TextureView preview;
  Spinner selector;
  TextView status;
  CheckBox parked, informed;
  Button open, video, audio, stop, export;
  String[] ids=new String[0];
  Size size=new Size(640,480);
  File current,last;
  boolean recording=false,pending=false,closing=false,waitingAudio=false;
  final Handler handler=new Handler(Looper.getMainLooper());

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    manager=(CameraManager)getSystemService(CAMERA_SERVICE);
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(20,20,20,20);
    box.setBackgroundColor(0xfff4f9f5);
    TextView title=new TextView(this);
    title.setText("ANDREAO FLEET · DIAGNÓSTICO");
    title.setTextSize(22); title.setTextColor(0xff168244);
    box.addView(title);
    box.addView(label("Teste local v0.2 · sem internet · sem root · somente com o veículo parado."));
    parked=new CheckBox(this);
    parked.setText("Carro estacionado em P, com freio acionado");
    box.addView(parked);
    informed=new CheckBox(this);
    informed.setText("Todos os ocupantes informados e de acordo com a gravação de áudio");
    box.addView(informed);
    selector=new Spinner(this);box.addView(selector);
    preview=new TextureView(this);preview.setBackgroundColor(0xff11261a);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,230);lp.topMargin=10;box.addView(preview,lp);
    status=label("Verificando compatibilidade...");
    status.setTextIsSelectable(true);status.setPadding(0,12,0,12);
    box.addView(status);
    open=button(box,"Abrir câmera Android",()->openCamera());
    video=button(box,"Gravar vídeo sem áudio",()->startRecording(false));
    audio=button(box,"Gravar vídeo COM áudio (autorizado)",()->startRecording(true));
    stop=button(box,"Parar e salvar",()->stopRecording());
    export=button(box,"Exportar última gravação",()->exportVideo());
    box.addView(label("IMPORTANTE: as quatro câmeras 360° originais do BYD podem estar protegidas e não aparecer no Camera2. "+
      "Se aparecer '0 câmeras', este teste não terá vídeo. Não interfere na câmera de ré. "+
      "Vídeo local não é um sistema de segurança comercial e nenhuma gravação é enviada ao servidor."));
    ScrollView scroll=new ScrollView(this);scroll.addView(box);setContentView(scroll);
    preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
      @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){if(device!=null&&!recording&&!pending)startPreview();}
      @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
      @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){closeSession();return true;}
      @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}
    });
    listCameras();
  }
  private TextView label(String s) {
    TextView v=new TextView(this);v.setText(s);v.setTextSize(14);v.setTextColor(Color.DKGRAY);return v;
  }
  private Button button(LinearLayout box,String title,Runnable action) {
    Button b=new Button(this);b.setText(title);b.setAllCaps(false);box.addView(b);
    b.setOnClickListener(v->action.run());return b;
  }
  private void message(String s){runOnUiThread(()->status.setText(s));}
  private boolean safe(){if(!parked.isChecked()){Toast.makeText(this,"Confirme carro parado em P.",Toast.LENGTH_SHORT).show();return false;}return true;}
  private void ui(){
    open.setEnabled(!closing&&!recording&&!pending&&ids.length>0);
    video.setEnabled(!closing&&device!=null&&!pending&&!recording);
    audio.setEnabled(!closing&&device!=null&&!pending&&!recording);
    stop.setEnabled(recording);
    export.setEnabled(!recording&&last!=null&&last.exists());
  }
  private void listCameras(){
    ArrayList<String> labels=new ArrayList<>();
    try{
      ids=manager.getCameraIdList();
      for(String id:ids){
        Integer f=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        labels.add("Camera2 ID "+id+" · "+(f==null?"desconhecida":f==CameraCharacteristics.LENS_FACING_BACK?"traseira":f==CameraCharacteristics.LENS_FACING_FRONT?"frontal":"externa"));
      }
    }catch(Exception e){ids=new String[0];}
    if(labels.isEmpty())labels.add("0 câmeras acessíveis pelo Android Camera2");
    selector.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
    message("Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\nFirmware: "+
      Build.DISPLAY+"\nCamera2: "+ids.length+" câmeras acessíveis.\n"+
      (ids.length==0?"As câmeras 360° originais provavelmente usam interface BYD protegida.":"Abra uma câmera com o carro estacionado."));
    ui();
  }
  private void openCamera(){
    if(!safe()||ids.length==0||recording||pending||closing)return;
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);return;
    }
    closeCamera();
    try{
      String id=ids[selector.getSelectedItemPosition()];
      StreamConfigurationMap map=manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      Size[] sizes=map==null?null:map.getOutputSizes(MediaRecorder.class);
      if(sizes==null||sizes.length==0){message("Câmera sem saída compatível com MediaRecorder.");return;}
      size=sizes[0];
      for(Size s:sizes)if(s.getWidth()==640&&s.getHeight()==480)size=s;
      for(Size s:sizes)if(s.getWidth()==1280&&s.getHeight()==720)size=s;
      manager.openCamera(id,new CameraDevice.StateCallback(){
        @Override public void onOpened(CameraDevice c){if(closing){c.close();return;}device=c;message("Câmera aberta. Teste sem dirigir. Resolução: "+size);ui();startPreview();}
        @Override public void onDisconnected(CameraDevice c){c.close();device=null;message("Câmera desconectada.");ui();}
        @Override public void onError(CameraDevice c,int error){c.close();device=null;message("Sistema BYD recusou ou reservou a câmera ("+error+").");ui();}
      },handler);
    }catch(Exception e){message("Falha abrindo câmera: "+e.getClass().getSimpleName());}
  }
  private void closeSession(){
    if(session!=null){try{session.close();}catch(Exception ignored){}session=null;}
    if(previewSurface!=null){previewSurface.release();previewSurface=null;}
  }
  private void startPreview(){
    if(closing||device==null||!preview.isAvailable()||recording||pending)return;
    try{
      closeSession();
      SurfaceTexture texture=preview.getSurfaceTexture();
      if(texture==null)return;
      texture.setDefaultBufferSize(size.getWidth(),size.getHeight());
      previewSurface=new Surface(texture);
      CaptureRequest.Builder req=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      req.addTarget(previewSurface);
      device.createCaptureSession(Arrays.asList(previewSurface),new CameraCaptureSession.StateCallback(){
        @Override public void onConfigured(CameraCaptureSession c){
          if(closing||device==null){c.close();return;}
          session=c;
          try{c.setRepeatingRequest(req.build(),null,handler);}
          catch(Exception e){message("Prévia indisponível.");}
        }
        @Override public void onConfigureFailed(CameraCaptureSession c){message("Prévia rejeitada pelo sistema.");}
      },handler);
    }catch(Exception e){message("Prévia falhou: "+e.getClass().getSimpleName());}
  }
  private void startRecording(boolean withAudio){
    if(!safe()||device==null||recording||pending||closing)return;
    if(!withAudio){beginRecording(false);return;}
    if(!informed.isChecked()){message("Para áudio, informe os ocupantes e confirme a autorização.");return;}
    new AlertDialog.Builder(this).setTitle("Atenção: áudio da cabine")
      .setMessage("O microfone pode registrar conversas dos ocupantes. Todos foram informados e concordaram com o teste?")
      .setNegativeButton("Cancelar",null).setPositiveButton("Confirmo",(d,w)->{
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
          waitingAudio=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION);
        }else beginRecording(true);
      }).show();
  }
  private void beginRecording(boolean withAudio){
    if(!safe()||device==null||pending||recording||closing)return;
    if(withAudio&&(!informed.isChecked()||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED))return;
    pending=true;ui();
    try{
      File dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES);
      if(dir==null)dir=new File(getFilesDir(),"videos");
      if(!dir.exists()&&!dir.mkdirs())throw new IOException("Armazenamento indisponível");
      current=new File(dir,"ANDREAO_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+(withAudio?"_AUDIO":"_VIDEO")+".mp4");
      recorder=new MediaRecorder();
      if(withAudio)recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
      recorder.setVideoEncodingBitRate(2500000);
      recorder.setVideoFrameRate(24);
      recorder.setVideoSize(size.getWidth(),size.getHeight());
      if(withAudio){recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioSamplingRate(44100);recorder.setAudioEncodingBitRate(96000);}
      recorder.setOutputFile(current.getAbsolutePath());
      recorder.prepare();
      closeSession();
      SurfaceTexture tex=preview.getSurfaceTexture();
      if(tex==null)throw new IOException("Prévia fechada");
      tex.setDefaultBufferSize(size.getWidth(),size.getHeight());
      previewSurface=new Surface(tex);
      Surface recordSurface=recorder.getSurface();
      CaptureRequest.Builder req=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
      req.addTarget(previewSurface);req.addTarget(recordSurface);
      device.createCaptureSession(Arrays.asList(previewSurface,recordSurface),new CameraCaptureSession.StateCallback(){
        @Override public void onConfigured(CameraCaptureSession c){
          if(closing||device==null||!pending){c.close();fail("Gravação interrompida.");return;}
          session=c;
          try{
            c.setRepeatingRequest(req.build(),null,handler);
            recorder.start();recording=true;pending=false;ui();
            message(withAudio?"● GRAVANDO · MICROFONE ATIVO":"● GRAVANDO · SEM ÁUDIO");
            handler.postDelayed(()->{if(recording)stopRecording();},60000); // Teste: limite de 60 segundos
          }catch(Exception e){fail("Falha iniciando gravação: "+e.getClass().getSimpleName());}
        }
        @Override public void onConfigureFailed(CameraCaptureSession c){fail("A câmera não permite gravação padrão.");}
      },handler);
    }catch(Exception e){fail("Erro no gravador: "+e.getClass().getSimpleName());}
  }
  private void fail(String s){
    pending=false;recording=false;
    if(recorder!=null){try{recorder.reset();recorder.release();}catch(Exception ignored){}recorder=null;}
    if(current!=null){current.delete();current=null;}
    message(s);ui();
    if(!closing&&device!=null)startPreview();
  }
  private void stopRecording(){
    if(!recording&&!pending)return;
    closeSession();
    boolean valid=false;
    try{if(recording&&recorder!=null){recorder.stop();valid=true;}}
    catch(RuntimeException ignored){message("Arquivo de vídeo incompleto, gravação descartada.");}
    finally{if(recorder!=null){try{recorder.reset();recorder.release();}catch(Exception ignored){}recorder=null;}}
    if(valid&&current!=null&&current.length()>0){
      last=current;
      message("Vídeo salvo localmente: "+last.getName()+" · "+(last.length()/1024)+" KB");
      final File finished=last;
      new Thread(()->{String sha=hash(finished);if(sha!=null)message("Vídeo salvo: "+finished.getName()+"\nSHA-256: "+sha);}).start();
    }else if(current!=null)current.delete();
    current=null;recording=false;pending=false;ui();
    if(!closing&&device!=null)startPreview();
  }
  private String hash(File file){
    try(InputStream in=new FileInputStream(file)){
      MessageDigest d=MessageDigest.getInstance("SHA-256");
      byte[] buf=new byte[8192];int n;
      while((n=in.read(buf))>0)d.update(buf,0,n);
      StringBuilder out=new StringBuilder();
      for(byte b:d.digest())out.append(String.format(Locale.US,"%02x",b&255));
      return out.toString();
    }catch(Exception ignored){return null;}
  }
  private void exportVideo(){
    if(last==null||!last.exists()||recording)return;
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("video/mp4");i.putExtra(Intent.EXTRA_TITLE,last.getName());
    startActivityForResult(i,EXPORT);
  }
  @Override public void onActivityResult(int code,int result,Intent data){
    super.onActivityResult(code,result,data);
    if(code!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null||last==null)return;
    Uri uri=data.getData();File source=last;
    new Thread(()->{
      try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){
        if(out==null)throw new IOException("Destino indisponível");
        byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1)out.write(buf,0,n);
        message("Vídeo exportado: "+source.getName());
      }catch(Exception e){message("Erro exportando: "+e.getMessage());}
    }).start();
  }
  @Override public void onRequestPermissionsResult(int req,String[] perms,int[] grants){
    super.onRequestPermissionsResult(req,perms,grants);
    boolean granted=grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED;
    if(req==CAMERA_PERMISSION){if(granted)openCamera();else message("Câmera sem permissão.");}
    else if(req==AUDIO_PERMISSION){boolean ready=waitingAudio;waitingAudio=false;
      if(granted&&ready&&informed.isChecked())beginRecording(true);else message("Áudio cancelado.");
    }
  }
  private void closeCamera(){
    if(recording||pending)stopRecording();
    closeSession();if(device!=null){device.close();device=null;}ui();
  }
  @Override protected void onPause(){closing=true;closeCamera();super.onPause();}
  @Override protected void onResume(){super.onResume();closing=false;if(open!=null)ui();}
  @Override protected void onDestroy(){closeCamera();super.onDestroy();}
}
