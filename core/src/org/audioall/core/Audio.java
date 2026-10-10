package org.audioall.core;

import java.io.IOException;

/** All DSP uses mono signed PCM16 at 48 kHz, independent of platform. */
public final class Audio {
    public static final int RATE = 48000;
    private Audio() {}
    public interface Sink { void write(short[] samples, int offset, int length) throws IOException; }
    public interface Progress {
        void update(double fraction, String status);
        boolean cancelled();
    }
    public static final Progress NONE = new Progress() {
        public void update(double f, String s) {}
        public boolean cancelled() { return Thread.currentThread().isInterrupted(); }
    };
    public static void check(Progress p) throws IOException {
        if (p.cancelled() || Thread.currentThread().isInterrupted()) throw new IOException("Операция отменена");
    }
    public static final class ToneWriter {
        private final Sink sink;
        private final Progress progress;
        private final short[] buffer = new short[4096];
        private int used;
        private long emitted;
        private double target, phase;
        public ToneWriter(Sink sink, Progress progress) { this.sink=sink; this.progress=progress; }
        public void tone(double hz, double ms) throws IOException {
            target += ms * RATE / 1000.0;
            int n = (int)(Math.round(target) - emitted);
            append(hz, n);
        }
        public void samples(double hz, int count) throws IOException {
            target += count; append(hz, count);
        }
        private void append(double hz, int count) throws IOException {
            double step = 2*Math.PI*hz/RATE;
            for (int i=0; i<count; i++) {
                buffer[used++] = hz == 0 ? 0 : (short)(Math.sin(phase)*20000);
                phase += step;
                if (phase >= 2*Math.PI) phase -= 2*Math.PI;
                emitted++;
                if (used == buffer.length) flush();
            }
        }
        public void flush() throws IOException {
            Audio.check(progress);
            if (used>0) { sink.write(buffer,0,used); used=0; }
        }
        public long sampleCount() { return emitted; }
    }
}
