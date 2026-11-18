package br.com.andreao.fleetprobe;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** Generates a QR entirely on-device: no network, analytics or QR web service. */
public final class FleetQrCode {
  private FleetQrCode(){}
  public static Bitmap make(String uri,int pixels) throws WriterException {
    if(uri==null||!uri.startsWith("http://192.")&&
       !uri.startsWith("http://10.")&&!uri.startsWith("http://172."))
      throw new IllegalArgumentException("Somente endereço IPv4 local");
    int side=Math.max(200,Math.min(600,pixels));
    BitMatrix qr=new QRCodeWriter().encode(uri,BarcodeFormat.QR_CODE,side,side);
    Bitmap picture=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
    for(int y=0;y<side;y++)for(int x=0;x<side;x++)
      picture.setPixel(x,y,qr.get(x,y)?Color.BLACK:Color.WHITE);
    return picture;
  }
}
