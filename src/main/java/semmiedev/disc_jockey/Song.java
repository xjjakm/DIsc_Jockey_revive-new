package semmiedev.disc_jockey;

import semmiedev.disc_jockey.gui.SongListWidget;

import java.util.ArrayList;

public class Song {
    public final ArrayList<Note> uniqueNotes = new ArrayList<>();

    public long[] notes = new long[0];

    public short length, height, tempo, loopStartTick;
    public String fileName, filePath, name, author, originalAuthor, description, displayName;
    public byte autoSaving, autoSavingDuration, timeSignature, vanillaInstrumentCount, formatVersion, loop, maxLoopCount;
    public int minutesSpent, leftClicks, rightClicks, blocksAdded, blocksRemoved;
    public String importFileName;
    public SongLoader.SongFolder folder;

    public SongListWidget.SongEntry entry;
    public String searchableFileName, searchableName;

    @Override
    public String toString() {
        return displayName;
    }

    public double millisecondsToTicks(long milliseconds) {
        double songSpeed = (tempo / 100.0) / 20.0;
        double oneMsTo20TickFraction = 1.0 / 50.0;
        return milliseconds * oneMsTo20TickFraction * songSpeed;
    }

    public double ticksToMilliseconds(double ticks) {
        double songSpeed = (tempo / 100.0) / 20.0;
        double oneMsTo20TickFraction = 1.0 / 50.0;
        return ticks / oneMsTo20TickFraction / songSpeed;
    }

    public double getLengthInSeconds() {
        return ticksToMilliseconds(length) / 1000.0;
    }
}
