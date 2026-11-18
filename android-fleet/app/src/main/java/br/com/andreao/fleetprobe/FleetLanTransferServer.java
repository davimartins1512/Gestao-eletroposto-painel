package br.com.andreao.fleetprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Human-initiated, one-clip LAN transfer. Not an internet uploader.
 *
 * ServerSocket binds to the CURRENT Wi-Fi IPv4 address, never wildcard/0.0.0.0.
 * It never opens an outbound connection and performs no DNS requests.
 * A random 256-bit capability is valid for one completed GET and <= 10 min.
 * Network identity / Wi-Fi-only route is rechecked during every chunk.
 *
 * This is HTTP inside a private WPA2/WPA3 phone hotspot, not TLS. Do NOT
 * expose to public Wi-Fi or an untrusted local network. Never auto-start.
 * Vehicle state and rear camera are not accessed.
 */
public final class FleetLanTransferServer {
  private static final long WINDOW_MS=10*60*1000L;
  private static final int CHUNK=32768;
  private final Context app;
  private final File file;
  private final long originalBytes;
  private final String token;
  private volatile ServerSocket listener;
  private volatile Network boundNetwork;
  private volatile String url="",status="Preparando transferência Wi-Fi...";
  private volatile boolean running,used,closed;
  private volatile long bytesSent;
  private Thread worker;

  private FleetLanTransferServer(Context app,File file) {
    this.app=app;
    this.file=file;
    originalBytes=file.length();
    byte[] secret=new byte[32];
    new SecureRandom().nextBytes(secret);
    StringBuilder b=new StringBuilder(secret.length*2);
    for(byte x:secret)b.append(String.format(Locale.US,"%02x",x&255));
    token=b.toString();
  }

  public static FleetLanTransferServer create(Context context,File candidate) {
    File file=candidate==null?null:
      OemRecordingLibrary.resolve(context,candidate.getAbsolutePath());
    if(file==null||file.length()<2048||file.length()>FleetDataPolicy.MAX_SELECT_BYTES)
      throw new IllegalArgumentException("Selecione um MP4 finalizado do Fleet de até 256 MiB");
    return new FleetLanTransferServer(context.getApplicationContext(),file);
  }

  public synchronized void start() {
    if(worker!=null)throw new IllegalStateException("Transferência já iniciada");
    worker=new Thread(this::serve,"fleet-wifi-oneclip");
    worker.start();
  }
  public String url(){return url;}
  public String status(){return status;}
  public long bytesSent(){return bytesSent;}
  public boolean active(){return running&&!closed;}
  public boolean finished(){return closed;}

  private ConnectivityManager connectivity(){
    return (ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
  }
  private boolean samePrivateWifi(Network original) {
    try {
      ConnectivityManager cm=connectivity();
      if(cm==null||original==null||!original.equals(cm.getActiveNetwork()))return false;
      NetworkCapabilities cap=cm.getNetworkCapabilities(original);
      return cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        &&!cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        &&!cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }catch(Throwable e){return false;}
  }
  private Inet4Address wifiAddress(Network network) {
    ConnectivityManager cm=connectivity();
    LinkProperties properties=cm==null?null:cm.getLinkProperties(network);
    if(properties==null)return null;
    for(LinkAddress addr:properties.getLinkAddresses()) {
      InetAddress ip=addr.getAddress();
      if(ip instanceof Inet4Address && ip.isSiteLocalAddress())
        return (Inet4Address)ip;
    }
    return null;
  }
  private void serve() {
    long expiry=SystemClock.elapsedRealtime()+WINDOW_MS;
    try {
      ConnectivityManager cm=connectivity();
      Network active=cm==null?null:cm.getActiveNetwork();
      if(!samePrivateWifi(active))
        throw new IllegalStateException("Conecte o carro ao hotspot Wi-Fi privado do celular");
      Inet4Address ip=wifiAddress(active);
      if(ip==null)
        throw new IllegalStateException("Wi-Fi sem IPv4 privado acessível; não usar dados celulares");
      boundNetwork=active;
      try(ServerSocket server=new ServerSocket()) {
        listener=server;
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress(ip,0),1);
        server.setSoTimeout(1000);
        url="http://"+ip.getHostAddress()+":"+server.getLocalPort()+"/clip/"+token;
        status="Disponível por até 10 minutos, apenas neste Wi-Fi. "+
          "Copie o endereço e abra no navegador do celular conectado ao mesmo hotspot. "+
          "HTTP local sem criptografia: use apenas hotspot privado e protegido por senha.";
        running=true;
        while(!closed&&!used&&SystemClock.elapsedRealtime()<expiry){
          if(!samePrivateWifi(boundNetwork)){
            status="Wi-Fi desconectado ou rota alterada; transferência interrompida.";
            break;
          }
          try(Socket client=server.accept()) {
            client.setSoTimeout(5000);
            client.setTcpNoDelay(true);
            handle(client,expiry);
          }catch(SocketTimeoutException wait){ /* periodic expiry/route check */ }
          catch(Exception clientFailure){
            if(!closed)status="Conexão do telefone encerrada: "+
              clientFailure.getClass().getSimpleName()+
              ". O link temporário permanece ativo até expirar ou concluir.";
          }
        }
        if(!used&&SystemClock.elapsedRealtime()>=expiry)
          status="Link expirou. Inicie uma nova transferência manualmente.";
      }
    }catch(Throwable failure) {
      status="Não foi possível disponibilizar o vídeo no Wi-Fi: "+
        failure.getClass().getSimpleName()+" · "+String.valueOf(failure.getMessage());
    }finally {
      running=false;
      closed=true;
      listener=null;
      url="";
    }
  }

  private void handle(Socket client,long expiry) throws Exception {
    InputStream input=new BufferedInputStream(client.getInputStream());
    OutputStream output=new BufferedOutputStream(client.getOutputStream());
    byte[] header=new byte[4096];
    int size=0,c;
    // Ignore untrusted headers; accept only one short GET request line.
    while(size<header.length && (c=input.read())!=-1) {
      header[size++]=(byte)c;
      if(size>=4&&header[size-4]=='\r'&&header[size-3]=='\n'&&
          header[size-2]=='\r'&&header[size-1]=='\n')break;
    }
    String request=new String(header,0,size,StandardCharsets.US_ASCII);
    int end=request.indexOf("\r\n");
    String firstLine=end<0?"":request.substring(0,end);
    // Exact GET only; no path traversal, HEAD, query string or directory routes.
    if(!firstLine.equals("GET /clip/"+token+" HTTP/1.1") &&
       !firstLine.equals("GET /clip/"+token+" HTTP/1.0")){
      respond(output,403,"Acesso não autorizado");
      return;
    }
    if(used||closed||SystemClock.elapsedRealtime()>=expiry||
       !samePrivateWifi(boundNetwork)||
       OemRecordingLibrary.resolve(app,file.getAbsolutePath())==null||
       file.length()!=originalBytes){
      respond(output,410,"Transferência indisponível ou rede alterada");
      return;
    }
    used=true; // single request; never send the same incident twice automatically
    byte[] prefix=("HTTP/1.1 200 OK\r\n"+
      "Content-Type: video/mp4\r\n"+
      "Content-Disposition: attachment; filename=\""+file.getName()+"\"\r\n"+
      "Content-Length: "+originalBytes+"\r\n"+
      "Cache-Control: no-store\r\n"+
      "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    output.write(prefix);
    long remaining=originalBytes;
    try(FileInputStream video=new FileInputStream(file)) {
      byte[] buffer=new byte[CHUNK];
      while(remaining>0 && !closed && SystemClock.elapsedRealtime()<expiry &&
            samePrivateWifi(boundNetwork)) {
        int n=video.read(buffer,0,(int)Math.min(CHUNK,remaining));
        if(n<=0)throw new IllegalStateException("MP4 mudou antes de completar envio");
        output.write(buffer,0,n);
        remaining-=n;
        bytesSent+=n;
      }
      output.flush();
    }
    if(remaining==0)status="Transferência local concluída: "+bytesSent+
      " bytes para um cliente neste Wi-Fi. Verifique o arquivo no telefone.";
    else status="Transferência interrompida por rede, tempo ou aplicativo. "+
      bytesSent+" bytes entregues; reinicie manualmente se necessário.";
  }

  private static void respond(OutputStream output,int code,String message) throws Exception {
    byte[] bytes=message.getBytes(StandardCharsets.UTF_8);
    output.write(("HTTP/1.1 "+code+" Denied\r\n"+
      "Content-Type: text/plain; charset=utf-8\r\n"+
      "Content-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n")
      .getBytes(StandardCharsets.US_ASCII));
    output.write(bytes);
    output.flush();
  }

  public synchronized void stop() {
    if(closed)return;
    closed=true;
    status="Compartilhamento encerrado manualmente.";
    url="";
    ServerSocket server=listener;
    if(server!=null)try{server.close();}catch(Exception ignored){}
  }
}
