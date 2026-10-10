package org.audioall.core;

/** One microphone stream is offered to every decoder; TX runs independently. */
public final class Receiver implements Audio.Sink {
    private final PacketModem.Decoder packets;
    private final Sstv.Decoder images;
    public Receiver(PacketModem.Listener packetListener,Sstv.Listener imageListener){packets=new PacketModem.Decoder(packetListener);images=new Sstv.Decoder(imageListener);}
    public void write(short[] data,int offset,int length){packets.write(data,offset,length);images.write(data,offset,length);}
    public String incomplete(){String s=packets.incomplete(),i=images.incomplete();return s+(i.isEmpty()?"":i+"\n");}
}
