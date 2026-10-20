package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Records the remote's mic while its button is held. The remote streams only between its AUDIO_START (press) and
 * AUDIO_STOP (release); Xiaomi's Bluetooth app (com.mi.bluetooth) hands the audio to the input "MIBT.VoiceControl",
 * a TYPE_WIRED_HEADSET device. Once the remote stops, read() blocks, so {@link #stop} stops the AudioRecord itself,
 * off the main thread, as that waits on the audio server. Each recording has its own {@link Session}, so one that
 * is still winding down never touches the next.
 */
final class VoiceRecorder {

    static final int RATE = 16000;
    private static final String TAG = "TvHomeVoice";
    private static final int MAX_SECONDS = 30;

    interface Listener {
        /** The mic level, 0 to 1, about ten times a second. On the recording thread. */
        void onLevel(float level);

        /** The recording, after {@link #stop} or the time cap; empty when the mic couldn't be opened. */
        void onDone(float[] samples);
    }

    private static final class Session {
        volatile boolean recording = true;
        volatile AudioRecord record;
    }

    private final Context context;
    /** The latest recording; main thread. */
    private Session current;

    VoiceRecorder(Context context) {
        this.context = context;
    }

    boolean isRecording() {
        return current != null && current.recording;
    }

    void start(Listener listener) {
        if (isRecording()) return;
        Session s = new Session();
        current = s;
        new Thread(() -> record(s, listener), "voice-recorder").start();
    }

    void stop() {
        Session s = current;
        if (s == null || !s.recording) return;
        s.recording = false;
        AudioRecord a = s.record;
        if (a != null) new Thread(() -> stopQuietly(a), "voice-stop").start();   // makes a blocked read() return
    }

    private static void stopQuietly(AudioRecord a) {
        try {
            a.stop();
        } catch (IllegalStateException e) {
            // not started yet, already stopped, or released
        }
    }

    private void record(Session s, Listener listener) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        AudioRecord ar = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            for (AudioDeviceInfo d : context.getSystemService(AudioManager.class)
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) ar.setPreferredDevice(d);
            }
            s.record = ar;
            if (s.recording) ar.startRecording();   // let go before this: stop() found nothing to stop
            byte[] buf = new byte[RATE / 5];   // 0.1 s
            while (s.recording && pcm.size() < RATE * 2 * MAX_SECONDS) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) break;
                pcm.write(buf, 0, n);
                listener.onLevel(level(buf, n));
            }
        } catch (RuntimeException e) {   // no permission, or the mic is gone
            Log.w(TAG, "can't record", e);
        } finally {
            s.recording = false;
            if (ar != null) {
                s.record = null;
                stopQuietly(ar);
                ar.release();   // a late stopper's stop() then throws IllegalStateException, which it ignores
            }
        }
        listener.onDone(samples(pcm.toByteArray()));
    }

    /** From -55 to -30 dBFS: speech through the remote comes in at about -42 to -37 dBFS. */
    private static float level(byte[] buf, int n) {
        double sum = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            sum += (double) s * s;
        }
        double db = 20 * Math.log10(Math.max(1, Math.sqrt(sum / Math.max(1, n / 2))) / 32768);
        return (float) Math.max(0, Math.min(1, (db + 55) / 25));
    }

    private static float[] samples(byte[] pcm) {
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)) / 32768f;
        }
        return out;
    }
}
