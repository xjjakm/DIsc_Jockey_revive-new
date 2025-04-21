package semmiedev.disc_jockey_revive.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import semmiedev.disc_jockey_revive.Main;
import semmiedev.disc_jockey_revive.Song;
import semmiedev.disc_jockey_revive.SongLoader;
import semmiedev.disc_jockey_revive.SongLoader.SongFolder;
import semmiedev.disc_jockey_revive.gui.screen.DiscJockeyScreen;

public class SongListWidget extends EntryListWidget<SongListWidget.Entry> {
    private static final String FAVORITE_EMOJI = "收藏★";
    private static final String NOT_FAVORITE_EMOJI = "收藏☆";
    private static final String FOLDER_EMOJI = "📁";
    private static final int LEFT_PADDING = 10;
    private final Screen parentScreen;

    public static abstract class Entry extends EntryListWidget.Entry<Entry> {
        public abstract boolean isSelected();
        public abstract void setSelected(boolean selected);
        public abstract boolean isSongEntry();

    }

    @Nullable
    public SongEntry getSelectedSongOrNull() {
        Entry selected = getSelectedOrNull();
        return selected instanceof SongEntry ? (SongEntry) selected : null;
    }

    @Nullable
    public FolderEntry getSelectedFolderOrNull() {
        Entry selected = getSelectedOrNull();
        return selected instanceof FolderEntry ? (FolderEntry) selected : null;
    }

    public static class SongEntry extends Entry {
        private static final Identifier ICONS = Identifier.of(Main.MOD_ID, "textures/gui/icons.png");
        public final int index;
        public final Song song;
        public boolean selected, favorite;
        public SongListWidget songListWidget;

        private final MinecraftClient client = MinecraftClient.getInstance();
        private int x, y, entryWidth, entryHeight;

        public SongEntry(Song song, int index) {
            this.song = song;
            this.index = index;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            this.x = x; this.y = y; this.entryWidth = entryWidth; this.entryHeight = entryHeight;

            if (selected) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0x000000);
            }

            // 收藏图标
            String emoji = String.valueOf(favorite ? FAVORITE_EMOJI : NOT_FAVORITE_EMOJI);
            int emojiWidth = client.textRenderer.getWidth(emoji);
            context.drawTextWithShadow(
                    client.textRenderer,
                    emoji,
                    x + 4, y + 6,
                    favorite ? 0xFFD700 : 0x808080
            );

            // 歌曲名称靠左显示，从收藏图标右侧开始
            int textX = x + emojiWidth + 8;
            int maxWidth = entryWidth - emojiWidth - 12;
            String displayText = client.textRenderer.trimToWidth(song.displayName, maxWidth);
            context.drawTextWithShadow(
                    client.textRenderer,
                    displayText,
                    textX, y + 6,
                    selected ? 0xFFFFFF : 0x808080
            );
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isOverFavoriteButton(mouseX, mouseY)) {
                favorite = !favorite;
                if (favorite) {
                    Main.config.favorites.add(song.fileName);
                } else {
                    Main.config.favorites.remove(song.fileName);
                }
                return true;
            }
            songListWidget.setSelected(this);
            return true;
        }

        private boolean isOverFavoriteButton(double mouseX, double mouseY) {
            int iconX = x + 2;
            int iconWidth = client.textRenderer.getWidth(favorite ? FAVORITE_EMOJI : NOT_FAVORITE_EMOJI);
            int iconHeight = 8;

            return mouseX >= iconX &&
                    mouseX <= iconX + iconWidth &&
                    mouseY >= y + 6 &&
                    mouseY <= y + 6 + iconHeight;
        }

        @Override
        public boolean isSelected() {
            return selected;
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public boolean isSongEntry() {
            return true;
        }
    }

    public static class FolderEntry extends Entry {
        public SongFolder folder;
        public boolean selected;
        public SongListWidget songListWidget;
        public String displayName;

        private final MinecraftClient client = MinecraftClient.getInstance();
        private int x, y, entryWidth, entryHeight;

        public FolderEntry(@Nullable SongFolder folder, SongListWidget songListWidget) {
            this.folder = folder;
            this.songListWidget = songListWidget;
            this.displayName = folder != null ? folder.name : "..";
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            this.x = x; this.y = y; this.entryWidth = entryWidth; this.entryHeight = entryHeight;

            if (selected) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0x000000);
            }

            // 文件夹图标和名称靠左显示
            String displayText = FOLDER_EMOJI + " " + displayName;
            int maxWidth = entryWidth - 8;
            displayText = client.textRenderer.trimToWidth(displayText, maxWidth);
            context.drawTextWithShadow(
                    client.textRenderer,
                    displayText,
                    x + 6, y + 6,
                    selected ? 0xFFFFFF : 0x808080
            );
        }

        private SongFolder findParentFolder(SongFolder current) {
            if (current == null) return null;

            for (SongFolder folder : SongLoader.FOLDERS) {
                if (folder.subFolders.contains(current)) {
                    return folder;
                }
                for (SongFolder subFolder : folder.subFolders) {
                    if (subFolder.subFolders.contains(current)) {
                        return folder;
                    }
                }
            }
            return null;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                if (songListWidget.getParentScreen() instanceof DiscJockeyScreen screen) {
                    if (this.folder == null) {
                        SongFolder parent = screen.findParentFolder(SongLoader.currentFolder);
                        if (parent != null || SongLoader.FOLDERS.contains(SongLoader.currentFolder)) {
                            screen.currentFolder = parent;
                            SongLoader.currentFolder = parent;
                            screen.shouldFilter = true;
                        }
                    } else {
                        screen.currentFolder = this.folder;
                        SongLoader.currentFolder = this.folder;
                        screen.shouldFilter = true;
                    }
                    songListWidget.setSelected(this);
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isSelected() {
            return selected;
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public boolean isSongEntry() {
            return false;
        }
    }

    public SongListWidget(MinecraftClient client, int width, int height, int top, int itemHeight, Screen parentScreen) {
        super(client, width, height, top, itemHeight);
        this.parentScreen = parentScreen;
    }

    public Screen getParentScreen() {
        return parentScreen;
    }

    @Override
    public int getRowLeft() {
        return super.getRowLeft() + LEFT_PADDING;
    }



    @Override
    public int getRowWidth() {
        return width - 40;
    }

    @Override
    protected int getScrollbarX() {
        return width - 12;
    }

    @Override
    public void setSelected(@Nullable Entry entry) {
        Entry selectedEntry = getSelectedOrNull();
        if (selectedEntry != null) selectedEntry.setSelected(false);
        if (entry != null) entry.setSelected(true);
        super.setSelected(entry);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        // Who cares
    }
}
