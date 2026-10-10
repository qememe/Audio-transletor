package org.audioall.core;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Streaming RIFF reader/writer. Input: PCM16 mono/stereo, 8..192 kHz. */
public final class Wave {
    private Wave() {}
    public static final class Writer implements Audio.Sink, Closeable {
        private final RandomAccessFile out;
        private long samples;
        public Writer(File file) throws IOException {
            out=new RandomAccessFile(file,"rw"); out.setLength(0);
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII)); le32(out,0);
            out.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)); le32(out,16);
            le16(out,1); le16(out,1); le32(out,Audio.RATE); le32(out,Audio.RATE*2);
            le16(out,2); le16(out,16); out.write("data".getBytes(StandardCharsets.US_ASCII)); le32(out,0);
        }
        public void write(short[] data,int off,int len) throws IOException {
            if ((samples+len)*2>0xfffffff0L) throw new IOException("WAV превышает 4 ГБ");
            byte[] bytes=new byte[len*2];
            for(int i=0;i<len;i++) { int s=data[off+i];bytes[2*i]=(byte)s;bytes[2*i+1]=(byte)(s>>8); }
            out.write(bytes);samples+=len;
        }
        public void close() throws IOException {
            try { out.seek(4);le32(out,36+samples*2);out.seek(40);le32(out,samples*2); } finally {out.close();}
        }
    }
    public static void read(InputStream source,Audio.Sink sink,Audio.Progress progress) throws IOException {
        DataInputStream in=new DataInputStream(new BufferedInputStream(source));
        if(!tag(in).equals("RIFF")) throw new IOException("Нужен RIFF WAV (PCM16)");
        long riffSize=u32(in);
        if(!tag(in).equals("WAVE")) throw new IOException("Нужен WAV");
        int channels=0,rate=0,bits=0,format=0,align=0; long remaining=riffSize-4;
        while(remaining>=8) {
            String chunk=tag(in); long length=u32(in);remaining-=8;
            if(length>remaining) throw new IOException("Повреждённый WAV: размер блока");
            if(chunk.equals("fmt ")) {
                if(length<16 || length>65536) throw new IOException("Некорректный fmt WAV");
                format=u16(in);channels=u16(in);rate=(int)u32(in);u32(in);align=u16(in);bits=u16(in);
                skip(in,length-16);
            } else if(chunk.equals("data")) {
                if(format!=1 || bits!=16 || channels<1 || channels>2 || rate<8000 || rate>192000 || align!=channels*2)
                    throw new IOException("Поддерживается WAV PCM16, mono/stereo, 8–192 кГц");
                long frames=length/align; short[] output=new short[4096]; int used=0;
                double next=0; float prev=0;
                for(long f=0;f<frames;f++) {
                    float value=(short)u16(in);
                    if(channels==2)value=(value+(short)u16(in))/2;
                    if(f==0)prev=value;
                    while(next<=f) {
                        double t=next-(f-1); t=Math.max(0,Math.min(1,t));
                        output[used++]=(short)(prev+(value-prev)*t);next+=(double)rate/Audio.RATE;
                        if(used==output.length) {sink.write(output,0,used);used=0;Audio.check(progress);}
                    }
                    prev=value;
                    if((f&16383)==0)progress.update((double)f/Math.max(1,frames),"Декодирование WAV");
                }
                if(used>0)sink.write(output,0,used);
                skip(in,length-frames*align);
            } else skip(in,length);
            if((length&1)!=0) {in.readByte();remaining--;}
            remaining-=length;
        }
    }
    private static String tag(DataInputStream in) throws IOException {byte[] b=new byte[4];in.readFully(b);return new String(b,StandardCharsets.US_ASCII);}
    private static int u16(DataInputStream in) throws IOException {return in.readUnsignedByte() | in.readUnsignedByte()<<8;}
    private static long u32(DataInputStream in) throws IOException {return (long)u16(in) | (long)u16(in)<<16;}
    private static void skip(DataInputStream in,long n) throws IOException {while(n>0) {long k=in.skip(n);if(k==0){in.readByte();k=1;}n-=k;}}
    private static void le16(RandomAccessFile out,int n) throws IOException {out.write(n);out.write(n>>>8);}
    private static void le32(RandomAccessFile out,long n) throws IOException {le16(out,(int)n);le16(out,(int)(n>>>16));}
}
