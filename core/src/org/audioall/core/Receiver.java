package org.audioall.core;

/** One microphone stream is offered to every decoder; TX runs independently. */
public final class Receiver implements Audio.Sink {
    private final PacketModem.Decoder packets;
    private final Sstv.Decoder images;
    public Receiver(PacketModem.Listener packetListener,Sstv.Listener imageListener){packets=new PacketModem.Decoder(packetListener);images=new Sstv.Decoder(imageListener);}
    public void write(short[] data,int offset,int length){packets.write(data,offset,length);images.write(data,offset,length);}
    /** Flush FM filter latency and final-line lookahead when a WAV ends at the raster boundary. */
    public void finish(){short[] tail=new short[Audio.RATE/100];write(tail,0,tail.length);}
    public String incomplete(){String s=packets.incomplete(),i=images.incomplete();return s+(i.isEmpty()?"":i+"\n");}
}
