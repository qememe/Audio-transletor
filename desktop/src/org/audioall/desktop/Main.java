package org.audioall.desktop;

import org.audioall.core.*;
import javax.swing.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args)throws Exception{
        if(args.length>0){cli(args);return;}
        if(java.awt.GraphicsEnvironment.isHeadless()){System.err.println("Нет графического экрана. Используйте команды encode / decode / devices или --help.");System.exit(2);}
        SwingUtilities.invokeLater(()->{try{UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());new Window().setVisible(true);}catch(Exception e){e.printStackTrace();}});
    }
    private static void cli(String[] args)throws Exception{
        String command=args[0];Map<String,String> options=new HashMap<>();
        for(int i=1;i<args.length;i++){if(!args[i].startsWith("--") || i+1>=args.length)throw new IllegalArgumentException("Опция должна иметь значение: "+args[i]);options.put(args[i],args[++i]);}
        if(command.equals("--help") || command.equals("help")){
            System.out.println("AudioALL — аудио/видео/изображения/текст через звук\n"+
                "encode --text 'Привет' --out message.wav [--profile FAST|ROBUST|BELL202] [--repeat 1|2|3]\n"+
                "encode --file clip.mp4 --kind VIDEO --out clip.wav\n"+
                "encode --image photo.png --mode ROBOT36|ROBOT72|PD50|PD120|MARTIN1 --out image.wav\n"+
                "decode --wav signal.wav --out received-folder\n"+
                "devices\nГрафический интерфейс: java -jar AudioALL.jar");return;
        }
        if(command.equals("devices")){for(Mixer.Info info:AudioSystem.getMixerInfo())System.out.println(info.getName()+" — "+info.getDescription());return;}
        if(command.equals("encode")){
            String output=required(options,"--out");File target=new File(output);File temporary=new File(output+".part");
            try(Wave.Writer writer=new Wave.Writer(temporary)){
                if(options.containsKey("--image")){Sstv.Mode mode=Sstv.Mode.valueOf(options.getOrDefault("--mode","ROBOT36").toUpperCase(Locale.ROOT));Sstv.encode(MediaIO.image(new File(options.get("--image"))),mode,writer,Audio.NONE);}
                else{Transfer transfer=options.containsKey("--text")?Transfer.text(options.get("--text")):MediaIO.file(new File(required(options,"--file")),Transfer.Kind.valueOf(options.getOrDefault("--kind","FILE").toUpperCase(Locale.ROOT)));PacketModem.encode(transfer,PacketModem.Profile.valueOf(options.getOrDefault("--profile","ROBUST").toUpperCase(Locale.ROOT)),Integer.parseInt(options.getOrDefault("--repeat","2")),writer,Audio.NONE);}
            }catch(Exception e){temporary.delete();throw e;}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);System.out.println("WAV: "+target.getAbsolutePath());return;
        }
        if(command.equals("decode")){
            Path output=Paths.get(required(options,"--out"));final int[] count={0};
            PacketModem.Listener files=new PacketModem.Listener(){public void status(String s){}public void error(String s){System.err.println(s);}public void transfer(Transfer t){try{Path p=MediaIO.save(output,t.name,t.data);count[0]++;System.out.println(t.kind+": "+p);if(t.kind==Transfer.Kind.TEXT)System.out.println(new String(t.data,"UTF-8"));}catch(IOException e){throw new java.io.UncheckedIOException(e);}}};
            Sstv.Listener images=new Sstv.Listener(){public void status(String s){System.out.println(s);}public void image(Raster r,Sstv.Mode m,boolean complete,int lines){if(complete)try{System.out.println("SSTV: "+MediaIO.saveImage(output,r,m));count[0]++;}catch(IOException e){throw new java.io.UncheckedIOException(e);}}};
            Receiver receiver=new Receiver(files,images);try(InputStream in=new FileInputStream(required(options,"--wav"))){Wave.read(in,receiver,Audio.NONE);}
            receiver.finish();String incomplete=receiver.incomplete();if(!incomplete.isEmpty())System.err.println(incomplete);if(count[0]==0)throw new IOException("Ни одной полной передачи не найдено");return;
        }
        throw new IllegalArgumentException("Неизвестная команда. Используйте --help");
    }
    private static String required(Map<String,String> options,String key){String v=options.get(key);if(v==null)throw new IllegalArgumentException("Нужна опция "+key);return v;}
}
