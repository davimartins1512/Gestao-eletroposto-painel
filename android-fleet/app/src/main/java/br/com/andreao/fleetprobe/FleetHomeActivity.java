package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Daily-use starting point for ONE BYD head unit; no camera, network or
 * microphone starts automatically. A single acceptance report covers
 * available functions without repeated plug/unplug or camera tests.
 */
public final class FleetHomeActivity extends Activity {
  private LinearLayout root;
  private TextView vehicle,status;
  @Override public void onCreate(Bundle savedState){
    super.onCreate(savedState);
    ScrollView scroll=new ScrollView(this);
    root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(30,26,30,32);
    root.setBackgroundColor(Color.rgb(246,249,248));
    TextView brand=new TextView(this);
    brand.setText("ANDREAO  /  FLEET");
    brand.setTextColor(0xff0d673c);
    brand.setTextSize(26);
    brand.setGravity(Gravity.START);
    root.addView(brand);
    text("CENTRAL DO VEÍCULO  ·  v2.2",12,0xff53665b);
    vehicle=text("Carro local",20,0xff153c2b);
    status=text("Gravações offline · clipes locais · nenhum envio automático",14,0xff53665b);
    section("GRAVAÇÃO E OCORRÊNCIAS");
    button("GRAVAR VÍDEO · INÍCIO MANUAL COM CARRO EM P",OemFrameProbeActivity.class,
      "Câmera OEM, MP4 de 90s e áudio opcional informado. Não gravar em movimento");
    button("VER VÍDEOS · REPRODUZIR / PROTEGER / EXPORTAR",OemRecordingLibraryActivity.class,
      "Galeria interna e USB, ocorrências protegidas, envio local para o telefone");
    button("ENVIAR VÍDEO AO CELULAR · WI-FI LOCAL",FleetDataControlActivity.class,
      "Selecione o MP4 primeiro na galeria; link único no hotspot privado");
    section("FROTA E DADOS");
    button("GPS LOCAL · TRAJETO COM NOTIFICAÇÃO",FleetTripActivity.class,
      "Localização registrada localmente por ação do responsável");
    button("VEÍCULO · IDENTIFICAÇÃO E CAPACIDADES",FleetProfileActivity.class,
      "Separação entre veículos, leitura real versus recursos ainda não disponíveis");
    button("SAÚDE DO GRAVADOR · ESPAÇO E INDICADORES",FleetHealthActivity.class,
      "Uso real da mídia, política circular, relatório e estado do armazenamento");
    button("CONFIGURAÇÕES · USB, MEMÓRIA, QUALIDADE, RETENÇÃO",StoragePreferencesActivity.class,
      "Sem remover o pendrive: reconhecimento na próxima rotação");
    section("ENTREGA E HOMOLOGAÇÃO");
    button("GERAR UM ÚNICO RELATÓRIO GERAL",FleetAcceptanceActivity.class,
      "Verifica tudo o que pode ser confirmado automaticamente, sem abrir câmera ou precisar remover USB");
    button("FERRAMENTAS TÉCNICAS (OPCIONAL)",LaunchActivity.class,
      "Diagnóstico original preservado apenas para investigar uma falha concreta");
    text("INTEGRAÇÕES NÃO LIBERADAS: READY/P e freio por dados veiculares, "+
      "SOC/SOH real, 12V real, vídeo remoto em nuvem e gravação com carro em movimento. "+
      "Não são simuladas. A segurança da câmera BYD permanece prioritária.",12,0xff8c4c11);
    scroll.addView(root);
    setContentView(scroll);
  }
  private TextView text(String content,int size,int color){
    TextView t=new TextView(this);
    t.setText(content);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setPadding(0,8,0,12);
    root.addView(t);
    return t;
  }
  private void section(String name){
    TextView section=text(name,13,0xff147447);
    section.setPadding(0,20,0,4);
  }
  private void button(String title,Class<?> screen,String detail){
    LinearLayout card=new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(15,14,15,15);
    card.setBackgroundColor(0xffffffff);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
    lp.setMargins(0,5,0,9);
    root.addView(card,lp);
    Button action=new Button(this);
    action.setText(title);
    action.setTextSize(14);
    action.setAllCaps(false);
    action.setOnClickListener(v->startActivity(new Intent(this,screen)));
    card.addView(action);
    TextView sub=new TextView(this);
    sub.setText(detail);
    sub.setTextSize(12);
    sub.setTextColor(0xff53665b);
    card.addView(sub);
  }
  @Override protected void onResume(){
    super.onResume();
    SharedPreferences prefs=getSharedPreferences(FleetProfileActivity.PREFS,MODE_PRIVATE);
    String alias=prefs.getString("vehicle_alias","Dolphin Mini · veículo de validação");
    vehicle.setText(alias);
    String note=OemProbeCheckpoint.read(getApplicationContext());
    boolean hasCheckpoint=note.contains("Fase: ");
    status.setText("OFFLINE POR PADRÃO · MP4 no carro/USB · envio apenas por solicitação"+
      (hasCheckpoint?"\nÚltima sessão OEM registrada no aparelho":""));
  }
}
