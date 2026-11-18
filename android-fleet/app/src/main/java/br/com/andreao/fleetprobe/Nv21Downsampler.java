package br.com.andreao.fleetprobe;

import java.nio.ByteBuffer;

/**
 * Precomputes the spatial sample indices ONCE per codec configuration.
 * Preserves the exact nearest-neighbour positions of the original proven
 * OEM NV21 -> NV12 conversion, without per-frame division or planar arrays.
 *
 * Input is read only. Output buffer must have width*height*3/2 capacity.
 */
public final class Nv21Downsampler {
  private final int width,height,maxIndex;
  private final int[] yIndex,vuIndex;

  public Nv21Downsampler(int sourceW,int sourceH,int destW,int destH){
    if(sourceW<16||sourceH<16||destW<16||destH<16 ||
      sourceW%2!=0||sourceH%2!=0||destW%2!=0||destH%2!=0||
      sourceW>8192||sourceH>4096||destW>1920||destH>1080)
      throw new IllegalArgumentException("Dimensões NV21 não suportadas");
    width=destW;height=destH;
    yIndex=new int[destW*destH];
    vuIndex=new int[(destW/2)*(destH/2)];
    for(int y=0;y<destH;y++){
      int sy=Math.min(sourceH-1,y*sourceH/destH);
      for(int x=0;x<destW;x++)
        yIndex[y*destW+x]=sy*sourceW+Math.min(sourceW-1,x*sourceW/destW);
    }
    int chromaW=destW/2,chromaH=destH/2,sourceArea=sourceW*sourceH;
    for(int y=0;y<chromaH;y++){
      int sy=Math.min(sourceH/2-1,y*(sourceH/2)/chromaH);
      for(int x=0;x<chromaW;x++){
        int sx=Math.min(sourceW/2-1,x*(sourceW/2)/chromaW);
        vuIndex[y*chromaW+x]=sourceArea+sy*sourceW+sx*2;
      }
    }
    int largest=0;
    for(int i:yIndex)if(i>largest)largest=i;
    for(int i:vuIndex)if(i+1>largest)largest=i+1;
    maxIndex=largest;
  }
  private void validate(ByteBuffer output,byte[] input){
    if(output==null||input==null)throw new IllegalArgumentException("Buffer ausente");
    if(input.length<=maxIndex||output.remaining()<width*height*3/2)
      throw new IllegalArgumentException("Buffer NV21 insuficiente");
  }
  public void writeNv12(ByteBuffer output,byte[] input){
    validate(output,input);
    for(int index:yIndex)output.put(input[index]);
    // NV21 stores VU. NV12 expects UV.
    for(int index:vuIndex){
      output.put(input[index+1]);
      output.put(input[index]);
    }
  }
  public void writeI420(ByteBuffer output,byte[] input){
    validate(output,input);
    for(int index:yIndex)output.put(input[index]);
    int start=output.position(),count=vuIndex.length;
    for(int i=0;i<count;i++){
      int index=vuIndex[i];
      output.put(start+i,input[index+1]);        // U plane
      output.put(start+count+i,input[index]);     // V plane
    }
    output.position(start+count*2);
  }
}
