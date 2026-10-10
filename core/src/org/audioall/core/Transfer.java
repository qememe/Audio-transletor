package org.audioall.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.zip.*;

/** Versioned lossless envelope: UTF-8 metadata, DEFLATE, SHA-256. */
public final class Transfer {
    public static final int MAX_BYTES=8*1024*1024;
    public enum Kind { TEXT, AUDIO, VIDEO, FILE }
    public final Kind kind;
    public final String name, mime;
    public final byte[] data;
    public Transfer(Kind kind,String name,String mime,byte[] data) {
        if(data.length>MAX_BYTES)throw new IllegalArgumentException("Файл больше 8 МиБ");
        this.kind=kind;this.name=safeName(name);this.mime=mime;this.data=data.clone();
    }
    public static Transfer text(String text) {return new Transfer(Kind.TEXT,"message.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));}
    public byte[] pack() throws IOException {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        try(DeflaterOutputStream zip=new DeflaterOutputStream(raw)){zip.write(data);}
        boolean compressed=raw.size()<data.length;byte[] payload=compressed?raw.toByteArray():data;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x41414c31);out.writeByte(kind.ordinal());out.writeUTF(name);out.writeUTF(mime);
        out.writeBoolean(compressed);out.writeInt(data.length);out.write(sha(data));out.write(payload);out.flush();
        return bytes.toByteArray();
    }
    public static Transfer unpack(byte[] packed) throws IOException {
        if(packed.length>MAX_BYTES+4096)throw new IOException("Слишком большой пакет");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(packed));
        if(in.readInt()!=0x41414c31)throw new IOException("Неизвестный формат AudioALL");
        int k=in.readUnsignedByte();if(k>=Kind.values().length)throw new IOException("Неизвестный тип");
        String name=in.readUTF(),mime=in.readUTF();boolean compressed=in.readBoolean();int size=in.readInt();
        if(size<0 || size>MAX_BYTES)throw new IOException("Размер вне допустимых пределов");
        byte[] hash=new byte[32];in.readFully(hash);
        InputStream stream=compressed?new InflaterInputStream(in):in;
        ByteArrayOutputStream data=new ByteArrayOutputStream(Math.min(size,65536));byte[] b=new byte[8192];int n;
        while((n=stream.read(b))!=-1){if(data.size()+n>size)throw new IOException("Переполнение данных");data.write(b,0,n);}
        byte[] content=data.toByteArray();
        if(content.length!=size || !MessageDigest.isEqual(hash,sha(content)))throw new IOException("SHA-256 не совпадает: файл повреждён");
        return new Transfer(Kind.values()[k],name,mime,content);
    }
    public static String safeName(String name) {
        String result=name.replace('\\','/');result=result.substring(result.lastIndexOf('/')+1);
        result=result.replaceAll("[^\\p{L}\\p{N}._ -]","_");
        if(result.isEmpty() || result.equals(".") || result.equals(".."))result="received.bin";
        return result.substring(0,Math.min(120,result.length()));
    }
    private static byte[] sha(byte[] data) {try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("Файл больше 8 МиБ");out.write(b,0,n);}return out.toByteArray();
    }
}
