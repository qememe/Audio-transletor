package org.audioall.tests;

import org.audioall.core.*;
import java.io.*;
import java.util.*;

public final class CoreTests {
    static int assertions;
    static void check(boolean value,String name){assertions++;if(!value)throw new AssertionError(name);}
    static final class Pcm implements Audio.Sink {
        short[] data=new short[65536];int length;
        public void write(short[] s,int off,int n){if(length+n>data.length)data=Arrays.copyOf(data,Math.max(length+n,data.length*2));System.arraycopy(s,off,data,length,n);length+=n;}
    }
    static final class Events implements PacketModem.Listener,Sstv.Listener {
        final List<Transfer> files=new ArrayList<>();Raster image;Sstv.Mode mode;boolean complete;String error="";
        public void transfer(Transfer t){files.add(t);}
        public void status(String s){}
        public void error(String s){error=s;}
        public void image(Raster image,Sstv.Mode mode,boolean complete,int lines){this.image=image;this.mode=mode;this.complete=complete;}
    }
    static void feed(Audio.Sink receiver,Pcm pcm,int block)throws IOException{for(int at=0;at<pcm.length;at+=block)receiver.write(pcm.data,at,Math.min(block,pcm.length-at));}
    static Raster card(int w,int h){Raster r=new Raster(w,h);for(int y=0;y<h;y++)for(int x=0;x<w;x++){int red=20+210*x/Math.max(1,w-1),green=20+210*y/Math.max(1,h-1),blue=30+180*(x+y)/Math.max(1,w+h-2);r.pixels[y*w+x]=0xff000000|red<<16|green<<8|blue;}return r;}
    public static void main(String[] args)throws Exception{
        for(int b=0;b<256;b++){
            int code=PacketModem.hammingEncode(b);check(PacketModem.hammingDecode(code)==b,"Hamming clean");
            for(int bit=0;bit<13;bit++)check(PacketModem.hammingDecode(code^(1<<bit))==b,"Hamming single error");
            for(int bit=1;bit<13;bit++)check(PacketModem.hammingDecode(code^1^(1<<bit))<0,"Hamming double error");
        }
        Random random=new Random(813);byte[] binary=new byte[777];random.nextBytes(binary);
        for(Transfer.Kind kind:Transfer.Kind.values()){
            Transfer original=new Transfer(kind,"../тест.bin","application/octet-stream",binary);
            Transfer result=Transfer.unpack(original.pack());check(Arrays.equals(result.data,binary)&&result.kind==kind,"Envelope "+kind);check(result.name.equals("тест.bin"),"Safe name");
            byte[] corrupt=original.pack();corrupt[corrupt.length-1]^=1;boolean rejected=false;try{Transfer.unpack(corrupt);}catch(IOException e){rejected=true;}check(rejected,"SHA corruption");
        }
        for(PacketModem.Profile profile:PacketModem.Profile.values()){
            Events e=new Events();Pcm pcm=new Pcm();Transfer t=new Transfer(Transfer.Kind.VIDEO,"clip.mp4","video/mp4",binary);
            PacketModem.encode(t,profile,2,pcm,Audio.NONE);
            // Arbitrary capture offset, amplitude attenuation, deterministic microphone noise.
            Pcm shifted=new Pcm();shifted.write(new short[1337],0,1337);
            for(int i=0;i<pcm.length;i++)pcm.data[i]=(short)(pcm.data[i]*.52+random.nextGaussian()*180);
            shifted.write(pcm.data,0,pcm.length);PacketModem.Decoder d=new PacketModem.Decoder(e);feed(d,shifted,997);
            check(e.files.size()==1,"Modem completion + duplicate suppression "+profile);
            check(Arrays.equals(e.files.get(0).data,binary),"Modem bytes "+profile);
            System.out.printf(Locale.ROOT,"PASS modem %-12s %.2f s PCM\n",profile.name(),pcm.length/(double)Audio.RATE);
        }
        for(Sstv.Mode mode:Sstv.Mode.values()){
            Raster original=card(mode.width,mode.height);Pcm pcm=new Pcm();Sstv.encode(original,mode,pcm,Audio.NONE);
            Events e=new Events();Sstv.Decoder d=new Sstv.Decoder(e);feed(d,pcm,4093);
            check(e.complete&&e.mode==mode,"SSTV complete "+mode);
            double squared=0;for(int i=0;i<original.pixels.length;i++)for(int shift=0;shift<=16;shift+=8){int a=(original.pixels[i]>>>shift)&255,b=(e.image.pixels[i]>>>shift)&255;squared+=(a-b)*(a-b);}
            double rmse=Math.sqrt(squared/(original.pixels.length*3));
            check(rmse<20,"SSTV image error "+mode+" RMSE="+rmse);
            check(Math.abs(pcm.length/(double)Audio.RATE-(mode.seconds()+.22))<.001,"Standard SSTV duration "+mode);
            System.out.printf(Locale.ROOT,"PASS SSTV %-9s RMSE %.2f, %.3f s\n",mode.name(),rmse,pcm.length/(double)Audio.RATE);
        }
        // Streaming WAV round trip and stereo/rate conversion.
        File wav=File.createTempFile("audioall-test-",".wav");try{
            Pcm pcm=new Pcm();PacketModem.encode(Transfer.text("Привет, звук! 🌍"),PacketModem.Profile.FAST,1,pcm,Audio.NONE);
            try(Wave.Writer writer=new Wave.Writer(wav)){writer.write(pcm.data,0,pcm.length);}
            Events e=new Events();Receiver receiver=new Receiver(e,e);try(InputStream in=new FileInputStream(wav)){Wave.read(in,receiver,Audio.NONE);}
            check(e.files.size()==1,"WAV receiver");check(new String(e.files.get(0).data,"UTF-8").equals("Привет, звук! 🌍"),"Unicode message");
        }finally{wav.delete();}
        System.out.println("All "+assertions+" assertions passed.");
    }
}
