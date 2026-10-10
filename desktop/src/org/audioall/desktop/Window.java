package org.audioall.desktop;

import org.audioall.core.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

final class Window extends JFrame {
    private static final Color BG=new Color(16,23,38),CARD=new Color(26,36,55),FG=new Color(232,240,249),MUTED=new Color(151,169,192),ACCENT=new Color(83,208,181);
    private final JComboBox<String> type=new JComboBox<>(new String[]{"Текст","Изображение SSTV","Аудиофайл","Видеофайл","Любой файл"});
    private final JComboBox<Sstv.Mode> mode=new JComboBox<>(Sstv.Mode.values());
    private final JComboBox<PacketModem.Profile> profile=new JComboBox<>(PacketModem.Profile.values());
    private final JSpinner repeats=new JSpinner(new SpinnerNumberModel(2,1,3,1));
    private final JTextArea message=new JTextArea("Привет! Это сообщение передано звуком.",5,35);
    private final JLabel fileLabel=new JLabel("Файл не выбран"),estimate=new JLabel(" "),txStatus=new JLabel("Готов к передаче"),rxStatus=new JLabel("Приём выключен"),level=new JLabel("Уровень: —");
    private final JComboBox<Device> input=new JComboBox<>(),output=new JComboBox<>();
    private final JProgressBar txProgress=new JProgressBar(0,1000),rxProgress=new JProgressBar(0,1000);
    private final JButton send=new JButton("▶ Передать звуком"),export=new JButton("Сохранить WAV"),cancel=new JButton("Остановить передачу"),receive=new JButton("● Включить приём"),decode=new JButton("Открыть WAV для приёма");
    private final JTextArea log=new JTextArea(8,45),receivedText=new JTextArea(6,35);
    private final DefaultListModel<Received> files=new DefaultListModel<>();private final JList<Received> fileList=new JList<>(files);
    private final Preview preview=new Preview();
    private final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"audioall-worker");t.setDaemon(true);return t;});
    private volatile boolean receiving,cancelTx,busyTx;private volatile SourceDataLine speaker;private volatile TargetDataLine microphone;
    private volatile Receiver live;private File selected;private Path directory=Paths.get(System.getProperty("user.home"),"AudioALL","received");
    private long lastTxUi,lastRxUi,lastLevel;private boolean decoding;
    Window(){
        super("AudioALL — данные через звук");setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);setMinimumSize(new Dimension(920,700));setSize(1180,830);setLocationRelativeTo(null);
        setTheme();JPanel root=new JPanel(new BorderLayout(18,18));root.setBorder(new EmptyBorder(22,24,20,24));root.setBackground(BG);setContentPane(root);
        JPanel header=new JPanel(new BorderLayout());header.setOpaque(false);JLabel title=new JLabel("AudioALL");title.setFont(new Font("SansSerif",Font.BOLD,30));title.setForeground(ACCENT);header.add(title,BorderLayout.WEST);
        JLabel subtitle=new JLabel("Аудио · Видео · SSTV · Текст     /     Локально, через динамик и микрофон");subtitle.setForeground(MUTED);header.add(subtitle,BorderLayout.EAST);root.add(header,BorderLayout.NORTH);
        JPanel tx=panel("Передача");tx.setLayout(new BoxLayout(tx,BoxLayout.Y_AXIS));
        tx.add(row("Что передать",type));tx.add(Box.createVerticalStrut(10));
        message.setLineWrap(true);message.setWrapStyleWord(true);JScrollPane textScroll=new JScrollPane(message);textScroll.setPreferredSize(new Dimension(440,110));textScroll.setMaximumSize(new Dimension(10000,140));tx.add(textScroll);
        JButton choose=new JButton("Выбрать файл…");choose.addActionListener(e->chooseFile());tx.add(row("Источник",choose));tx.add(fileLabel);tx.add(Box.createVerticalStrut(14));
        tx.add(row("Режим SSTV",mode));tx.add(row("Модем файлов",profile));tx.add(row("Повторов пакета",repeats));tx.add(row("Выход звука",output));tx.add(Box.createVerticalStrut(8));tx.add(estimate);tx.add(Box.createVerticalStrut(16));
        tx.add(row("",send,export));tx.add(row("",cancel));tx.add(txProgress);tx.add(txStatus);tx.add(Box.createVerticalGlue());
        JLabel hint=new JLabel("<html>Картинки: Robot 36/72, PD 50/120, Martin 1.<br>Аудио и видео передаются файлами. Для длинных файлов<br>уменьшите размер заранее. Максимум: 8 МиБ.</html>");hint.setForeground(MUTED);tx.add(hint);
        JPanel rx=panel("Приём • автоопределение всех протоколов");rx.setLayout(new BorderLayout(8,10));
        JPanel rxTop=new JPanel();rxTop.setOpaque(false);rxTop.setLayout(new BoxLayout(rxTop,BoxLayout.Y_AXIS));rxTop.add(row("Вход звука",input));rxTop.add(row("",receive,decode));rxTop.add(row("",rxStatus,level));rxTop.add(rxProgress);rx.add(rxTop,BorderLayout.NORTH);
        JTabbedPane tabs=new JTabbedPane();tabs.addTab("Изображение",preview);receivedText.setEditable(false);receivedText.setLineWrap(true);receivedText.setWrapStyleWord(true);tabs.addTab("Текст",new JScrollPane(receivedText));
        JPanel saved=new JPanel(new BorderLayout());saved.setOpaque(false);saved.add(new JScrollPane(fileList),BorderLayout.CENTER);JButton open=new JButton("Открыть выбранный файл");open.addActionListener(e->openSelected());saved.add(open,BorderLayout.SOUTH);tabs.addTab("Файлы",saved);rx.add(tabs,BorderLayout.CENTER);
        log.setEditable(false);log.setLineWrap(true);log.setWrapStyleWord(true);JScrollPane logScroll=new JScrollPane(log);logScroll.setPreferredSize(new Dimension(400,125));rx.add(logScroll,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,tx,rx);split.setResizeWeight(.43);split.setBorder(null);root.add(split,BorderLayout.CENTER);
        JButton folder=new JButton("Папка приёма…");folder.addActionListener(e->chooseFolder());JPanel footer=new JPanel(new BorderLayout());footer.setOpaque(false);footer.add(folder,BorderLayout.WEST);
        JLabel duplex=new JLabel("Передача и приём работают одновременно • Для встречной передачи используйте раздельные звуковые каналы");duplex.setForeground(MUTED);footer.add(duplex,BorderLayout.EAST);root.add(footer,BorderLayout.SOUTH);
        input.addItem(new Device(null));output.addItem(new Device(null));
        for(Mixer.Info info:AudioSystem.getMixerInfo()) {Mixer m=AudioSystem.getMixer(info);if(m.getTargetLineInfo().length>0)input.addItem(new Device(info));if(m.getSourceLineInfo().length>0)output.addItem(new Device(info));}
        type.addActionListener(e->selectionChanged());profile.addActionListener(e->updateEstimate());mode.addActionListener(e->updateEstimate());repeats.addChangeListener(e->updateEstimate());
        message.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){updateEstimate();}public void removeUpdate(javax.swing.event.DocumentEvent e){updateEstimate();}public void changedUpdate(javax.swing.event.DocumentEvent e){updateEstimate();}});
        send.addActionListener(e->transmit(false));export.addActionListener(e->transmit(true));cancel.addActionListener(e->{cancelTx=true;SourceDataLine s=speaker;if(s!=null){s.stop();s.flush();s.close();}});
        receive.addActionListener(e->{if(receiving)stopReceive();else startReceive();});decode.addActionListener(e->decodeFile());cancel.setEnabled(false);
        fileList.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)openSelected();}});
        addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){cancelTx=true;stopReceive();SourceDataLine s=speaker;if(s!=null)s.close();workers.shutdownNow();}});
        selectionChanged();append("Файлы сохраняются в "+directory.toAbsolutePath());
    }
    private void setTheme(){
        String[] bg={"Panel.background","Viewport.background","ScrollPane.background","TabbedPane.background","OptionPane.background"};for(String key:bg)UIManager.put(key,CARD);
        String[] fg={"Label.foreground","Button.foreground","ToggleButton.foreground","CheckBox.foreground","TabbedPane.foreground","ComboBox.foreground","TextArea.foreground","List.foreground"};for(String key:fg)UIManager.put(key,FG);
        UIManager.put("Button.background",new Color(46,65,88));UIManager.put("ComboBox.background",CARD);UIManager.put("TextArea.background",BG);UIManager.put("TextArea.caretForeground",FG);UIManager.put("List.background",BG);
        UIManager.put("ProgressBar.foreground",ACCENT);UIManager.put("ProgressBar.background",BG);
        for(JComponent c:new JComponent[]{message,log,receivedText,fileList}){c.setBackground(BG);c.setForeground(FG);c.setFont(new Font("SansSerif",Font.PLAIN,14));}
        for(JLabel label:new JLabel[]{fileLabel,estimate,txStatus,rxStatus,level})label.setForeground(FG);
    }
    private JPanel panel(String title){JPanel p=new JPanel();p.setBackground(CARD);p.setBorder(BorderFactory.createTitledBorder(new CompoundBorder(new LineBorder(new Color(48,66,90)),new EmptyBorder(14,14,14,14)),title,TitledBorder.LEFT,TitledBorder.TOP,new Font("SansSerif",Font.BOLD,16),FG));return p;}
    private JPanel row(String label,JComponent... components){JPanel r=new JPanel(new FlowLayout(FlowLayout.LEFT,7,5));r.setOpaque(false);r.setAlignmentX(Component.LEFT_ALIGNMENT);r.setMaximumSize(new Dimension(10000,42));if(!label.isEmpty()){JLabel l=new JLabel(label);l.setForeground(MUTED);l.setPreferredSize(new Dimension(135,25));r.add(l);}for(JComponent c:components)r.add(c);return r;}
    private void selectionChanged(){boolean image=type.getSelectedIndex()==1;textEnabled(type.getSelectedIndex()==0);mode.setEnabled(image);profile.setEnabled(!image);repeats.setEnabled(!image);updateEstimate();}
    private void textEnabled(boolean value){message.setEnabled(value);}
    private void chooseFile(){JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){selected=chooser.getSelectedFile();fileLabel.setText(selected.getName()+" • "+selected.length()+" байт");updateEstimate();}}
    private void updateEstimate(){try{if(type.getSelectedIndex()==1){Sstv.Mode m=(Sstv.Mode)mode.getSelectedItem();estimate.setText("Время: "+duration(m.seconds())+" • "+m.width+"×"+m.height);}else{long bytes=type.getSelectedIndex()==0?message.getText().getBytes(StandardCharsets.UTF_8).length:selected==null?0:selected.length();double seconds=PacketModem.seconds((int)Math.min(Transfer.MAX_BYTES+4096,bytes+512),(PacketModem.Profile)profile.getSelectedItem(),(Integer)repeats.getValue());estimate.setText("Оценка до сжатия: "+duration(seconds));}}catch(Exception e){estimate.setText(" ");}}
    private static String duration(double seconds){long s=(long)Math.ceil(seconds);return s<60?s+" сек":s<3600?s/60+" мин "+s%60+" сек":s/3600+" ч "+s%3600/60+" мин";}
    private Transfer getTransfer(int kind,String text,File file)throws IOException{if(kind==0)return Transfer.text(text);if(file==null)throw new IOException("Выберите файл");return MediaIO.file(file,kind==2?Transfer.Kind.AUDIO:kind==3?Transfer.Kind.VIDEO:Transfer.Kind.FILE);}
    private void transmit(boolean toFile){
        if(busyTx)return;int kind=type.getSelectedIndex();if(kind!=0 && selected==null){error("Выберите файл");return;}
        File destination=null;if(toFile){JFileChooser c=new JFileChooser();c.setSelectedFile(new File("transmission.wav"));if(c.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;destination=c.getSelectedFile();if(destination.exists()&&JOptionPane.showConfirmDialog(this,"Заменить выбранный WAV?","Сохранение",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;}
        final File target=destination,file=selected;final String text=message.getText();final Sstv.Mode selectedMode=(Sstv.Mode)mode.getSelectedItem();final PacketModem.Profile selectedProfile=(PacketModem.Profile)profile.getSelectedItem();final int repeat=(Integer)repeats.getValue();final Device device=(Device)output.getSelectedItem();
        busyTx=true;cancelTx=false;send.setEnabled(false);export.setEnabled(false);cancel.setEnabled(true);txProgress.setValue(0);txStatus.setText(toFile?"Кодирование WAV…":"Подготовка передачи…");lastTxUi=0;
        Audio.Progress progress=new Audio.Progress(){public boolean cancelled(){return cancelTx;}public void update(double f,String s){long now=System.currentTimeMillis();if(now-lastTxUi>120 || f>=1){lastTxUi=now;SwingUtilities.invokeLater(()->{txProgress.setValue((int)(f*1000));txStatus.setText(s);});}}};
        workers.submit(()->{
            File partial=target==null?null:new File(target.getAbsolutePath()+".part");
            try{
                Raster image=kind==1?MediaIO.image(file):null;Transfer transfer=kind==1?null:getTransfer(kind,text,file);
                if(target!=null){try(Wave.Writer sink=new Wave.Writer(partial)){if(kind==1)Sstv.encode(image,selectedMode,sink,progress);else PacketModem.encode(transfer,selectedProfile,repeat,sink,progress);}Audio.check(progress);Files.move(partial.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);append("WAV сохранён: "+target.getAbsolutePath());}
                else{AudioFormat format=new AudioFormat(Audio.RATE,16,1,true,false);DataLine.Info info=new DataLine.Info(SourceDataLine.class,format);SourceDataLine line=(SourceDataLine)device.line(info);speaker=line;try{line.open(format,Audio.RATE/5);line.start();Audio.Sink sink=(s,off,len)->{Audio.check(progress);byte[] b=new byte[len*2];for(int i=0;i<len;i++){b[i*2]=(byte)s[off+i];b[i*2+1]=(byte)(s[off+i]>>8);}int at=0;while(at<b.length){Audio.check(progress);int n=line.write(b,at,b.length-at);if(n<=0)throw new IOException("Звуковой выход закрыт");at+=n;}};if(kind==1)Sstv.encode(image,selectedMode,sink,progress);else PacketModem.encode(transfer,selectedProfile,repeat,sink,progress);line.drain();}finally{line.close();speaker=null;}}
                SwingUtilities.invokeLater(()->{txStatus.setText("Готово");txProgress.setValue(1000);});
            }catch(Exception e){if(partial!=null)partial.delete();append("Передача: "+(cancelTx?"остановлена":e.getMessage()));SwingUtilities.invokeLater(()->txStatus.setText(cancelTx?"Остановлено":"Ошибка передачи"));}
            finally{busyTx=false;SwingUtilities.invokeLater(()->{send.setEnabled(true);export.setEnabled(true);cancel.setEnabled(false);});}
        });
    }
    private Receiver makeReceiver(Path folder){
        PacketModem.Listener packets=new PacketModem.Listener(){
            public void status(String s){rxUpdate(s);}public void error(String s){append("Ошибка приёма: "+s);}
            public void transfer(Transfer t){try{Path path=MediaIO.save(folder,t.name,t.data);SwingUtilities.invokeLater(()->{files.addElement(new Received(path,t.kind.toString()));if(t.kind==Transfer.Kind.TEXT)receivedText.append(new String(t.data,StandardCharsets.UTF_8)+"\n\n");});append("Получен "+t.kind+": "+path.getFileName());rxUpdate("Файл полностью восстановлен");}catch(IOException e){append("Ошибка сохранения: "+e.getMessage());}}
        };
        Sstv.Listener images=new Sstv.Listener(){public void status(String s){append(s);rxUpdate(s);}public void image(Raster r,Sstv.Mode m,boolean complete,int lines){SwingUtilities.invokeLater(()->{preview.setRaster(r);rxStatus.setText(m.label+": "+lines+"/"+m.height+" строк");});if(complete)try{Path path=MediaIO.saveImage(folder,r,m);SwingUtilities.invokeLater(()->files.addElement(new Received(path,"SSTV")));append("Изображение сохранено: "+path.getFileName());}catch(IOException e){append("Ошибка сохранения: "+e.getMessage());}}};
        return new Receiver(packets,images);
    }
    private void startReceive(){
        if(receiving)return;Device device=(Device)input.getSelectedItem();Path folder=directory;Receiver receiver=makeReceiver(folder);live=receiver;receiving=true;receive.setText("■ Остановить приём");input.setEnabled(false);rxStatus.setText("Открываю микрофон…");
        workers.submit(()->{TargetDataLine line=null;try{
            AudioFormat format=new AudioFormat(Audio.RATE,16,1,true,false);line=(TargetDataLine)device.line(new DataLine.Info(TargetDataLine.class,format));microphone=line;line.open(format,Audio.RATE/2);line.start();rxUpdate("Слушаю • SSTV + все FSK");byte[] b=new byte[4096];short[] s=new short[2048];
            while(receiving){int n=line.read(b,0,b.length);if(n<=0)break;double energy=0;for(int i=0;i<n/2;i++){s[i]=(short)((b[i*2]&255)|(b[i*2+1]<<8));energy+=(double)s[i]*s[i];}if(System.currentTimeMillis()-lastLevel>200){lastLevel=System.currentTimeMillis();double rms=Math.sqrt(energy/Math.max(1,n/2));double db=20*Math.log10(Math.max(1,rms)/32768);SwingUtilities.invokeLater(()->level.setText(String.format(java.util.Locale.ROOT,"Уровень: %.0f dBFS",db)));}receiver.write(s,0,n/2);}
        }catch(Exception e){if(receiving)append("Микрофон: "+e.getMessage());}finally{if(line!=null)line.close();microphone=null;receiving=false;String missing=receiver.incomplete();if(!missing.isEmpty())append(missing);SwingUtilities.invokeLater(()->{receive.setText("● Включить приём");input.setEnabled(true);rxStatus.setText("Приём выключен");level.setText("Уровень: —");});}});
    }
    private void stopReceive(){receiving=false;TargetDataLine m=microphone;if(m!=null){m.stop();m.close();}}
    private void decodeFile(){
        if(decoding)return;JFileChooser c=new JFileChooser();if(c.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;File file=c.getSelectedFile();Path folder=directory;decoding=true;decode.setEnabled(false);rxProgress.setValue(0);Receiver receiver=makeReceiver(folder);
        workers.submit(()->{try(InputStream in=new FileInputStream(file)){Wave.read(in,receiver,new Audio.Progress(){public boolean cancelled(){return Thread.currentThread().isInterrupted();}public void update(double f,String s){SwingUtilities.invokeLater(()->rxProgress.setValue((int)(f*1000)));}});String missing=receiver.incomplete();append(missing.isEmpty()?"WAV обработан: "+file.getName():missing);}catch(Exception e){append("WAV: "+e.getMessage());}finally{decoding=false;SwingUtilities.invokeLater(()->{decode.setEnabled(true);rxProgress.setValue(1000);});}});
    }
    private void rxUpdate(String s){long now=System.currentTimeMillis();if(now-lastRxUi>100){lastRxUi=now;SwingUtilities.invokeLater(()->rxStatus.setText(s));}}
    private void chooseFolder(){JFileChooser c=new JFileChooser(directory.toFile());c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(c.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){directory=c.getSelectedFile().toPath();append("Новая папка: "+directory+" (для следующих сеансов приёма)");}}
    private void openSelected(){Received r=fileList.getSelectedValue();if(r==null)return;try{Desktop.getDesktop().open(r.path.toFile());}catch(Exception e){error("Не удалось открыть: "+e.getMessage());}}
    private void error(String s){JOptionPane.showMessageDialog(this,s,"AudioALL",JOptionPane.ERROR_MESSAGE);}
    private void append(String s){SwingUtilities.invokeLater(()->{if(log.getDocument().getLength()>24000)log.setText(log.getText().substring(log.getText().length()-12000));log.append(s+"\n");log.setCaretPosition(log.getDocument().getLength());});}
    private static final class Device {final Mixer.Info info;Device(Mixer.Info info){this.info=info;}Line line(DataLine.Info line)throws LineUnavailableException{return info==null?AudioSystem.getLine(line):AudioSystem.getMixer(info).getLine(line);}@Override public String toString(){return info==null?"По умолчанию":info.getName();}}
    private static final class Received {final Path path;final String kind;Received(Path path,String kind){this.path=path;this.kind=kind;}@Override public String toString(){return kind+" • "+path.getFileName();}}
    private static final class Preview extends JPanel {private java.awt.image.BufferedImage image;Preview(){setBackground(BG);setPreferredSize(new Dimension(440,340));}void setRaster(Raster r){image=MediaIO.buffered(r);repaint();}protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(image==null){graphics.setColor(MUTED);graphics.drawString("Здесь появится принятое SSTV-изображение",25,getHeight()/2);return;}double scale=Math.min((double)getWidth()/image.getWidth(),(double)getHeight()/image.getHeight());int w=(int)(image.getWidth()*scale),h=(int)(image.getHeight()*scale);Graphics2D g=(Graphics2D)graphics;g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,(getWidth()-w)/2,(getHeight()-h)/2,w,h,null);}}
}
