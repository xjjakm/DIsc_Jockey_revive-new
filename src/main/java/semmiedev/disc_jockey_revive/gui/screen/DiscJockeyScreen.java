package semmiedev.disc_jockey_revive.gui.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import semmiedev.disc_jockey_revive.Main;
import semmiedev.disc_jockey_revive.Note;
import semmiedev.disc_jockey_revive.Song;
import semmiedev.disc_jockey_revive.SongLoader;
import semmiedev.disc_jockey_revive.gui.SongListWidget;
import semmiedev.disc_jockey_revive.gui.hud.BlocksOverlay;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import semmiedev.disc_jockey_revive.SongLoader.SongFolder;
import semmiedev.disc_jockey_revive.SongPlayer.PlayMode;

public class DiscJockeyScreen extends Screen {
    private static final MutableText
            SELECT_SONG = Text.translatable(Main.MOD_ID+".screen.select_song"),
            PLAY = Text.translatable(Main.MOD_ID+".screen.play"),
            PLAY_STOP = Text.translatable(Main.MOD_ID+".screen.play.stop"),
            PREVIEW = Text.translatable(Main.MOD_ID+".screen.preview"),
            PREVIEW_STOP = Text.translatable(Main.MOD_ID+".screen.preview.stop"),
            DROP_HINT = Text.translatable(Main.MOD_ID+".screen.drop_hint").formatted(Formatting.GRAY)
    ;

    private SongListWidget songListWidget;
    private ButtonWidget playButton, previewButton;
    public boolean shouldFilter;
    private String query = "";

    private static final MutableText
            FOLDER_UP = Text.literal("↑"),
            CURRENT_FOLDER = Text.translatable(Main.MOD_ID+".screen.current_folder"),
            PLAY_MODE = Text.translatable(Main.MOD_ID+".screen.play_mode"),
            MODE_SINGLE = Text.translatable(Main.MOD_ID+".screen.mode_single"),
            MODE_LIST = Text.translatable(Main.MOD_ID+".screen.mode_list"),
            MODE_RANDOM = Text.translatable(Main.MOD_ID+".screen.mode_random"),
            MODE_STOP = Text.translatable(Main.MOD_ID+".screen.mode_stop");

    private static final MutableText
            OPEN_FOLDER = Text.translatable(Main.MOD_ID+".screen.open_folder"),
            RELOAD = Text.translatable(Main.MOD_ID+".screen.reload");

    private ButtonWidget folderUpButton, playModeButton;
    public SongFolder currentFolder;
    private PlayMode currentPlayMode = PlayMode.STOP_AFTER;

    public DiscJockeyScreen() {
        super(Main.NAME);
    }

    @Override
    protected void init() {
        shouldFilter = true;
        songListWidget = new SongListWidget(client, width, height - 64 - 32, 32, 20, this);

        // 恢复播放模式
        currentPlayMode = Main.config.playMode;

        // 恢复文件夹状态
        if (!Main.config.currentFolderPath.isEmpty()) {
            currentFolder = findFolderByPath(Main.config.currentFolderPath);
            SongLoader.currentFolder = currentFolder;
        }

        addDrawableChild(songListWidget);
        for (int i = 0; i < SongLoader.SONGS.size(); i++) {
            Song song = SongLoader.SONGS.get(i);
            song.entry.songListWidget = songListWidget;
            if (song.entry.selected) songListWidget.setSelected(song.entry);

            // 添加文件夹条目
            if (song.folder != null && !songListWidget.children().contains(song.folder.entry)) {
                song.folder.entry = new SongListWidget.FolderEntry(song.folder, songListWidget);
                songListWidget.children().add(song.folder.entry);
            }
        }

        folderUpButton = ButtonWidget.builder(FOLDER_UP, button -> {
            if (currentFolder != null) {
                currentFolder = null;
                SongLoader.currentFolder = null;
                shouldFilter = true;
            }
        }).dimensions(10, 10, 20, 20).build();
        addDrawableChild(folderUpButton);

        playModeButton = ButtonWidget.builder(getPlayModeText(), button -> {
            switch (currentPlayMode) {
                case SINGLE_LOOP -> currentPlayMode = PlayMode.LIST_LOOP;
                case LIST_LOOP -> currentPlayMode = PlayMode.RANDOM;
                case RANDOM -> currentPlayMode = PlayMode.STOP_AFTER;
                case STOP_AFTER -> currentPlayMode = PlayMode.SINGLE_LOOP;
            }
            Main.SONG_PLAYER.setPlayMode(currentPlayMode);
            playModeButton.setMessage(getPlayModeText());
        }).dimensions(width - 120, 10, 100, 20).build();
        addDrawableChild(playModeButton);

        playButton = ButtonWidget.builder(PLAY, button -> {
            if (Main.SONG_PLAYER.running) {
                Main.SONG_PLAYER.stop();
            } else {
                SongListWidget.SongEntry entry = songListWidget.getSelectedSongOrNull();
                if (entry != null) {
                    Main.SONG_PLAYER.start(entry.song);
                    client.setScreen(null);
                }
            }
        }).dimensions(width / 2 - 160, height - 61, 100, 20).build();
        addDrawableChild(playButton);

        previewButton = ButtonWidget.builder(PREVIEW, button -> {
            if (Main.PREVIEWER.running) {
                Main.PREVIEWER.stop();
            } else {
                SongListWidget.SongEntry entry = songListWidget.getSelectedSongOrNull();
                if (entry != null) Main.PREVIEWER.start(entry.song);
            }
        }).dimensions(width / 2 - 50, height - 61, 100, 20).build();
        addDrawableChild(previewButton);

        addDrawableChild(ButtonWidget.builder(Text.translatable(Main.MOD_ID+".screen.blocks"), button -> {
            // TODO: 6/2/2022 Add an auto build mode
            if (BlocksOverlay.itemStacks == null) {
                SongListWidget.SongEntry entry = songListWidget.getSelectedSongOrNull();
                if (entry != null) {
                    client.setScreen(null);

                    BlocksOverlay.itemStacks = new ItemStack[0];
                    BlocksOverlay.amounts = new int[0];
                    BlocksOverlay.amountOfNoteBlocks = entry.song.uniqueNotes.size();

                    for (Note note : entry.song.uniqueNotes) {
                        ItemStack itemStack = Note.INSTRUMENT_BLOCKS.get(note.instrument()).asItem().getDefaultStack();
                        int index = -1;

                        for (int i = 0; i < BlocksOverlay.itemStacks.length; i++) {
                            if (BlocksOverlay.itemStacks[i].getItem() == itemStack.getItem()) {
                                index = i;
                                break;
                            }
                        }

                        if (index == -1) {
                            BlocksOverlay.itemStacks = Arrays.copyOf(BlocksOverlay.itemStacks, BlocksOverlay.itemStacks.length + 1);
                            BlocksOverlay.amounts = Arrays.copyOf(BlocksOverlay.amounts, BlocksOverlay.amounts.length + 1);

                            BlocksOverlay.itemStacks[BlocksOverlay.itemStacks.length - 1] = itemStack;
                            BlocksOverlay.amounts[BlocksOverlay.amounts.length - 1] = 1;
                        } else {
                            BlocksOverlay.amounts[index] = BlocksOverlay.amounts[index] + 1;
                        }
                    }
                }
            } else {
                BlocksOverlay.itemStacks = null;
                client.setScreen(null);
            }
        }).dimensions(width / 2 + 60, height - 61, 100, 20).build());

        // 打开文件夹
        addDrawableChild(ButtonWidget.builder(OPEN_FOLDER, button -> {
            try {
                String folderPath = currentFolder != null ?
                        currentFolder.path :
                        Main.songsFolder.getAbsolutePath(); // 使用绝对路径

                File target = new File(folderPath);
                if (!target.exists()) {
                    client.inGameHud.getChatHud().addMessage(
                            Text.translatable(Main.MOD_ID+".screen.folder_not_exist", folderPath)
                                    .formatted(Formatting.RED));
                    return;
                }

                // Windows 专用命令，其他的不会
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    new ProcessBuilder("explorer.exe", "/select,", target.getAbsolutePath()).start();
                } else {
                    java.awt.Desktop.getDesktop().open(target);
                }
            } catch (Exception e) {
                Main.LOGGER.error("打开文件夹失败", e);
                client.inGameHud.getChatHud().addMessage(
                        Text.translatable(Main.MOD_ID+".screen.open_folder_failed")
                                .formatted(Formatting.RED));
            }
        }).dimensions(width / 2 - 160, height - 31, 100, 20).build());


        // 重新加载
        addDrawableChild(ButtonWidget.builder(RELOAD, button -> {
            SongLoader.loadSongs();
            client.setScreen(null);
        }).dimensions(width / 2 + 60, height - 31, 100, 20).build());

        TextFieldWidget searchBar = new TextFieldWidget(textRenderer, width / 2 - 50, height - 31, 100, 20, Text.translatable(Main.MOD_ID+".screen.search"));
        searchBar.setChangedListener(query -> {
            query = query.toLowerCase().replaceAll("\\s", "");
            if (this.query.equals(query)) return;
            this.query = query;
            shouldFilter = true;
        });
        addDrawableChild(searchBar);

        // TODO: 6/2/2022 Add a reload button
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, DROP_HINT, width / 2, 5, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, SELECT_SONG, width / 2, 20, 0xFFFFFF);

        // 显示当前文件夹和播放模式
        String folderName = currentFolder == null ? "/" : currentFolder.name;
        context.drawTextWithShadow(textRenderer, CURRENT_FOLDER.getString() + ": " + folderName, 35, 15, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, PLAY_MODE.getString() + ":", width - 220, 15, 0xFFFFFF);
    }

    @Override
    public void tick() {
        previewButton.setMessage(Main.PREVIEWER.running ? PREVIEW_STOP : PREVIEW);
        playButton.setMessage(Main.SONG_PLAYER.running ? PLAY_STOP : PLAY);

        if (shouldFilter) {
            shouldFilter = false;
            songListWidget.children().clear();
            boolean empty = query.isEmpty();

            boolean isInSongsOrSubfolder = currentFolder == null ||
                    currentFolder.path.startsWith(Main.songsFolder.getPath());

            if (currentFolder == null) {
                for (SongFolder folder : SongLoader.FOLDERS) {
                    if (empty || folder.name.toLowerCase().contains(query)) {
                        if (folder.entry == null) {
                            folder.entry = new SongListWidget.FolderEntry(folder, songListWidget);
                        }
                        songListWidget.children().add(folder.entry);
                    }
                }
            } else {
                // 返回上级
                SongListWidget.FolderEntry parentEntry = new SongListWidget.FolderEntry(null, songListWidget);
                parentEntry.displayName = "..";
                songListWidget.children().add(parentEntry);

                // 子文件夹
                for (SongFolder subFolder : currentFolder.subFolders) {
                    if (empty || subFolder.name.toLowerCase().contains(query)) {
                        if (subFolder.entry == null) {
                            subFolder.entry = new SongListWidget.FolderEntry(subFolder, songListWidget);
                        }
                        songListWidget.children().add(subFolder.entry);
                    }
                }
            }

            // 只有在songs目录或其子目录中才显示歌曲(原作者的💩跑我这了)
            if (isInSongsOrSubfolder) {
                // 歌曲条目
                List<Song> songsToShow = currentFolder == null ?
                        SongLoader.SONGS.stream()
                                .filter(song -> song.folder == null)
                                .collect(Collectors.toList()) :
                        currentFolder.songs.stream()
                                .filter(song -> song.folder == currentFolder)
                                .collect(Collectors.toList());

                // 已收藏歌曲
                for (Song song : songsToShow) {
                    if (song.entry.favorite && (empty || song.searchableFileName.contains(query) || song.searchableName.contains(query))) {
                        songListWidget.children().add(song.entry);
                    }
                }

                // 未收藏歌曲
                for (Song song : songsToShow) {
                    if (!song.entry.favorite && (empty || song.searchableFileName.contains(query) || song.searchableName.contains(query))) {
                        songListWidget.children().add(song.entry);
                    }
                }
            }
        }
    }



    public SongFolder findParentFolder(SongFolder current) {
        if (current == null) return null;

        if (SongLoader.FOLDERS.contains(current)) {
            return null;
        }

        for (SongFolder folder : SongLoader.FOLDERS) {
            if (folder.subFolders.contains(current)) {
                return folder;
            }
            SongFolder found = findParentInSubfolders(folder, current);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private SongFolder findParentInSubfolders(SongFolder parent, SongFolder target) {
        for (SongFolder subFolder : parent.subFolders) {
            if (subFolder == target) {
                return parent;
            }
            SongFolder found = findParentInSubfolders(subFolder, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        String string = paths.stream().map(Path::getFileName).map(Path::toString).collect(Collectors.joining(", "));
        if (string.length() > 300) string = string.substring(0, 300)+"...";

        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                paths.forEach(path -> {
                    try {
                        File file = path.toFile();

                        if (SongLoader.SONGS.stream().anyMatch(input -> input.fileName.equalsIgnoreCase(file.getName()))) return;

                        Song song = SongLoader.loadSong(file);
                        if (song != null) {
                            Files.copy(path, Main.songsFolder.toPath().resolve(file.getName()));
                            SongLoader.SONGS.add(song);
                        }
                    } catch (IOException exception) {
                        Main.LOGGER.warn("Failed to copy song file from {} to {}", path, Main.songsFolder.toPath(), exception);
                    }
                });

                SongLoader.sort();
            }
            client.setScreen(this);
        }, Text.translatable(Main.MOD_ID+".screen.drop_confirm"), Text.literal(string)));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        super.close();
        // 保存当前文件夹
        Main.config.currentFolderPath = currentFolder != null ? currentFolder.path : "";
        // 保存播放模式
        Main.config.playMode = currentPlayMode;
        // 异步保存配置
        new Thread(() -> Main.configHolder.save()).start();
    }

    private SongFolder findInSubFolders(SongFolder parent, SongFolder target) {
        for (SongFolder subFolder : parent.subFolders) {
            if (subFolder == target) {
                return parent;
            }
            SongFolder found = findInSubFolders(subFolder, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private SongFolder findFolderByPath(String path) {
        for (SongFolder folder : SongLoader.FOLDERS) {
            if (folder.path.equals(path)) {
                return folder;
            }
        }

        for (SongFolder folder : SongLoader.FOLDERS) {
            SongFolder found = findFolderByPathInSubfolders(folder, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private SongFolder findFolderByPathInSubfolders(SongFolder parent, String targetPath) {
        for (SongFolder subFolder : parent.subFolders) {
            if (subFolder.path.equals(targetPath)) {
                return subFolder;
            }
            SongFolder found = findFolderByPathInSubfolders(subFolder, targetPath);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Text getPlayModeText() {
        return switch (currentPlayMode) {
            case SINGLE_LOOP -> MODE_SINGLE;
            case LIST_LOOP -> MODE_LIST;
            case RANDOM -> MODE_RANDOM;
            case STOP_AFTER -> MODE_STOP;
        };
    }
}
