package org.audioall.android;

import org.audioall.core.*;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.media.*;
import android.media.audiofx.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int PICK=10,WAV=11,EXPORT=12,SAVE_RECEIVED=13,MIC=21;
    private static final int BG=0xff101726,CARD=0xff1a2437,FG=0xffe8f0f9,MUTED=0xff97a9c0,ACCENT=0xff53d0b5;
    private final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"audioall");t.setDaemon(true);return t;});
    private Spinner type,mode,profile,repeats,input,output;
    private EditText message;private TextView fileName,estimate,txStatus,rxStatus,receivedText,log,level;
    private Button send,export,stopTx,receive,decode;
    private ProgressBar txProgress,rxProgress;private ImageView preview;
    private Uri selected;private String selectedName="file.bin";private long selectedSize;
    private volatile boolean receiving,txBusy,cancelTx,rxBusy,destroyed;
    private volatile AudioRecord microphone;private volatile AudioTrack speaker;
    private boolean wavBusy;private long lastTxUi,lastRxUi,lastLevel;
    private final ArrayList<AudioDeviceInfo> inputs=new ArrayList<>(),outputs=new ArrayList<>();
    private File receivedToSave;private TxRequest pendingExport;
    private final ArrayList<File> received=new ArrayList<>();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);
        shell.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(shell);shell.requestApplyInsets();
        ScrollView scroll=new ScrollView(this);shell.addView(scroll,new LinearLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setPadding(dp(18),dp(16),dp(18),dp(24));body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);
        TextView title=label("AudioALL",30,ACCENT);title.setTypeface(null,Typeface.BOLD);body.addView(title);body.addView(label("Данные через звук · Linux / Windows / Android",13,MUTED));
        LinearLayout tx=card(body,"Передача");
        type=spinner(tx,new String[]{"Текст","Изображение SSTV","Аудиофайл","Видеофайл","Любой файл"},"Что передать");
        message=new EditText(this);message.setText("Привет! Это сообщение передано звуком.");message.setTextColor(FG);message.setMinLines(3);message.setMaxLines(6);message.setGravity(Gravity.TOP);message.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);tx.addView(message);
        Button choose=button(tx,"Выбрать файл…");choose.setOnClickListener(v->{String mime=type.getSelectedItemPosition()==1?"image/*":type.getSelectedItemPosition()==2?"audio/*":type.getSelectedItemPosition()==3?"video/*":"*/*";pick(PICK,mime);});
        fileName=label("Файл не выбран",13,MUTED);tx.addView(fileName);
        mode=spinner(tx,Sstv.Mode.values(),"Режим SSTV");profile=spinner(tx,PacketModem.Profile.values(),"Модем файлов");repeats=spinner(tx,new String[]{"1 раз","2 раза","3 раза"},"Повторы пакета");repeats.setSelection(1);
        inputs.add(null);outputs.add(null);AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_INPUTS))inputs.add(d);
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))outputs.add(d);
        output=spinner(tx,deviceLabels(outputs),"Выход звука");
        estimate=label(" ",14,ACCENT);tx.addView(estimate);
        send=button(tx,"▶ Передать звуком");send.setOnClickListener(v->{TxRequest request=request();if(request!=null)transmit(request,null);});
        export=button(tx,"Сохранить сигнал в WAV");export.setOnClickListener(v->{TxRequest request=request();if(request!=null){pendingExport=request;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/wav");i.putExtra(Intent.EXTRA_TITLE,"transmission.wav");startActivityForResult(i,EXPORT);}});
        stopTx=button(tx,"Остановить передачу");stopTx.setEnabled(false);stopTx.setOnClickListener(v->cancelTransmission());txProgress=progress(tx);txStatus=label("Готов к передаче",13,MUTED);tx.addView(txStatus);
        tx.addView(label("Аудио и видео передаются файлами целиком. Максимум 8 МиБ. Для короткой передачи заранее уменьшите размер файла.",13,MUTED));
        LinearLayout rx=card(body,"Приём • все протоколы автоматически");input=spinner(rx,deviceLabels(inputs),"Вход звука");
        receive=button(rx,"● Включить приём");receive.setOnClickListener(v->{if(rxBusy)stopReceive();else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC);else startReceive();});
        decode=button(rx,"Открыть WAV для приёма");decode.setOnClickListener(v->pick(WAV,"*/*"));
        rxStatus=label("Приём выключен",14,FG);rx.addView(rxStatus);level=label("Уровень: —",12,MUTED);rx.addView(level);rxProgress=progress(rx);
        preview=new ImageView(this);preview.setBackgroundColor(BG);preview.setAdjustViewBounds(true);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setMinimumHeight(dp(160));rx.addView(preview,new LinearLayout.LayoutParams(-1,dp(250)));
        receivedText=label("Принятые сообщения появятся здесь",15,FG);receivedText.setTextIsSelectable(true);rx.addView(receivedText);
        Button files=button(rx,"Принятые файлы • открыть / сохранить / поделиться");files.setOnClickListener(v->showFiles());
        log=label("Для приёма держите приложение открытым. Передачу и приём можно включать одновременно. Для встречной передачи используйте раздельные звуковые каналы.",12,MUTED);rx.addView(log);
        AdapterView.OnItemSelectedListener selection=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){updateSelection();}public void onNothingSelected(AdapterView<?> p){}};
        type.setOnItemSelectedListener(selection);mode.setOnItemSelectedListener(selection);profile.setOnItemSelectedListener(selection);repeats.setOnItemSelectedListener(selection);
        message.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){updateEstimate();}public void afterTextChanged(android.text.Editable e){}});
        File folder=new File(getFilesDir(),"received");File[] existing=folder.listFiles();if(existing!=null){Arrays.sort(existing,Comparator.comparingLong(File::lastModified));for(File f:existing)if(f.isFile()&&!f.getName().endsWith(".part"))received.add(f);}
        updateSelection();
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(6));return t;}
    private LinearLayout card(LinearLayout body,String title){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(14));android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(CARD);bg.setCornerRadius(dp(14));box.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(18);body.addView(box,p);TextView heading=label(title,20,FG);heading.setTypeface(null,Typeface.BOLD);box.addView(heading);return box;}
    private Spinner spinner(LinearLayout parent,Object[] values,String label){parent.addView(label(label,12,MUTED));Spinner s=new Spinner(this);ArrayAdapter<Object> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);parent.addView(s,new LinearLayout.LayoutParams(-1,dp(44)));return s;}
    private Button button(LinearLayout parent,String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(FG);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(5);parent.addView(b,p);return b;}
    private ProgressBar progress(LinearLayout parent){ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setMax(1000);parent.addView(p,new LinearLayout.LayoutParams(-1,dp(14)));return p;}
    private Object[] deviceLabels(ArrayList<AudioDeviceInfo> devices){Object[] labels=new Object[devices.size()];labels[0]="По умолчанию";for(int i=1;i<labels.length;i++)labels[i]=devices.get(i).getProductName()+" ("+devices.get(i).getId()+")";return labels;}
    private void updateSelection(){if(type==null||mode==null)return;boolean image=type.getSelectedItemPosition()==1;message.setEnabled(type.getSelectedItemPosition()==0);mode.setEnabled(image);profile.setEnabled(!image);repeats.setEnabled(!image);updateEstimate();}
    private void updateEstimate(){if(estimate==null)return;if(type.getSelectedItemPosition()==1){Sstv.Mode m=(Sstv.Mode)mode.getSelectedItem();estimate.setText("Время: "+duration(m.seconds())+" · "+m.width+"×"+m.height);}else{long size=type.getSelectedItemPosition()==0?message.getText().toString().getBytes(StandardCharsets.UTF_8).length:selectedSize;double seconds=PacketModem.seconds((int)Math.min(Transfer.MAX_BYTES+4096,Math.max(0,size)+512),(PacketModem.Profile)profile.getSelectedItem(),repeats.getSelectedItemPosition()+1);estimate.setText("Оценка до сжатия: "+duration(seconds));}}
    private static String duration(double value){long s=(long)Math.ceil(value);return s<60?s+" сек":s<3600?s/60+" мин "+s%60+" сек":s/3600+" ч "+s%3600/60+" мин";}
    private void pick(int code,String mime){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);startActivityForResult(i,code);}
    private TxRequest request(){if(txBusy)return null;int kind=type.getSelectedItemPosition();if(kind!=0&&selected==null){notice("Выберите файл");return null;}return new TxRequest(kind,message.getText().toString(),selected,selectedName,(Sstv.Mode)mode.getSelectedItem(),(PacketModem.Profile)profile.getSelectedItem(),repeats.getSelectedItemPosition()+1,outputs.get(output.getSelectedItemPosition()));}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){if(request==EXPORT)pendingExport=null;return;}Uri uri=data.getData();
        if(request==PICK){selected=uri;selectedName="file.bin";selectedSize=0;try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int name=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),size=c.getColumnIndex(OpenableColumns.SIZE);if(name>=0)selectedName=c.getString(name);if(size>=0&&!c.isNull(size))selectedSize=c.getLong(size);}}catch(Exception e){notice(e.getMessage());}fileName.setText(selectedName+" · "+selectedSize+" байт");updateEstimate();}
        else if(request==WAV)decodeWave(uri);
        else if(request==EXPORT){TxRequest tx=pendingExport;pendingExport=null;if(tx!=null)transmit(tx,uri);}
        else if(request==SAVE_RECEIVED){File file=receivedToSave;receivedToSave=null;if(file!=null)workers.submit(()->{try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){copy(in,out);append("Файл сохранён");}catch(Exception e){append("Сохранение: "+e.getMessage());}});}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==MIC){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startReceive();else notice("Для приёма звука нужно разрешение на микрофон. Декодирование WAV доступно без него.");}}
    private Raster readImage(byte[] bytes)throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<1||options.outHeight<1)throw new IOException("Не удалось открыть изображение");
        options.inSampleSize=1;while((long)(options.outWidth/options.inSampleSize)*(options.outHeight/options.inSampleSize)>4_000_000)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(b==null)throw new IOException("Неподдерживаемое изображение");int[] pixels=new int[b.getWidth()*b.getHeight()];b.getPixels(pixels,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());Raster r=new Raster(b.getWidth(),b.getHeight(),pixels);b.recycle();return r;
    }
    private byte[] read(Uri uri)throws IOException{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Файл недоступен");return Transfer.readLimited(in);}}
    private void transmit(TxRequest request,Uri destination){
        if(txBusy)return;txBusy=true;cancelTx=false;setTxUi(true);txStatus.setText("Подготовка…");txProgress.setProgress(0);lastTxUi=0;screenOn();
        Audio.Progress progress=new Audio.Progress(){public boolean cancelled(){return cancelTx||destroyed;}public void update(double f,String s){long now=System.currentTimeMillis();if(now-lastTxUi>120||f>=1){lastTxUi=now;ui(()->{txProgress.setProgress((int)(f*1000));txStatus.setText(s);});}}};
        workers.submit(()->{File temporary=null;try{
            Raster image=null;Transfer transfer=null;
            if(request.kind==1)image=readImage(read(request.uri));else if(request.kind==0)transfer=Transfer.text(request.text);else{String mime=getContentResolver().getType(request.uri);transfer=new Transfer(request.kind==2?Transfer.Kind.AUDIO:request.kind==3?Transfer.Kind.VIDEO:Transfer.Kind.FILE,request.name,mime==null?"application/octet-stream":mime,read(request.uri));}
            if(destination!=null){temporary=File.createTempFile("signal-",".wav",getCacheDir());try(Wave.Writer writer=new Wave.Writer(temporary)){if(image!=null)Sstv.encode(image,request.mode,writer,progress);else PacketModem.encode(transfer,request.profile,request.repeats,writer,progress);}Audio.check(progress);try(InputStream in=new FileInputStream(temporary);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){copy(in,out);}append("WAV сохранён");}
            else{
                int min=AudioTrack.getMinBufferSize(Audio.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IOException("48 кГц не поддерживается выходом");
                AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(Audio.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(Math.max(min,Audio.RATE/5)).setTransferMode(AudioTrack.MODE_STREAM).build();
                speaker=track;try{if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("Не удалось открыть динамик");if(request.device!=null)track.setPreferredDevice(request.device);track.play();final long[] played={0};Audio.Sink sink=(s,off,len)->{int at=0;while(at<len){Audio.check(progress);int n=track.write(s,off+at,len-at,AudioTrack.WRITE_BLOCKING);if(n<=0)throw new IOException("AudioTrack: "+n);at+=n;played[0]+=n;}};
                    if(image!=null)Sstv.encode(image,request.mode,sink,progress);else PacketModem.encode(transfer,request.profile,request.repeats,sink,progress);
                    // Wait for the buffered tail, including the last packet and SSTV line.
                    long deadline=System.currentTimeMillis()+5000;while((track.getPlaybackHeadPosition()&0xffffffffL)<played[0]&&System.currentTimeMillis()<deadline){Audio.check(progress);Thread.sleep(20);}
                }finally{try{track.stop();}catch(IllegalStateException ignored){}track.release();speaker=null;}
            }
            ui(()->{txStatus.setText("Готово");txProgress.setProgress(1000);});
        }catch(Exception e){append(cancelTx?"Передача остановлена":"Передача: "+e.getMessage());ui(()->txStatus.setText(cancelTx?"Остановлено":"Ошибка передачи"));}
        finally{if(temporary!=null)temporary.delete();txBusy=false;ui(()->{setTxUi(false);screenOn();});}});
    }
    private void setTxUi(boolean busy){send.setEnabled(!busy);export.setEnabled(!busy);stopTx.setEnabled(busy);output.setEnabled(!busy);}
    private void cancelTransmission(){cancelTx=true;AudioTrack t=speaker;if(t!=null)try{t.pause();t.flush();}catch(IllegalStateException ignored){}}
    private Receiver makeReceiver(){
        PacketModem.Listener packets=new PacketModem.Listener(){public void status(String s){rxUpdate(s);}public void error(String s){append("Приём: "+s);}public void transfer(Transfer t){try{File file=save(t.name,t.data);ui(()->{received.add(file);if(t.kind==Transfer.Kind.TEXT){String current=receivedText.getText().toString();if(current.length()>16000)current=current.substring(current.length()-8000);receivedText.setText(current+"\n\n"+new String(t.data,StandardCharsets.UTF_8));}});append("Получен "+t.kind+": "+t.name);rxUpdate("Файл полностью восстановлен");}catch(IOException e){append("Сохранение: "+e.getMessage());}}};
        Sstv.Listener images=new Sstv.Listener(){public void status(String s){append(s);rxUpdate(s);}public void image(Raster r,Sstv.Mode m,boolean complete,int lines){ui(()->{Bitmap bitmap=Bitmap.createBitmap(r.pixels,r.width,r.height,Bitmap.Config.ARGB_8888);preview.setImageBitmap(bitmap);rxStatus.setText(m.label+": "+lines+"/"+m.height+" строк");});if(complete)try{Bitmap b=Bitmap.createBitmap(r.pixels,r.width,r.height,Bitmap.Config.ARGB_8888);ByteArrayOutputStream bytes=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,bytes);b.recycle();File f=save(m.name().toLowerCase(Locale.ROOT)+".png",bytes.toByteArray());ui(()->received.add(f));append("SSTV-изображение сохранено");}catch(IOException e){append("Сохранение: "+e.getMessage());}}};
        return new Receiver(packets,images);
    }
    private File save(String name,byte[] bytes)throws IOException{File folder=new File(getFilesDir(),"received");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Не удалось создать папку");File f=new File(folder,System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+"-"+Transfer.safeName(name)),tmp=new File(f.getPath()+".part");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);}if(!tmp.renameTo(f)){tmp.delete();throw new IOException("Не удалось сохранить файл");}return f;}
    private void startReceive(){
        if(rxBusy)return;AudioDeviceInfo device=inputs.get(input.getSelectedItemPosition());Receiver receiver=makeReceiver();receiving=true;rxBusy=true;receive.setText("■ Остановить приём");input.setEnabled(false);rxStatus.setText("Открываю микрофон…");screenOn();
        workers.submit(()->{AudioRecord record=null;ArrayList<AudioEffect> effects=new ArrayList<>();try{
            int min=AudioRecord.getMinBufferSize(Audio.RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IOException("48 кГц не поддерживается микрофоном");AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);int source="true".equals(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))?MediaRecorder.AudioSource.UNPROCESSED:MediaRecorder.AudioSource.VOICE_RECOGNITION;
            record=new AudioRecord.Builder().setAudioSource(source).setAudioFormat(new AudioFormat.Builder().setSampleRate(Audio.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(Math.max(min*2,Audio.RATE/2)).build();microphone=record;
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Микрофон недоступен");if(device!=null)record.setPreferredDevice(device);
            int session=record.getAudioSessionId();if(AutomaticGainControl.isAvailable()){AutomaticGainControl e=AutomaticGainControl.create(session);if(e!=null){e.setEnabled(false);effects.add(e);}}if(NoiseSuppressor.isAvailable()){NoiseSuppressor e=NoiseSuppressor.create(session);if(e!=null){e.setEnabled(false);effects.add(e);}}if(AcousticEchoCanceler.isAvailable()){AcousticEchoCanceler e=AcousticEchoCanceler.create(session);if(e!=null){e.setEnabled(false);effects.add(e);}}
            record.startRecording();rxUpdate("Слушаю • SSTV + все FSK");short[] samples=new short[2048];while(receiving&&!destroyed){int n=record.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(n<0)throw new IOException("AudioRecord: "+n);if(n==0)continue;long now=System.currentTimeMillis();if(now-lastLevel>200){lastLevel=now;double sum=0;for(int i=0;i<n;i++)sum+=(double)samples[i]*samples[i];double db=20*Math.log10(Math.max(1,Math.sqrt(sum/n))/32768);ui(()->level.setText(String.format(Locale.ROOT,"Уровень: %.0f dBFS",db)));}receiver.write(samples,0,n);}
        }catch(Exception e){if(receiving)append("Микрофон: "+e.getMessage());}finally{if(record!=null){try{record.stop();}catch(IllegalStateException ignored){}record.release();}for(AudioEffect e:effects)e.release();microphone=null;receiving=false;rxBusy=false;String missing=receiver.incomplete();if(!missing.isEmpty())append(missing);ui(()->{receive.setText("● Включить приём");receive.setEnabled(true);input.setEnabled(true);rxStatus.setText("Приём выключен");level.setText("Уровень: —");screenOn();});}});
    }
    private void stopReceive(){receiving=false;receive.setEnabled(false);AudioRecord m=microphone;if(m!=null)try{m.stop();}catch(IllegalStateException ignored){}}
    private void decodeWave(Uri uri){if(wavBusy)return;wavBusy=true;decode.setEnabled(false);rxProgress.setProgress(0);Receiver receiver=makeReceiver();workers.submit(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("WAV недоступен");Wave.read(in,receiver,new Audio.Progress(){long last;public boolean cancelled(){return destroyed;}public void update(double f,String s){long now=System.currentTimeMillis();if(now-last>120){last=now;ui(()->rxProgress.setProgress((int)(f*1000)));}}});receiver.finish();String missing=receiver.incomplete();append(missing.isEmpty()?"WAV обработан":missing);}catch(Exception e){append("WAV: "+e.getMessage());}finally{wavBusy=false;ui(()->{decode.setEnabled(true);rxProgress.setProgress(1000);});}});}
    private void rxUpdate(String s){long now=System.currentTimeMillis();if(now-lastRxUi>100){lastRxUi=now;ui(()->rxStatus.setText(s));}}
    private void showFiles(){if(received.isEmpty()){notice("Пока нет принятых файлов");return;}String[] names=new String[received.size()];for(int i=0;i<names.length;i++)names[i]=received.get(i).getName();new AlertDialog.Builder(this).setTitle("Принятые файлы").setItems(names,(dialog,pos)->fileActions(received.get(pos))).setNegativeButton("Закрыть",null).show();}
    private void fileActions(File file){new AlertDialog.Builder(this).setTitle(file.getName()).setItems(new String[]{"Открыть","Сохранить копию…","Поделиться…"},(d,action)->{
        Uri uri=new Uri.Builder().scheme("content").authority("org.audioall.android.received").appendPath(file.getName()).build();String mime=getContentResolver().getType(uri);
        try{if(action==1){receivedToSave=file;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,file.getName());startActivityForResult(i,SAVE_RECEIVED);}else if(action==0){Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uri,mime);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);}else{Intent i=new Intent(Intent.ACTION_SEND);i.setType(mime);i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri("AudioALL",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Поделиться файлом"));}}catch(ActivityNotFoundException e){notice("На устройстве нет приложения для открытия этого формата. Сохраните копию файла.");}
    }).setNegativeButton("Закрыть",null).show();}
    private void screenOn(){if(receiving||txBusy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void ui(Runnable task){if(!destroyed)runOnUiThread(()->{if(!destroyed)task.run();});}
    private void notice(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void append(String s){ui(()->{String current=log.getText().toString();if(current.length()>16000)current=current.substring(current.length()-8000);log.setText(current+"\n"+s);});}
    private static void copy(InputStream in,OutputStream out)throws IOException{if(out==null)throw new IOException("Не удалось открыть файл для записи");byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    @Override protected void onDestroy(){destroyed=true;receiving=false;cancelTransmission();AudioRecord m=microphone;if(m!=null)try{m.stop();}catch(IllegalStateException ignored){}workers.shutdownNow();super.onDestroy();}
    private static final class TxRequest {final int kind,repeats;final String text,name;final Uri uri;final Sstv.Mode mode;final PacketModem.Profile profile;final AudioDeviceInfo device;TxRequest(int kind,String text,Uri uri,String name,Sstv.Mode mode,PacketModem.Profile profile,int repeats,AudioDeviceInfo device){this.kind=kind;this.text=text;this.uri=uri;this.name=name;this.mode=mode;this.profile=profile;this.repeats=repeats;this.device=device;}}
}
