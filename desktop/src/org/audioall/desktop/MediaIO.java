package org.audioall.desktop;

import org.audioall.core.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

final class MediaIO {
    static Raster image(File file)throws IOException{
        try(javax.imageio.stream.ImageInputStream in=ImageIO.createImageInputStream(file)){
            if(in==null)throw new IOException("Не удалось открыть изображение");
            java.util.Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(in);
            if(!readers.hasNext())throw new IOException("Поддерживаются PNG, JPEG, BMP, GIF");
            javax.imageio.ImageReader reader=readers.next();
            try{reader.setInput(in);if((long)reader.getWidth(0)*reader.getHeight(0)>32_000_000)throw new IOException("Изображение слишком большое (максимум 32 Мп)");BufferedImage b=reader.read(0);return new Raster(b.getWidth(),b.getHeight(),b.getRGB(0,0,b.getWidth(),b.getHeight(),null,0,b.getWidth()));}finally{reader.dispose();}
        }
    }
    static BufferedImage buffered(Raster r){BufferedImage b=new BufferedImage(r.width,r.height,BufferedImage.TYPE_INT_RGB);b.setRGB(0,0,r.width,r.height,r.pixels,0,r.width);return b;}
    static Transfer file(File file,Transfer.Kind kind)throws IOException{
        try(InputStream in=new FileInputStream(file)){String mime=Files.probeContentType(file.toPath());return new Transfer(kind,file.getName(),mime==null?"application/octet-stream":mime,Transfer.readLimited(in));}
    }
    static Path save(Path directory,String name,byte[] data)throws IOException{
        Files.createDirectories(directory);String safe=Transfer.safeName(name);Path path=directory.resolve(System.currentTimeMillis()+"-"+java.util.UUID.randomUUID().toString().substring(0,8)+"-"+safe);
        Files.write(path,data,StandardOpenOption.CREATE_NEW);return path;
    }
    static Path saveImage(Path directory,Raster r,Sstv.Mode mode)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(buffered(r),"png",out);return save(directory,mode.name().toLowerCase(Locale.ROOT)+".png",out.toByteArray());
    }
}
