package br.com.andreao.fleetprobe;

/**
 * Zero-cellular-by-default transfer policy. Pure-Java gate for a future
 * authenticated per-file cloud transfer. Current app has only a manual inbound
 * private-Wi-Fi HTTP server for single-clip download; no outbound upload client.
 *
 * Wi-Fi can be backed by somebody else's limited data. Therefore an explicitly
 * acknowledged, validated Wi-Fi network is needed for a temporary upload.
 * A network losing Wi-Fi between chunks MUST abort any future transfer.
 */
public final class FleetDataPolicy {
  private FleetDataPolicy(){}
  public static final long MIB=1024L*1024L;
  public static final int SESSION_MINUTES=10;
  public static final long MAX_SELECT_BYTES=256L*MIB;
  public enum Reason {
    ALLOWED,NO_USER_REQUEST,NO_WIFI,NO_VALIDATED_INTERNET,
    CELLULAR_TRANSPORT,NO_HOTSPOT_CONFIRMATION,FILE_TOO_LARGE,
    SESSION_EXPIRED,NO_MONTHLY_BUDGET
  }
  public static final class Verdict {
    public final Reason reason;
    public final String message;
    Verdict(Reason reason,String message){this.reason=reason;this.message=message;}
    public boolean permitted(){return reason==Reason.ALLOWED;}
  }
  public static Verdict evaluate(boolean requested,boolean wifi,boolean validated,
    boolean cellular,boolean hotspotConfirmed,long bytes,long nowMs,long expiryMs,
    long remainingMonthlyBytes){
    if(!requested)return new Verdict(Reason.NO_USER_REQUEST,"Nenhum envio autorizado.");
    if(!wifi)return new Verdict(Reason.NO_WIFI,"Sem Wi-Fi: não enviar usando chip do carro.");
    if(cellular)return new Verdict(Reason.CELLULAR_TRANSPORT,
      "Rede celular detectada no caminho: envio bloqueado.");
    if(!validated)return new Verdict(Reason.NO_VALIDATED_INTERNET,
      "Wi-Fi sem internet validada: não enviar.");
    if(!hotspotConfirmed)return new Verdict(Reason.NO_HOTSPOT_CONFIRMATION,
      "Confirme que o Wi-Fi é o roteador do seu telefone.");
    if(nowMs>=expiryMs)return new Verdict(Reason.SESSION_EXPIRED,
      "Autorização temporária expirou; confirme novamente.");
    if(bytes<=0||bytes>MAX_SELECT_BYTES)return new Verdict(Reason.FILE_TOO_LARGE,
      "Clipe acima do limite da operação; reduza ou exporte localmente.");
    if(remainingMonthlyBytes<bytes)return new Verdict(Reason.NO_MONTHLY_BUDGET,
      "Envio acima do orçamento de dados autorizado; não enviar.");
    return new Verdict(Reason.ALLOWED,
      "Rede Wi-Fi validada, autorização temporária e orçamento suficientes. "+
      "Ainda requer transporte seguro e autenticação; política sozinha não envia arquivos.");
  }
}