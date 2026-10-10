package org.audioall.core;

import java.io.IOException;
import java.util.*;

/** Original implementation of public analog SSTV timings and VIS headers. */
public final class Sstv {
    private Sstv() {}
    public enum Mode {
        ROBOT36("Robot 36",8,320,240,9,150),
        ROBOT72("Robot 72",12,320,240,9,300),
        PD50("PD 50",93,320,256,20,388.16),
        PD120("PD 120",95,640,496,20,508.48),
        MARTIN1("Martin 1",44,320,256,4.862,446.446);
        public final String label;public final int vis,width,height;public final double sync,line;
        Mode(String label,int vis,int width,int height,double sync,double line){this.label=label;this.vis=vis;this.width=width;this.height=height;this.sync=sync;this.line=line;}
        public boolean paired(){return this==PD50 || this==PD120;}
        public int lines(){return paired()?height/2:height;}
        public double seconds(){return .91+lines()*line/1000;}
        @Override public String toString(){return label+" • "+width+"×"+height;}
        public static Mode byVis(int vis){for(Mode m:values())if(m.vis==vis)return m;return null;}
    }
    public interface Listener {
        void image(Raster raster,Mode mode,boolean complete,int lines);
        void status(String message);
    }
    public static void encode(Raster input,Mode mode,Audio.Sink sink,Audio.Progress progress) throws IOException {
        Raster image=input.fit(mode.width,mode.height);Audio.ToneWriter tone=new Audio.ToneWriter(sink,progress);
        tone.tone(0,100);tone.tone(1900,300);tone.tone(1200,10);tone.tone(1900,300);tone.tone(1200,30);
        for(int bit=0;bit<7;bit++)tone.tone((mode.vis&(1<<bit))!=0?1100:1300,30);
        tone.tone((Integer.bitCount(mode.vis)&1)!=0?1100:1300,30);tone.tone(1200,30);
        for(int line=0;line<mode.lines();line++){
            Audio.check(progress);tone.tone(1200,mode.sync);
            if(mode==Mode.MARTIN1){
                tone.tone(1500,.572);channel(tone,image,line,1,146.432,false);tone.tone(1500,.572);
                channel(tone,image,line,2,146.432,false);tone.tone(1500,.572);
                channel(tone,image,line,0,146.432,false);tone.tone(1500,.572);
            }else if(mode.paired()){
                int y=line*2;double scan=mode==Mode.PD50?91.52:121.60;
                tone.tone(1500,2.08);channel(tone,image,y,3,scan,false);
                channel(tone,image,y,4,scan,true);channel(tone,image,y,5,scan,true);channel(tone,image,y+1,3,scan,false);
            }else if(mode==Mode.ROBOT72){
                tone.tone(1500,3);channel(tone,image,line,3,138,false);tone.tone(1500,4.5);tone.tone(1900,1.5);
                channel(tone,image,line,4,69,false);tone.tone(2300,4.5);tone.tone(1500,1.5);channel(tone,image,line,5,69,false);
            }else{
                tone.tone(1500,3);channel(tone,image,line,3,88,false);
                tone.tone((line&1)==0?1500:2300,4.5);tone.tone(1900,1.5);
                channel(tone,image,line&~1,(line&1)==0?4:5,44,true);
            }
            tone.flush();progress.update((line+1.0)/mode.lines(),mode.label+": строка "+(line+1)+"/"+mode.lines());
        }
        tone.tone(0,120);tone.flush();
    }
    private static void channel(Audio.ToneWriter tone,Raster image,int y,int component,double ms,boolean average) throws IOException {
        for(int x=0;x<image.width;x++){
            double value=component(image.pixels[y*image.width+x],component);
            if(average)value=(value+component(image.pixels[(y+1)*image.width+x],component))/2;
            tone.tone(1500+800*Math.max(0,Math.min(255,value))/255,ms/image.width);
        }
    }
    private static double component(int rgb,int component){
        int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;
        switch(component){case 0:return r;case 1:return g;case 2:return b;case 3:return .299*r+.587*g+.114*b;case 4:return 128+.5*r-.418688*g-.081312*b;default:return 128-.168736*r-.331264*g+.5*b;}
    }
    private static int rgb(double y,double cr,double cb){int r=clamp(y+1.402*(cr-128)),g=clamp(y-.714136*(cr-128)-.344136*(cb-128)),b=clamp(y+1.772*(cb-128));return 0xff000000|r<<16|g<<8|b;}
    private static int clamp(double n){return (int)Math.max(0,Math.min(255,Math.round(n)));}
    private static int samples(double ms){return (int)Math.round(ms*Audio.RATE/1000);}

    public static final class Decoder implements Audio.Sink {
        private final Listener listener;private final Fm fm=new Fm();
        private final float[] buffer=new float[Audio.RATE*2];private int size,scan,line;
        private Mode mode;private Raster raster;private double next,period;private double[] previousY,previousCr;
        public Decoder(Listener listener){this.listener=listener;}
        public void write(short[] input,int off,int len){
            for(int i=0;i<len;i++){
                if(size==buffer.length){process();compact();if(size==buffer.length)throw new IllegalStateException("SSTV buffer full");}
                buffer[size++]=fm.sample(input[off+i]);
                if((size&1023)==0){process();compact();}
            }process();compact();
        }
        private void process(){
            while(true){
                if(mode==null){
                    int header=samples(910);
                    while(scan+header<=size){
                        // Break, leader and start are checked before eight VIS bits + parity.
                        if(near(mean(scan+samples(302),samples(6)),1200,90) &&
                            near(mean(scan+samples(100),samples(40)),1900,80) &&
                            near(mean(scan+samples(440),samples(40)),1900,80) &&
                            near(mean(scan+samples(617),samples(16)),1200,80) &&
                            near(mean(scan+samples(887),samples(16)),1200,90)){
                            int vis=0,parity=0;boolean valid=true;
                            for(int bit=0;bit<8;bit++){
                                double frequency=mean(scan+samples(640+bit*30+8),samples(14));int value=near(frequency,1100,70)?1:near(frequency,1300,70)?0:-1;
                                if(value<0){valid=false;break;}parity^=value;if(bit<7)vis|=value<<bit;
                            }
                            Mode found=valid && parity==0?Mode.byVis(vis):null;
                            if(found!=null){mode=found;raster=new Raster(mode.width,mode.height);line=0;period=samples(mode.line);next=scan+header;listener.status("SSTV: "+mode.label);break;}
                        }
                        scan+=samples(2);
                    }
                    if(mode==null)return;
                }
                int nominal=(int)Math.round(next);
                if(nominal+Math.ceil(period)+samples(5)>size)return;
                int start=findSync(nominal);
                if(line>0){double actual=start-(next-period);if(Math.abs(actual-period)<samples(2))period=period*.95+actual*.05;}
                decodeLine(start,period/samples(mode.line));line++;
                if(line%4==0 || line==mode.lines())listener.image(raster.copy(),mode,line==mode.lines(),line*(mode.paired()?2:1));
                next=start+period;
                if(line==mode.lines()){scan=(int)Math.round(next);mode=null;previousY=null;previousCr=null;}
            }
        }
        private int findSync(int expected){
            int lo=Math.max(0,expected-samples(3)),hi=Math.min(size-1,expected+samples(mode.sync+3));
            int bestStart=-1,bestEnd=-1,run=-1;
            for(int i=lo;i<=hi;i++){
                if(buffer[i]>1050 && buffer[i]<1350){if(run<0)run=i;}
                else if(run>=0){if(bestStart<0 || i-run>bestEnd-bestStart){bestStart=run;bestEnd=i;}run=-1;}
            }
            if(run>=0 && (bestStart<0 || hi+1-run>bestEnd-bestStart)){bestStart=run;bestEnd=hi+1;}
            if(bestStart>=0 && bestEnd-bestStart>samples(mode.sync*.55) && bestEnd<hi)
                return bestEnd-samples(mode.sync);
            return expected;
        }
        private void decodeLine(int start,double scale){
            int w=mode.width;double sync=mode.sync;
            if(mode==Mode.MARTIN1){
                double offset=sync+.572;double[] g=scanChannel(start,offset,146.432,scale);offset+=146.432+.572;
                double[] b=scanChannel(start,offset,146.432,scale);offset+=146.432+.572;double[] r=scanChannel(start,offset,146.432,scale);
                for(int x=0;x<w;x++)raster.pixels[line*w+x]=0xff000000|clamp(r[x])<<16|clamp(g[x])<<8|clamp(b[x]);
            }else if(mode.paired()){
                double duration=mode==Mode.PD50?91.52:121.6,offset=sync+2.08;
                double[] y0=scanChannel(start,offset,duration,scale);offset+=duration;
                double[] cr=scanChannel(start,offset,duration,scale);offset+=duration;
                double[] cb=scanChannel(start,offset,duration,scale);offset+=duration;
                double[] y1=scanChannel(start,offset,duration,scale);
                for(int x=0;x<w;x++){raster.pixels[(line*2)*w+x]=rgb(y0[x],cr[x],cb[x]);raster.pixels[(line*2+1)*w+x]=rgb(y1[x],cr[x],cb[x]);}
            }else if(mode==Mode.ROBOT72){
                double[] y=scanChannel(start,sync+3,138,scale),cr=scanChannel(start,sync+3+138+6,69,scale),cb=scanChannel(start,sync+3+138+6+69+6,69,scale);
                for(int x=0;x<w;x++)raster.pixels[line*w+x]=rgb(y[x],cr[x],cb[x]);
            }else{
                double[] y=scanChannel(start,sync+3,88,scale),chroma=scanChannel(start,sync+3+88+6,44,scale);
                if((line&1)==0){previousY=y;previousCr=chroma;for(int x=0;x<w;x++)raster.pixels[line*w+x]=rgb(y[x],chroma[x],128);}
                else if(previousY!=null){for(int x=0;x<w;x++){raster.pixels[(line-1)*w+x]=rgb(previousY[x],previousCr[x],chroma[x]);raster.pixels[line*w+x]=rgb(y[x],previousCr[x],chroma[x]);}}
            }
        }
        private double[] scanChannel(int start,double offset,double duration,double scale){
            double[] values=new double[mode.width];double step=duration*Audio.RATE/1000*scale/mode.width;
            double begin=start+offset*Audio.RATE/1000*scale;
            for(int x=0;x<values.length;x++){
                int a=Math.max(0,(int)Math.round(begin+(x+.15)*step)),b=Math.min(size,(int)Math.round(begin+(x+.85)*step));
                values[x]=Math.max(0,Math.min(255,(mean(a,Math.max(1,b-a))-1500)*255/800));
            }return values;
        }
        private double mean(int start,int length){double total=0;int end=Math.min(size,start+length);for(int i=Math.max(0,start);i<end;i++)total+=buffer[i];return total/Math.max(1,end-Math.max(0,start));}
        private static boolean near(double value,double target,double tolerance){return Math.abs(value-target)<tolerance;}
        private void compact(){int drop=mode==null?Math.min(scan,size):Math.max(0,(int)Math.round(next)-samples(8));if(drop>0){System.arraycopy(buffer,drop,buffer,0,size-drop);size-=drop;scan=Math.max(0,scan-drop);next-=drop;}}
        public String incomplete(){return mode==null?"":"SSTV не завершён: "+line+"/"+mode.lines()+" строк";}
    }
    /** Quadrature FM discriminator with an antialias/negative-image FIR. */
    private static final class Fm {
        private static final int TAPS=49;
        final double[] coefficients=new double[TAPS],real=new double[TAPS],imaginary=new double[TAPS];
        int cursor;double oscillator,previousPhase;final double[] smooth=new double[3];int smoothIndex;
        Fm(){double sum=0,cutoff=1300.0/Audio.RATE;for(int i=0;i<TAPS;i++){int k=i-TAPS/2;double sinc=k==0?2*cutoff:Math.sin(2*Math.PI*cutoff*k)/(Math.PI*k);double window=.42-.5*Math.cos(2*Math.PI*i/(TAPS-1))+.08*Math.cos(4*Math.PI*i/(TAPS-1));coefficients[i]=sinc*window;sum+=coefficients[i];}for(int i=0;i<TAPS;i++)coefficients[i]/=sum;}
        float sample(short pcm){
            real[cursor]=pcm*Math.cos(oscillator);imaginary[cursor]=-pcm*Math.sin(oscillator);
            oscillator+=2*Math.PI*1900/Audio.RATE;if(oscillator>2*Math.PI)oscillator-=2*Math.PI;
            double re=0,im=0;int at=cursor;for(int i=0;i<TAPS;i++){re+=coefficients[i]*real[at];im+=coefficients[i]*imaginary[at];if(--at<0)at=TAPS-1;}
            cursor=(cursor+1)%TAPS;
            double phase=Math.atan2(im,re),delta=phase-previousPhase;previousPhase=phase;
            if(delta>Math.PI)delta-=2*Math.PI;if(delta< -Math.PI)delta+=2*Math.PI;
            double frequency=re*re+im*im<100?1900:1900+delta*Audio.RATE/(2*Math.PI);
            smooth[smoothIndex]=frequency;smoothIndex=(smoothIndex+1)%smooth.length;
            return (float)((smooth[0]+smooth[1]+smooth[2])/3);
        }
    }
}
