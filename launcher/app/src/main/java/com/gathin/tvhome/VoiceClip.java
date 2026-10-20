package com.gathin.tvhome;

import java.util.Arrays;

/** Sound rules for voice input, kept apart so they can be tested without a device. */
final class VoiceClip {

    /** A tenth of a second. */
    private static final int FRAME = VoiceRecorder.RATE / 10;
    /** Pauses keep this many frames at each end: speech fades in and out. */
    private static final int KEEP = 3;
    /** Quieter than about -60 dBFS is no speech at all. The remote's mic hums at about -70 dBFS. */
    private static final float FLOOR = 0.001f;

    private VoiceClip() {
    }

    /**
     * The speech in a recording: pauses are cut to 0.3 s at each end, the silence before the first word and after
     * the last to 0.3 s. A pause is ten times quieter than the loudest tenth of a second. The model then has less to
     * go through, as people tend to hold the button well before and after speaking. Empty when nothing was said.
     */
    static float[] trim(float[] samples) {
        int frames = (samples.length + FRAME - 1) / FRAME;
        float[] level = new float[frames];
        float peak = 0;
        for (int f = 0; f < frames; f++) {
            int from = f * FRAME, to = Math.min(samples.length, from + FRAME);
            double sum = 0;
            for (int i = from; i < to; i++) sum += samples[i] * samples[i];
            level[f] = (float) Math.sqrt(sum / (to - from));
            peak = Math.max(peak, level[f]);
        }
        if (peak < FLOOR) return new float[0];
        float quiet = Math.max(FLOOR, peak / 10);
        boolean[] keep = new boolean[frames];
        for (int f = 0; f < frames; ) {
            if (level[f] >= quiet) {
                keep[f++] = true;
                continue;
            }
            int end = f;   // a pause: frames f to end - 1
            while (end < frames && level[end] < quiet) end++;
            for (int g = f; g < end; g++) keep[g] = (f > 0 && g - f < KEEP) || (end < frames && end - g <= KEEP);
            f = end;
        }
        float[] out = new float[samples.length];
        int n = 0;
        for (int f = 0; f < frames; f++) {
            if (!keep[f]) continue;
            int from = f * FRAME, len = Math.min(samples.length, from + FRAME) - from;
            System.arraycopy(samples, from, out, n, len);
            n += len;
        }
        return Arrays.copyOf(out, n);
    }
}
