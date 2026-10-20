package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VoiceTextTest {

    @Test
    public void emptyFieldGetsTheWordsAlone() {
        assertEquals("hello there", VoiceText.merge("", "  hello there "));
    }

    @Test
    public void wordsGoAfterASpace() {
        assertEquals("cats and dogs", VoiceText.merge("cats", "and dogs"));
        assertEquals("cats and dogs", VoiceText.merge("cats ", "and dogs"));
    }

    @Test
    public void aLoneSentenceLosesItsFullStop() {
        assertEquals("Nature scenes", VoiceText.tidy(" Nature scenes. "));
        assertEquals("Hi. How are you.", VoiceText.tidy("Hi. How are you."));
        assertEquals("What's new?", VoiceText.tidy("What's new?"));
        assertEquals("", VoiceText.tidy(""));
    }

    @Test
    public void nothingSaidLeavesTheField() {
        assertEquals("cats", VoiceText.merge("cats", "   "));
    }
}
