package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Preferencias locais, todas ajustaveis e desativadas como controles externos.
 * Nao promete gravacao, acesso OEM, nem atualizacao remota automatica.
 */
public final class StoragePreferencesActivity extends Activity {
  private static final String[] TARGETS={"Automático (USB quando acessível)","Memória interna","USB/cartão removível"};
  private static final String[] TARGET_VALUES={"auto","internal","removable"};
  private final Map<String,EditText> values=new LinkedHashMap<>();
  private SharedPreferences prefs;
  private Spinner target,quality,liveQuality;
  private CheckBox autoswitch,fallback;
  private TextView status;
  private LinearLayout layout;

  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    prefs=getSharedPreferences(StoragePolicy.PREFS,MODE_PRIVATE);
    ScrollView view=new ScrollView(this);
    layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(28,20,28,22);
    title("ANDREAO Fleet · armazenamento e limites");
    info("As configurações abaixo se aplicam à GRAVAÇÃO MANUAL DE TESTE da câmera BYD. Gravação 24h, live e sentinela ainda NÃO estão operacionais e precisam de validação no veículo.");
    target=spinner("Onde salvar vídeos",TARGETS,index(TARGET_VALUES,prefs.getString("storage_target","auto")));
    autoswitch=toggle("Trocar automaticamente para USB se aparecer",prefs.getBoolean("removable_autoswitch",true));
    fallback=toggle("Voltar para memória interna se USB for removido",prefs.getBoolean("fallback_internal",true));
    numeric("max_storage_gb","Teto de espaço em GB (vazio = sem teto)",prefs.getFloat("max_storage_gb",0f),0.5,8192,true);
    numeric("reserve_free_gb","Manter livre no disco (GB)",prefs.getFloat("reserve_free_gb",2f),0.25,1024,false);
    numeric("retention_hours","Retenção circular (horas)",prefs.getFloat("retention_hours",24f),1,720,false);
    numeric("segment_seconds","Duração dos clipes MP4 (segundos; padrão 90 = 1min30)",prefs.getFloat("segment_seconds",90f),10,600,false);
    numeric("test_duration_minutes","Duração do teste prolongado (minutos)",prefs.getFloat("test_duration_minutes",5f),2,30,false);
    quality=spinner("Resolução local",new String[]{"Automática","720p","1080p","Original OEM"},
      index(new String[]{"auto","720p","1080p","oem"},prefs.getString("video_quality","auto")));
    numeric("fps","FPS da gravação",prefs.getFloat("fps",20f),5,60,false);
    numeric("bitrate_mbps","Bitrate de vídeo (Mbps)",prefs.getFloat("bitrate_mbps",4f),0.25,50,false);
    liveQuality=spinner("Qualidade da câmera ao vivo",new String[]{"Automática","Baixa","Padrão","Alta"},
      index(new String[]{"auto","low","standard","high"},prefs.getString("live_quality","auto")));
    numeric("max_mobile_data_gb","Limite de alerta de dados (GB/mês; vazio = sem)",prefs.getFloat("max_mobile_data_gb",0f),0.1,10000,true);
    numeric("mobile_data_alert_pct","Alerta de consumo de dados (%)",prefs.getFloat("mobile_data_alert_pct",80f),10,100,false);
    numeric("parked_min_voltage_12v","Tensão mínima 12V sentinela (V)",prefs.getFloat("parked_min_voltage_12v",12.2f),11.5,13.5,false);
    info("O corte por tensão só será aplicado após medição real de 12V. O piloto permite foto e MP4 com início manual; não controla o carro, não inicia automaticamente e não faz escuta oculta.");
    Button save=new Button(this);save.setAllCaps(false);save.setText("SALVAR CONFIGURAÇÕES");
    save.setOnClickListener(v->save());
    layout.addView(save);
    Button inspect=new Button(this);inspect.setAllCaps(false);
    inspect.setText("VERIFICAR MEMÓRIA INTERNA, PENDRIVE E TESTAR ESCRITA");
    inspect.setOnClickListener(v->inspect());
    layout.addView(inspect);
    status=new TextView(this);status.setTextSize(14);
    status.setText("O teste de escrita cria e apaga somente um arquivo temporário na pasta do ANDREAO Fleet.");
    layout.addView(status);
    view.addView(layout);setContentView(view);
  }
  private int index(String[] possibilities,String value){
    for(int i=0;i<possibilities.length;i++)if(possibilities[i].equals(value))return i;
    return 0;
  }
  private void title(String text){
    TextView t=new TextView(this);t.setText(text);t.setTextSize(22);t.setPadding(0,5,0,15);layout.addView(t);
  }
  private void info(String text){
    TextView t=new TextView(this);t.setText(text);t.setTextSize(13);t.setPadding(0,5,0,13);layout.addView(t);
  }
  private Spinner spinner(String caption,String[] list,int selected){
    info(caption);
    Spinner spinner=new Spinner(this);
    spinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,list));
    spinner.setSelection(selected);
    layout.addView(spinner);return spinner;
  }
  private CheckBox toggle(String caption,boolean checked){
    CheckBox c=new CheckBox(this);c.setText(caption);c.setChecked(checked);
    layout.addView(c);return c;
  }
  private void numeric(String key,String caption,float current,double min,double max,boolean nullable){
    info(caption);
    EditText entry=new EditText(this);
    entry.setSingleLine(true);
    entry.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    entry.setText(nullable&&current<=0?"":String.format(Locale.US,"%s",(current==(int)current?(int)current:current)));
    entry.setTag(new double[]{min,max,nullable?1:0});
    entry.setSelectAllOnFocus(true);
    values.put(key,entry);layout.addView(entry);
  }
  private void save(){
    LinkedHashMap<String,Float> parsed=new LinkedHashMap<>();
    for(Map.Entry<String,EditText> x:values.entrySet()){
      String text=x.getValue().getText().toString().trim();
      double[] range=(double[])x.getValue().getTag();
      if(text.isEmpty()&&range[2]>0){parsed.put(x.getKey(),0f);continue;}
      float f;
      try{f=Float.parseFloat(text);}
      catch(Exception e){status.setText("Valor inválido em "+x.getKey());return;}
      if(!Float.isFinite(f)||f<range[0]||f>range[1]){
        status.setText("Valor fora do intervalo em "+x.getKey()+": "+range[0]+" a "+range[1]);return;
      }
      parsed.put(x.getKey(),f);
    }
    SharedPreferences.Editor writer=prefs.edit();
    writer.putString("storage_target",TARGET_VALUES[target.getSelectedItemPosition()]);
    writer.putBoolean("removable_autoswitch",autoswitch.isChecked());
    writer.putBoolean("fallback_internal",fallback.isChecked());
    String[] qualities={"auto","720p","1080p","oem"},live={"auto","low","standard","high"};
    writer.putString("video_quality",qualities[quality.getSelectedItemPosition()]);
    writer.putString("live_quality",live[liveQuality.getSelectedItemPosition()]);
    for(Map.Entry<String,Float> x:parsed.entrySet())writer.putFloat(x.getKey(),x.getValue());
    writer.apply();
    status.setText("Preferências salvas. Serão usadas pelo piloto manual de MP4 quando houver encoder e câmera compatíveis. Gravação automática ainda não validada.");
  }
  private void inspect(){
    status.setText("Verificando os locais de armazenamento do Android...");
    new Thread(()->{
      String result;
      try{result=StoragePolicy.describe(getApplicationContext(),prefs);}
      catch(Throwable e){result="Não foi possível verificar volumes: "+e.getClass().getSimpleName();}
      final String display=result;
      runOnUiThread(()->status.setText(display));
    },"fleet-storage-probe").start();
  }
}
