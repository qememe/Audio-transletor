package org.audioall.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

/** AudioALL/1 framing, not AX.25. Bell 202 or orthogonal 4FSK physical layer. */
public final class PacketModem {
    private PacketModem() {}
    public enum Profile {
        ROBUST("4FSK 300 • устойчивый",300,new int[]{1200,1800,2400,3000}),
        FAST("4FSK 600 • быстрый",600,new int[]{1200,1800,2400,3000}),
        BELL202("AFSK Bell 202 • 1200",1200,new int[]{2200,1200});
        public final String label;public final int baud;public final int[] tones;
        Profile(String label,int baud,int[] tones){this.label=label;this.baud=baud;this.tones=tones;}
        public int bits(){return tones.length==4?2:1;}
        public int samples(){return Audio.RATE/baud;}
        @Override public String toString(){return label;}
    }
    public interface Listener {
        void transfer(Transfer transfer);
        void status(String message);
        void error(String message);
    }
    private static final long SYNC=0xd3a5c79e6b28f041L;
    public static final int PAYLOAD=256,RAW=280,ENCODED_BITS=RAW*13;
    public static int packets(int bytes){return (bytes+PAYLOAD-1)/PAYLOAD;}
    public static double seconds(int bytes,Profile profile,int repeats){
        return packets(bytes)*repeats*((32+64.0/profile.bits()+ENCODED_BITS/(double)profile.bits())/profile.baud+.04)+.3;
    }
    public static void encode(Transfer transfer,Profile profile,int repeats,Audio.Sink sink,Audio.Progress progress) throws IOException {
        if(repeats<1 || repeats>3)throw new IllegalArgumentException("Повторы: 1–3");
        byte[] packed=transfer.pack();int count=packets(packed.length);int id=new java.security.SecureRandom().nextInt();
        Audio.ToneWriter writer=new Audio.ToneWriter(sink,progress);writer.tone(0,150);
        for(int seq=0;seq<count;seq++) {
            int used=Math.min(PAYLOAD,packed.length-seq*PAYLOAD);
            byte[] frame=new byte[RAW];ByteBuffer header=ByteBuffer.wrap(frame);
            header.put((byte)1).put((byte)0).putInt(id).putShort((short)seq).putShort((short)count).putInt(packed.length).putShort((short)used);
            System.arraycopy(packed,seq*PAYLOAD,frame,20,used);
            CRC32 crc=new CRC32();crc.update(frame,0,RAW-4);header.putInt(RAW-4,(int)crc.getValue());
            int[] bits=new int[ENCODED_BITS];
            for(int b=0;b<RAW;b++){int code=hammingEncode(frame[b]&255);for(int i=0;i<13;i++)bits[b*13+i]=(code>>>i)&1;}
            // Self-synchronising per-frame XOR stream prevents long constant runs.
            whiten(bits);
            for(int rep=0;rep<repeats;rep++) {
                Audio.check(progress);
                for(int i=0;i<32;i++)writer.samples(profile.tones[(i&1)==0?0:profile.tones.length-1],profile.samples());
                for(int shift=64-profile.bits();shift>=0;shift-=profile.bits())
                    writer.samples(profile.tones[(int)(SYNC>>>shift)&(profile.tones.length-1)],profile.samples());
                for(int bit=0;bit<bits.length;bit+=profile.bits()) {
                    int symbol=bits[bit];if(profile.bits()==2)symbol=(symbol<<1)|bits[bit+1];
                    writer.samples(profile.tones[symbol],profile.samples());
                }
                writer.samples(0,Audio.RATE/25);writer.flush();
                progress.update((seq*repeats+rep+1)/(double)(count*repeats),"Пакет "+(seq+1)+"/"+count+", повтор "+(rep+1));
            }
        }
        writer.samples(0,Audio.RATE/10);writer.flush();progress.update(1,"Передача завершена");
    }
    public static int hammingEncode(int value) {
        int code=0,k=0;
        for(int p=1;p<=12;p++)if((p&(p-1))!=0)code|=((value>>>k++)&1)<<(p-1);
        for(int parity=1;parity<=8;parity<<=1){int bit=0;for(int p=1;p<=12;p++)if((p&parity)!=0)bit^=(code>>>(p-1))&1;code|=bit<<(parity-1);}
        code|=(Integer.bitCount(code)&1)<<12;return code;
    }
    /** SECDED: returns -1 for a detected double error. */
    public static int hammingDecode(int code) {
        int syndrome=0;for(int p=1;p<=12;p++)if(((code>>>(p-1))&1)!=0)syndrome^=p;
        int parity=Integer.bitCount(code)&1;
        if(syndrome!=0){if(parity==0 || syndrome>12)return -1;code^=1<<(syndrome-1);}
        int value=0,k=0;for(int p=1;p<=12;p++)if((p&(p-1))!=0)value|=((code>>>(p-1))&1)<<k++;
        return value;
    }
    private static void whiten(int[] bits) {
        int state=0x1ff;for(int i=0;i<bits.length;i++){int mask=state&1;bits[i]^=mask;state=(state>>>1)|(((state^(state>>>4))&1)<<8);}
    }
    public static final class Decoder implements Audio.Sink {
        private final Listener listener;
        private final Demodulator[] demods;
        private final LinkedHashMap<Integer,Assembly> sessions=new LinkedHashMap<>();
        private final LinkedHashSet<Integer> completed=new LinkedHashSet<>();
        public Decoder(Listener listener){this.listener=listener;demods=new Demodulator[Profile.values().length];for(Profile p:Profile.values())demods[p.ordinal()]=new Demodulator(p,this);}
        public void write(short[] samples,int offset,int length){for(Demodulator d:demods)d.feed(samples,offset,length);}
        private void frame(byte[] frame) {
            CRC32 crc=new CRC32();crc.update(frame,0,RAW-4);ByteBuffer b=ByteBuffer.wrap(frame);
            if((int)crc.getValue()!=b.getInt(RAW-4))return;
            if(b.get()!=1 || b.get()!=0)return;
            int id=b.getInt(),seq=b.getShort()&65535,count=b.getShort()&65535,size=b.getInt(),length=b.getShort()&65535;
            if(size<1 || size>Transfer.MAX_BYTES+4096 || count!=packets(size) || seq>=count || length!=Math.min(PAYLOAD,size-seq*PAYLOAD))return;
            if(completed.contains(id))return;
            long now=System.currentTimeMillis();sessions.values().removeIf(a->now-a.last>30*60*1000L);
            Assembly assembly=sessions.get(id);
            if(assembly==null){if(sessions.size()>=2){Integer old=sessions.keySet().iterator().next();sessions.remove(old);listener.error("Незавершённая передача вытеснена новой");}assembly=new Assembly(size,count);sessions.put(id,assembly);}
            if(assembly.data.length!=size || assembly.count!=count)return;
            assembly.last=now;
            if(!assembly.seen.get(seq)){System.arraycopy(frame,20,assembly.data,seq*PAYLOAD,length);assembly.seen.set(seq);listener.status("Приём пакетов: "+assembly.seen.cardinality()+"/"+count);}
            if(assembly.seen.cardinality()==count){sessions.remove(id);try{Transfer result=Transfer.unpack(assembly.data);completed.add(id);if(completed.size()>64)completed.remove(completed.iterator().next());listener.transfer(result);}catch(IOException e){listener.error(e.getMessage());}}
        }
        public String incomplete(){if(sessions.isEmpty())return "";StringBuilder text=new StringBuilder();for(Assembly a:sessions.values())text.append("Не хватает пакетов: ").append(a.count-a.seen.cardinality()).append(" из ").append(a.count).append(". Повторите передачу.\n");return text.toString();}
    }
    private static final class Assembly {final byte[] data;final BitSet seen=new BitSet();final int count;long last;Assembly(int size,int count){data=new byte[size];this.count=count;last=System.currentTimeMillis();}}
    private static final class Demodulator {
        final Profile profile;final Decoder receiver;final int n,syncSymbols,frameSymbols;
        final double[][] cosine,sine;short[] buffer;int size,scan,locked=-1;
        final int[] sync;
        Demodulator(Profile profile,Decoder receiver){
            this.profile=profile;this.receiver=receiver;n=profile.samples();syncSymbols=64/profile.bits();frameSymbols=ENCODED_BITS/profile.bits();
            buffer=new short[(syncSymbols+frameSymbols+4)*n+8192];sync=new int[syncSymbols];
            for(int i=0;i<syncSymbols;i++)sync[i]=(int)(SYNC>>>(64-profile.bits()*(i+1)))&(profile.tones.length-1);
            cosine=new double[profile.tones.length][n];sine=new double[profile.tones.length][n];
            for(int t=0;t<profile.tones.length;t++)for(int i=0;i<n;i++){double p=2*Math.PI*profile.tones[t]*i/Audio.RATE;cosine[t][i]=Math.cos(p);sine[t][i]=Math.sin(p);}
        }
        void feed(short[] samples,int off,int len){
            while(len>0){int take=Math.min(len,buffer.length-size);if(take==0)throw new IllegalStateException("Modem buffer full");System.arraycopy(samples,off,buffer,size,take);size+=take;off+=take;len-=take;process();compact();}
        }
        void process(){
            while(true){
                if(locked>=0){
                    if(size<locked+(syncSymbols+frameSymbols)*n)return;
                    int[] bits=new int[ENCODED_BITS];
                    for(int i=0;i<frameSymbols;i++){int symbol=classify(locked+(syncSymbols+i)*n,null);int pos=i*profile.bits();if(profile.bits()==2){bits[pos]=symbol>>>1;bits[pos+1]=symbol&1;}else bits[pos]=symbol;}
                    whiten(bits);byte[] frame=new byte[RAW];boolean valid=true;
                    for(int i=0;i<RAW;i++){int code=0;for(int k=0;k<13;k++)code|=bits[i*13+k]<<k;int value=hammingDecode(code);if(value<0){valid=false;break;}frame[i]=(byte)value;}
                    if(valid)receiver.frame(frame);
                    scan=locked+(syncSymbols+frameSymbols)*n;locked=-1;
                }
                if(scan+(syncSymbols+1)*n>size)return;
                if(classify(scan,null)==sync[0] && classify(scan+n,null)==sync[1] && matches(scan)>=syncSymbols-1){
                    int best=scan;double bestScore=-1;
                    for(int candidate=Math.max(0,scan-n/2);candidate<=scan+n/2;candidate+=Math.max(1,n/8)){
                        if(candidate+syncSymbols*n>size)break;
                        double score=score(candidate);if(score>bestScore){bestScore=score;best=candidate;}
                    }
                    locked=best;continue;
                }
                scan+=Math.max(1,n/4);
            }
        }
        int matches(int start){int matches=0;for(int i=0;i<syncSymbols;i++){if(classify(start+i*n,null)==sync[i])matches++;else if(i-matches>1)break;}return matches;}
        double score(int start){double result=0;double[] powers=new double[profile.tones.length];for(int i=0;i<syncSymbols;i++){classify(start+i*n,powers);double sum=1;for(double p:powers)sum+=p;result+=powers[sync[i]]/sum;}return result;}
        int classify(int start,double[] output){
            double best=-1;int symbol=0;
            for(int t=0;t<profile.tones.length;t++){double re=0,im=0;for(int i=0;i<n;i++){double s=buffer[start+i];re+=s*cosine[t][i];im+=s*sine[t][i];}double power=re*re+im*im;if(output!=null)output[t]=power;if(power>best){best=power;symbol=t;}}
            return symbol;
        }
        void compact(){int drop=locked>=0?locked:scan;if(drop>0){System.arraycopy(buffer,drop,buffer,0,size-drop);size-=drop;scan-=drop;if(locked>=0)locked-=drop;}}
    }
}
