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
            // Erase one complete repetition of every packet. The other copy must suffice.
            int frameSamples=(32+64/profile.bits()+PacketModem.ENCODED_BITS/profile.bits())*profile.samples()+Audio.RATE/25;
            for(int seq=0;seq<PacketModem.packets(t.pack().length);seq++){
                int begin=Audio.RATE*150/1000+seq*2*frameSamples;
                Arrays.fill(pcm.data,begin,begin+frameSamples,(short)0);
            }
            Events recovered=new Events();feed(new PacketModem.Decoder(recovered),pcm,2041);
            check(recovered.files.size()==1&&Arrays.equals(recovered.files.get(0).data,binary),"Lost repetition "+profile);
            if(profile==PacketModem.Profile.FAST){
                Pcm drift=new Pcm();double ratio=1.0001;int length=(int)(pcm.length*ratio);short[] shiftedClock=new short[length];
                for(int i=0;i<length;i++){double source=i/ratio;int at=(int)source;double f=source-at;shiftedClock[i]=(short)(pcm.data[at]*(1-f)+pcm.data[Math.min(pcm.length-1,at+1)]*f);}
                drift.write(shiftedClock,0,length);Events drifting=new Events();feed(new PacketModem.Decoder(drifting),drift,3079);
                check(drifting.files.size()==1&&Arrays.equals(drifting.files.get(0).data,binary),"100 ppm clock drift");
            }
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
            if(mode==Sstv.Mode.PD120){
                int saved=pcm.length;pcm.length-=Audio.RATE*120/1000;
                Events boundary=new Events();Receiver wavEnd=new Receiver(boundary,boundary);feed(wavEnd,pcm,4096);
                check(!boundary.complete,"SSTV discriminator lookahead remains before EOF flush");
                wavEnd.finish();check(boundary.complete,"SSTV WAV ending exactly at the raster boundary");pcm.length=saved;
            }
            System.out.printf(Locale.ROOT,"PASS SSTV %-9s RMSE %.2f, %.3f s\n",mode.name(),rmse,pcm.length/(double)Audio.RATE);
        }
        Events mixed=new Events();Receiver auto=new Receiver(mixed,mixed);
        Sstv.encode(card(320,240),Sstv.Mode.ROBOT36,auto,Audio.NONE);
        for(Transfer.Kind kind:Transfer.Kind.values())PacketModem.encode(new Transfer(kind,"mixed.bin","application/octet-stream",binary),PacketModem.Profile.FAST,1,auto,Audio.NONE);
        check(mixed.complete&&mixed.files.size()==4,"SSTV + all file kinds in one uninterrupted receiver stream");
        for(int i=0;i<4;i++)check(mixed.files.get(i).kind==Transfer.Kind.values()[i]&&Arrays.equals(mixed.files.get(i).data,binary),"Mixed content "+i);
        java.util.concurrent.ExecutorService duplex=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            Events a=new Events(),b=new Events();Receiver receiverA=new Receiver(a,a),receiverB=new Receiver(b,b);
            java.util.concurrent.CountDownLatch go=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<?> txA=duplex.submit(()->{try{go.await();PacketModem.encode(Transfer.text("Duplex A → B"),PacketModem.Profile.FAST,1,receiverB,Audio.NONE);}catch(Exception e){throw new RuntimeException(e);}});
            java.util.concurrent.Future<?> txB=duplex.submit(()->{try{go.await();PacketModem.encode(new Transfer(Transfer.Kind.AUDIO,"voice.ogg","audio/ogg",binary),PacketModem.Profile.BELL202,1,receiverA,Audio.NONE);}catch(Exception e){throw new RuntimeException(e);}});
            go.countDown();txA.get();txB.get();check(a.files.size()==1&&b.files.size()==1,"Concurrent duplex on independent PCM channels");
            check(a.files.get(0).kind==Transfer.Kind.AUDIO&&b.files.get(0).kind==Transfer.Kind.TEXT,"Duplex content");
        }finally{duplex.shutdownNow();}
        // Streaming WAV round trip and stereo/rate conversion.
        File wav=File.createTempFile("audioall-test-",".wav");try{
            Pcm pcm=new Pcm();PacketModem.encode(Transfer.text("Привет, звук! 🌍"),PacketModem.Profile.FAST,1,pcm,Audio.NONE);
            try(Wave.Writer writer=new Wave.Writer(wav)){writer.write(pcm.data,0,pcm.length);}
            Events e=new Events();Receiver receiver=new Receiver(e,e);try(InputStream in=new FileInputStream(wav)){Wave.read(in,receiver,Audio.NONE);}
            check(e.files.size()==1,"WAV receiver");check(new String(e.files.get(0).data,"UTF-8").equals("Привет, звук! 🌍"),"Unicode message");
            // Non-native sample rate + two channels + an odd-sized ancillary chunk.
            Pcm source=new Pcm();PacketModem.encode(Transfer.text("Stereo 44.1 kHz"),PacketModem.Profile.BELL202,1,source,Audio.NONE);
            try(RandomAccessFile out=new RandomAccessFile(wav,"rw")){
                out.setLength(0);int frames=(int)((long)source.length*44100/Audio.RATE);out.writeBytes("RIFF");le32(out,48+frames*4);out.writeBytes("WAVEfmt ");le32(out,16);le16(out,1);le16(out,2);le32(out,44100);le32(out,44100*4);le16(out,4);le16(out,16);out.writeBytes("JUNK");le32(out,3);out.write(new byte[]{1,2,3,0});out.writeBytes("data");le32(out,frames*4);
                for(int i=0;i<frames;i++){double position=i*(double)Audio.RATE/44100;int at=(int)position;double f=position-at;int value=(int)(source.data[at]*(1-f)+source.data[Math.min(source.length-1,at+1)]*f);le16(out,value);le16(out,value/2);}
            }
            Events stereo=new Events();try(InputStream in=new FileInputStream(wav)){Wave.read(in,new Receiver(stereo,stereo),Audio.NONE);}
            check(stereo.files.size()==1&&new String(stereo.files.get(0).data,"UTF-8").equals("Stereo 44.1 kHz"),"Stereo 44100 resampling and odd chunk");
        }finally{wav.delete();}
        System.out.println("All "+assertions+" assertions passed.");
    }
    static void le16(RandomAccessFile out,int n)throws IOException{out.write(n);out.write(n>>>8);}
    static void le32(RandomAccessFile out,int n)throws IOException{le16(out,n);le16(out,n>>>16);}
}
