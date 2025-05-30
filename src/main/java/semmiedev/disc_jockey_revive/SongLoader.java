package semmiedev.disc_jockey_revive;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import semmiedev.disc_jockey_revive.gui.SongListWidget;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public class SongLoader {
    public static final ArrayList<Song> SONGS = new ArrayList<>();
    public static final ArrayList<SongFolder> FOLDERS = new ArrayList<>();
    public static final ArrayList<String> SONG_SUGGESTIONS = new ArrayList<>();
    public static volatile boolean loadingSongs;
    public static volatile boolean showToast;
    public static SongFolder currentFolder = null;

    public static class SongFolder {
        public final String name;
        public final String path;
        public final ArrayList<Song> songs = new ArrayList<>();
        public final ArrayList<SongFolder> subFolders = new ArrayList<>();
        public SongListWidget.FolderEntry entry;

        public SongFolder(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    public static void loadSongs() {
        if (loadingSongs) return;
        new Thread(() -> {
            loadingSongs = true;
            SONGS.clear();
            FOLDERS.clear();
            SONG_SUGGESTIONS.clear();
            SONG_SUGGESTIONS.add("Songs are loading, please wait");

            // Load root folder
            loadFolder(Main.songsFolder, null);

            for (Song song : SONGS) SONG_SUGGESTIONS.add(song.displayName);
            Main.config.favorites.removeIf(favorite -> SongLoader.SONGS.stream().map(song -> song.fileName).noneMatch(favorite::equals));

            if (showToast && MinecraftClient.getInstance().textRenderer != null) SystemToast.add(MinecraftClient.getInstance().getToastManager(), SystemToast.Type.PACK_LOAD_FAILURE, Main.NAME, Text.translatable(Main.MOD_ID+".loading_done"));
            showToast = true;
            loadingSongs = false;
        }).start();
    }

    private static void loadFolder(File folder, SongFolder parentFolder) {
        if (!folder.isDirectory()) return;

        SongFolder songFolder = new SongFolder(folder.getName(), folder.getPath());
        if (parentFolder == null) {
            FOLDERS.add(songFolder);
        } else {
            parentFolder.subFolders.add(songFolder);
        }

        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                loadFolder(file, songFolder);
            } else {
                try {
                    Song song = loadSong(file);
                    if (song != null) {
                        SONGS.add(song);
                        songFolder.songs.add(song);
                        song.folder = songFolder;
                    }
                } catch (Exception exception) {
                    Main.LOGGER.error("Unable to read or parse song {}", file.getName(), exception);
                }
            }
        }
    }

    public static Song loadSong(File file) throws IOException {
        if (file.isFile()) {
            BinaryReader reader = new BinaryReader(Files.newInputStream(file.toPath()));
            Song song = new Song();

            song.fileName = file.getName().replaceAll("[\\n\\r]", "");
            song.filePath = file.getPath();

            song.length = reader.readShort();

            boolean newFormat = song.length == 0;
            if (newFormat) {
                song.formatVersion = reader.readByte();
                song.vanillaInstrumentCount = reader.readByte();
                song.length = reader.readShort();
            }

            song.height = reader.readShort();
            song.name = reader.readString().replaceAll("[\\n\\r]", "");
            song.author = reader.readString().replaceAll("[\\n\\r]", "");
            song.originalAuthor = reader.readString().replaceAll("[\\n\\r]", "");
            song.description = reader.readString().replaceAll("[\\n\\r]", "");
            song.tempo = reader.readShort();
            song.autoSaving = reader.readByte();
            song.autoSavingDuration = reader.readByte();
            song.timeSignature = reader.readByte();
            song.minutesSpent = reader.readInt();
            song.leftClicks = reader.readInt();
            song.rightClicks = reader.readInt();
            song.blocksAdded = reader.readInt();
            song.blocksRemoved = reader.readInt();
            song.importFileName = reader.readString().replaceAll("[\\n\\r]", "");

            if (newFormat) {
                song.loop = reader.readByte();
                song.maxLoopCount = reader.readByte();
                song.loopStartTick = reader.readShort();
            }

            song.displayName = song.name.replaceAll("\\s", "").isEmpty() ? song.fileName : song.name+" ("+song.fileName+")";
            song.entry = new SongListWidget.SongEntry(song, SONGS.size());
            song.entry.favorite = Main.config.favorites.contains(song.fileName);
            song.searchableFileName = song.fileName.toLowerCase().replaceAll("\\s", "");
            song.searchableName = song.name.toLowerCase().replaceAll("\\s", "");

            short tick = -1;
            short jumps;
            while ((jumps = reader.readShort()) != 0) {
                tick += jumps;
                short layer = -1;
                while ((jumps = reader.readShort()) != 0) {
                    layer += jumps;

                    byte instrumentId = reader.readByte();
                    byte noteId = (byte)(reader.readByte() - 33);

                    if (newFormat) {
                        reader.readByte(); // Velocity
                        reader.readByte(); // Panning
                        reader.readShort(); // Pitch
                    }

                    if (noteId < 0) {
                        noteId = 0;
                    } else if (noteId > 24) {
                        noteId = 24;
                    }

                    Note note = new Note(Note.INSTRUMENTS[instrumentId], noteId);
                    if (!song.uniqueNotes.contains(note)) song.uniqueNotes.add(note);

                    song.notes = Arrays.copyOf(song.notes, song.notes.length + 1);
                    song.notes[song.notes.length - 1] = tick | layer << Note.LAYER_SHIFT | (long)instrumentId << Note.INSTRUMENT_SHIFT | (long)noteId << Note.NOTE_SHIFT;
                }
            }

            return song;
        }
        return null;
    }

    public static void sort() {
        SONGS.sort(Comparator.comparing(song -> song.displayName));
        FOLDERS.sort(Comparator.comparing(folder -> folder.name));
        for (SongFolder folder : FOLDERS) {
            folder.songs.sort(Comparator.comparing(song -> song.displayName));
            folder.subFolders.sort(Comparator.comparing(subFolder -> subFolder.name));
        }
    }
}
