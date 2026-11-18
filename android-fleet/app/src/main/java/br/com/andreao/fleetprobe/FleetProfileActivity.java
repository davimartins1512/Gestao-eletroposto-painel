package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

/** One physical head unit = one local fleet vehicle identity. */
public final class FleetProfileActivity extends Activity {
  public static final String PREFS="andreao_fleet_vehicle_local";
  private EditText alias;
  private Spinner model;
  private TextView status;
  private SharedPreferences prefs;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
    ScrollView scroll=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(28,22,28,30);
    TextView title=new TextView(this);title.setText("IDENTIFICAÇÃO LOCAL DO VEÍCULO");
    title.setTextSize(22);root.addView(title);
    TextView note=new TextView(this);note.setText("Este nome fica somente neste carro. "+
      "O painel web de frota tem cadastros separados, mas ainda não há "+
      "sincronização direta com esta central. Nenhum identificador é enviado.");
    root.addView(note);
    alias=new EditText(this);
    alias.setSingleLine(true);alias.setHint("Ex.: Mini 01 · Andréão");
    alias.setText(prefs.getString("vehicle_alias","Dolphin Mini · veículo de validação"));
    root.addView(alias);
    model=new Spinner(this);
    String[] models={"BYD Dolphin Mini","BYD Yuan Plus","BYD Dolphin Plus","Outro BYD / compatibilidade não verificada"};
    model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,models));
    model.setSelection(Math.max(0,Math.min(models.length-1,prefs.getInt("model_index",0))));
    root.addView(model);
    Button save=new Button(this);save.setText("SALVAR PERFIL NESTE CARRO");
    save.setAllCaps(false);save.setOnClickListener(v->save());
    root.addView(save);
    status=new TextView(this);
    status.setTextIsSelectable(true);
    status.setPadding(0,14,0,10);
    root.addView(status);
    scroll.addView(root);setContentView(scroll);
    updateCapabilities();
  }
  private void save(){
    String name=alias.getText().toString().trim();
    if(name.isEmpty()||name.length()>80){status.setText("Digite identificação de até 80 caracteres.");return;}
    prefs.edit().putString("vehicle_alias",name).putInt("model_index",model.getSelectedItemPosition()).apply();
    updateCapabilities();
  }
  private void updateCapabilities(){
    boolean mini=model.getSelectedItemPosition()==0;
    status.setText("IDENTIDADE LOCAL: "+prefs.getString("vehicle_alias","Não cadastrada")+
      "\nModelo: "+model.getSelectedItem().toString()+
      "\nCâmera OEM: "+(mini?"gravação manual validada no Mini de ensaio":
        "NÃO validada neste modelo; não assumir mesma API")+
      "\nArmazenamento local, MP4, galeria, retenção, Wi-Fi LAN: funções do aplicativo"+
      "\nGPS local: depende da permissão e sinal do dispositivo"+
      "\nSOC/SOH da bateria de tração: SEM FONTE AUTENTICADA"+
      "\n12V real / READY / P / freio: NÃO VERIFICADOS"+
      "\nLive remoto / painel em nuvem: AINDA NÃO CONECTADO"+
      "\nOperação de câmeras durante manobras: NÃO VALIDADA — NÃO USAR.");
  }
}
