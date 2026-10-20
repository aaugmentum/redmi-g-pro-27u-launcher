package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VoiceClipTest {

    private static final int RATE = VoiceRecorder.RATE, TENTH = RATE / 10;

    /** Seconds of a 300 Hz tone at the given level, then of near-silence; repeated. */
    private static float[] clip(float... secondsAndLevels) {
        int n = 0;
        for (int i = 0; i < secondsAndLevels.length; i += 2) n += Math.round(secondsAndLevels[i] * RATE);
        float[] out = new float[n];
        int at = 0;
        for (int i = 0; i < secondsAndLevels.length; i += 2) {
            int len = Math.round(secondsAndLevels[i] * RATE);
            for (int j = 0; j < len; j++) {
                out[at + j] = (float) (secondsAndLevels[i + 1] * Math.sqrt(2) * Math.sin(2 * Math.PI * 300 * j / RATE));
            }
            at += len;
        }
        return out;
    }

    @Test
    public void silenceIsNothing() {
        assertEquals(0, VoiceClip.trim(clip(3, 0.0002f)).length);
    }

    @Test
    public void longSilenceAroundSpeechGoes() {
        // 2 s of hum, 1 s of speech, 2 s of hum: 0.3 s is kept before and after the speech.
        assertEquals(RATE + 6 * TENTH, VoiceClip.trim(clip(2, 0.0002f, 1, 0.01f, 2, 0.0002f)).length);
    }

    @Test
    public void longPauseIsCutToItsEnds() {
        // A 3 s pause between words keeps 0.3 s at each end.
        assertEquals(2 * RATE + 6 * TENTH, VoiceClip.trim(clip(1, 0.01f, 3, 0.0002f, 1, 0.01f)).length);
    }

    @Test
    public void shortPausesStay() {
        float[] speech = clip(1, 0.01f, 0.5f, 0.0002f, 1, 0.01f);
        assertEquals(speech.length, VoiceClip.trim(speech).length);
    }

    @Test
    public void quietSpeechIsKept() {
        // The remote's own level: speech at about -40 dBFS, the hum at -70.
        assertEquals(RATE + 6 * TENTH, VoiceClip.trim(clip(2, 0.0003f, 1, 0.01f, 2, 0.0003f)).length);
    }
}
