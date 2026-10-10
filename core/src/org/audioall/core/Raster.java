package org.audioall.core;

/** Platform-neutral ARGB bitmap. */
public final class Raster {
    public final int width,height;
    public final int[] pixels;
    public Raster(int width,int height,int[] pixels){if(width<1 || height<1 || (long)width*height!=pixels.length)throw new IllegalArgumentException("Размер изображения");this.width=width;this.height=height;this.pixels=pixels;}
    public Raster(int width,int height){this(width,height,new int[width*height]);java.util.Arrays.fill(pixels,0xff000000);}
    public Raster copy(){return new Raster(width,height,pixels.clone());}
    /** Fit with black bars, preserving aspect ratio. Bilinear interpolation. */
    public Raster fit(int w,int h){
        Raster out=new Raster(w,h);double scale=Math.min((double)w/width,(double)h/height);
        int dw=Math.max(1,(int)Math.round(width*scale)),dh=Math.max(1,(int)Math.round(height*scale));
        int ox=(w-dw)/2,oy=(h-dh)/2;
        for(int y=0;y<dh;y++)for(int x=0;x<dw;x++){
            double sx=Math.max(0,Math.min(width-1,(x+.5)/scale-.5)),sy=Math.max(0,Math.min(height-1,(y+.5)/scale-.5));
            int x0=(int)sx,y0=(int)sy,x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);double fx=sx-x0,fy=sy-y0;int rgb=0xff000000;
            for(int shift=0;shift<=16;shift+=8){double a=((pixels[y0*width+x0]>>>shift)&255)*(1-fx)+((pixels[y0*width+x1]>>>shift)&255)*fx;double b=((pixels[y1*width+x0]>>>shift)&255)*(1-fx)+((pixels[y1*width+x1]>>>shift)&255)*fx;rgb|=(int)Math.round(a*(1-fy)+b*fy)<<shift;}
            out.pixels[(y+oy)*w+x+ox]=rgb;
        }return out;
    }
}
