package org.audioall.tests;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Constructs and paints the actual desktop window on a real graphical display. */
public final class DesktopSmoke {
    public static void main(String[] args)throws Exception{
        final Throwable[] failure={null};
        SwingUtilities.invokeAndWait(()->{JFrame window=null;try{
            java.lang.reflect.Constructor<?> c=Class.forName("org.audioall.desktop.Window").getDeclaredConstructor();c.setAccessible(true);window=(JFrame)c.newInstance();window.setVisible(true);window.validate();
            BufferedImage image=new BufferedImage(window.getWidth(),window.getHeight(),BufferedImage.TYPE_INT_RGB);java.awt.Graphics2D g=image.createGraphics();window.paint(g);g.dispose();
            ImageIO.write(image,"png",new File(args.length>0?args[0]:"build/desktop-smoke.png"));
            if(window.getContentPane().getComponentCount()<3)throw new AssertionError("Missing window sections");
            System.out.println("PASS desktop window: construction, layout and rendering");
        }catch(Throwable e){failure[0]=e;}finally{if(window!=null)window.dispose();}});
        if(failure[0]!=null)throw new AssertionError("Desktop smoke test",failure[0]);
    }
}
