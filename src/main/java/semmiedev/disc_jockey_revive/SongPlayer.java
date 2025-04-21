package semmiedev.disc_jockey_revive;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.GameMode;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SongPlayer implements ClientTickEvents.StartWorldTick {
    private static boolean warned;
    public boolean running;
    public Song song;

    private int index;
    private double tick; // Aka song position
    private HashMap<NoteBlockInstrument, HashMap<Byte, BlockPos>> noteBlocks = null;
    public boolean tuned;
    private long lastPlaybackTickAt = -1L;

    // Used to check and enforce packet rate limits to not get kicked
    private long last100MsSpanAt = -1L;
    private int last100MsSpanEstimatedPackets = 0;
    // At how many packets/100ms should the player just reduce / stop sending packets for a while
    final private int last100MsReducePacketsAfter = 300 / 10, last100MsStopPacketsAfter = 450 / 10;
    // If higher than current millis, don't send any packets of this kind (temp disable)
    private long reducePacketsUntil = -1L, stopPacketsUntil = -1L;

    // Use to limit swings and look to only each tick. More will not be visually visible anyway due to interpolation
    private long lastLookSentAt = -1L, lastSwingSentAt = -1L;

    // The thread executing the tickPlayback method
    private Thread playbackThread = null;
    public long playbackLoopDelay = 5;
    // Just for external debugging purposes
    public HashMap<Block, Integer> missingInstrumentBlocks = new HashMap<>();
    public float speed = 1.0f; // Toy

    private long lastInteractAt = -1;
    private float availableInteracts = 8;
    private int tuneInitialUntunedBlocks = -1;
    private HashMap<BlockPos, Pair<Integer, Long>> notePredictions = new HashMap<>();
    public boolean didSongReachEnd = false;
    public boolean loopSong = false;
    private long pausePlaybackUntil = -1L; // Set after tuning, if configured

    public SongPlayer() {
        Main.TICK_LISTENERS.add(this);
    }

    public @NotNull HashMap<NoteBlockInstrument, @Nullable NoteBlockInstrument> instrumentMap = new HashMap<>(); // Toy
    public synchronized void startPlaybackThread() {
        if(Main.config.disableAsyncPlayback) {
            playbackThread = null;
            return;
        }

        this.playbackThread = new Thread(() -> {
            Thread ownThread = this.playbackThread;
            while(ownThread == this.playbackThread) {
                try {
                    // Accuracy doesn't really matter at this precision imo
                    Thread.sleep(playbackLoopDelay);
                }catch (Exception ex) {
                    ex.printStackTrace();
                }
                tickPlayback();
            }
        });
        this.playbackThread.start();
    }

    public enum PlayMode {
        SINGLE_LOOP, // 单曲循环
        LIST_LOOP,   // 列表循环
        RANDOM,      // 随机播放
        STOP_AFTER   // 播完就停
    }

    private PlayMode playMode = PlayMode.STOP_AFTER;
    private boolean isRandomPlaying = false;
    private int randomIndex = -1;

    public synchronized void stopPlaybackThread() {
        this.playbackThread = null; // Should stop on its own then
    }

    public synchronized void start(Song song) {
        if (!Main.config.hideWarning && !warned) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.translatable("disc_jockey.warning").formatted(Formatting.BOLD, Formatting.RED));
            warned = true;
            return;
        }
        if (running) stop();
        this.song = song;
        this.loopSong = playMode == PlayMode.SINGLE_LOOP;
        if(this.playbackThread == null) startPlaybackThread();
        running = true;
        lastPlaybackTickAt = System.currentTimeMillis();
        last100MsSpanAt = System.currentTimeMillis();
        last100MsSpanEstimatedPackets = 0;
        reducePacketsUntil = -1L;
        stopPacketsUntil = -1L;
        lastLookSentAt = -1L;
        lastSwingSentAt = -1L;
        missingInstrumentBlocks.clear();
        didSongReachEnd = false;
        isRandomPlaying = playMode == PlayMode.RANDOM;
        if (isRandomPlaying) {
            randomIndex = getRandomSongIndex();
        }
    }

    private int getRandomSongIndex() {
        if (SongLoader.currentFolder == null) {
            return (int) (Math.random() * SongLoader.SONGS.size());
        } else {
            return (int) (Math.random() * SongLoader.currentFolder.songs.size());
        }
    }

    public synchronized void stop() {
        //MinecraftClient.getInstance().send(() -> Main.TICK_LISTENERS.remove(this));
        stopPlaybackThread();
        running = false;
        index = 0;
        tick = 0;
        noteBlocks = null;
        notePredictions.clear();
        tuned = false;
        tuneInitialUntunedBlocks = -1;
        lastPlaybackTickAt = -1L;
        last100MsSpanAt = -1L;
        last100MsSpanEstimatedPackets = 0;
        reducePacketsUntil = -1L;
        stopPacketsUntil = -1L;
        lastLookSentAt = -1L;
        lastSwingSentAt = -1L;
        didSongReachEnd = false; // Change after running stop() if actually ended cleanly
    }

    public synchronized void tickPlayback() {
        if (!running) {
            lastPlaybackTickAt = -1L;
            last100MsSpanAt = -1L;
            return;
        }
        long previousPlaybackTickAt = lastPlaybackTickAt;
        lastPlaybackTickAt = System.currentTimeMillis();
        if(last100MsSpanAt != -1L && System.currentTimeMillis() - last100MsSpanAt >= 100) {
            last100MsSpanEstimatedPackets = 0;
            last100MsSpanAt = System.currentTimeMillis();
        }else if (last100MsSpanAt == -1L) {
            last100MsSpanAt = System.currentTimeMillis();
            last100MsSpanEstimatedPackets = 0;
        }
        if(noteBlocks != null && tuned) {
            if(pausePlaybackUntil != -1L && System.currentTimeMillis() <= pausePlaybackUntil) return;
            while (running) {
                MinecraftClient client = MinecraftClient.getInstance();
                GameMode gameMode = client.interactionManager == null ? null : client.interactionManager.getCurrentGameMode();
                // In the best case, gameMode would only be queried in sync Ticks, no here
                if (gameMode == null || !gameMode.isSurvivalLike()) {
                    client.inGameHud.getChatHud().addMessage(Text.translatable(Main.MOD_ID+".player.invalid_game_mode", gameMode == null ? "unknown" : gameMode.getTranslatableName()).formatted(Formatting.RED));
                    stop();
                    return;
                }

                long note = song.notes[index];
                final long now = System.currentTimeMillis();
                if ((short)note <= Math.round(tick)) {
                    @Nullable BlockPos blockPos = noteBlocks.get(Note.INSTRUMENTS[(byte)(note >> Note.INSTRUMENT_SHIFT)]).get((byte)(note >> Note.NOTE_SHIFT));
                    if(blockPos == null) {
                        // Instrument got likely mapped to "nothing". Skip it
                        index++;
                        continue;
                    }
                    if (!canInteractWith(client.player, blockPos)) {
                        stop();
                        client.inGameHud.getChatHud().addMessage(Text.translatable(Main.MOD_ID+".player.to_far").formatted(Formatting.RED));
                        return;
                    }
                    Vec3d unit = Vec3d.ofCenter(blockPos, 0.5).subtract(client.player.getEyePos()).normalize();
                    if((lastLookSentAt == -1L || now - lastLookSentAt >= 50) && last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(MathHelper.wrapDegrees((float) (MathHelper.atan2(unit.z, unit.x) * 57.2957763671875) - 90.0f), MathHelper.wrapDegrees((float) (-(MathHelper.atan2(unit.y, Math.sqrt(unit.x * unit.x + unit.z * unit.z)) * 57.2957763671875))), true, false));
                        last100MsSpanEstimatedPackets++;
                        lastLookSentAt = now;
                    }else if(last100MsSpanEstimatedPackets >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }
                    if(last100MsSpanEstimatedPackets < last100MsStopPacketsAfter && (stopPacketsUntil == -1L || stopPacketsUntil < now)) {
                        // TODO: 5/30/2022 Check if the block needs tuning
                        //client.interactionManager.attackBlock(blockPos, Direction.UP);
                        client.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                        last100MsSpanEstimatedPackets++;
                    }else if(last100MsSpanEstimatedPackets >= last100MsStopPacketsAfter) {
                        Main.LOGGER.info("Stopping all packets for a bit!");
                        stopPacketsUntil = Math.max(stopPacketsUntil, now + 250);
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 10000);
                    }
                    if(last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                        last100MsSpanEstimatedPackets++;
                    }else if(last100MsSpanEstimatedPackets >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }
                    if((lastSwingSentAt == -1L || now - lastSwingSentAt >= 50) &&last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.executeSync(() -> client.player.swingHand(Hand.MAIN_HAND));
                        lastSwingSentAt = now;
                        last100MsSpanEstimatedPackets++;
                    }else if(last100MsSpanEstimatedPackets  >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }

                    index++;
                    if (index >= song.notes.length) {
                        stop();
                        didSongReachEnd = true;
                        if (playMode == PlayMode.SINGLE_LOOP) {
                            start(song);
                        } else if (playMode == PlayMode.LIST_LOOP || playMode == PlayMode.RANDOM) {
                            playNextSong();
                        }
                        break;
                    }
                } else {
                    break;
                }
            }

            if(running) { // Might not be running anymore (prevent small offset on song, even if that is not played anymore)
                long elapsedMs = previousPlaybackTickAt != -1L && lastPlaybackTickAt != -1L ? lastPlaybackTickAt - previousPlaybackTickAt : (16); // Assume 16ms if unknown
                tick += song.millisecondsToTicks(elapsedMs) * speed;
            }
        }
    }

    private void playNextSong() {
        if (SongLoader.currentFolder == null || SongLoader.currentFolder.songs.isEmpty()) return;

        int currentIndex = SongLoader.currentFolder.songs.indexOf(song);
        if (currentIndex == -1) return;

        if (playMode == PlayMode.RANDOM) {
            int newIndex;
            do {
                newIndex = (int) (Math.random() * SongLoader.currentFolder.songs.size());
            } while (newIndex == currentIndex && SongLoader.currentFolder.songs.size() > 1);
            start(SongLoader.currentFolder.songs.get(newIndex));
        } else if (playMode == PlayMode.LIST_LOOP) {
            int nextIndex = (currentIndex + 1) % SongLoader.currentFolder.songs.size();
            start(SongLoader.currentFolder.songs.get(nextIndex));
        }
    }

    public synchronized void setPlayMode(PlayMode mode) {
        this.playMode = mode;
        this.loopSong = mode == PlayMode.SINGLE_LOOP;
    }

    // this is the original author‘s comment, i dont wanna delete it
    // TODO: 6/2/2022 Play note blocks every song tick, instead of every tick. That way the song will sound better
    //      11/1/2023 Playback now done in separate thread. Not ideal but better especially when FPS are low.
    @Override
    public void onStartTick(ClientWorld world) {
        MinecraftClient client = MinecraftClient.getInstance();
        if(world == null || client.world == null || client.player == null) return;
        if(song == null || !running) return;

        // Clear outdated note predictions
        ArrayList<BlockPos> outdatedPredictions = new ArrayList<>();
        for(Map.Entry<BlockPos, Pair<Integer, Long>> entry : notePredictions.entrySet()) {
            if(entry.getValue().getRight() < System.currentTimeMillis())
                outdatedPredictions.add(entry.getKey());
        }
        for(BlockPos outdatedPrediction : outdatedPredictions) notePredictions.remove(outdatedPrediction);

        if (noteBlocks == null) {
            noteBlocks = new HashMap<>();

            ClientPlayerEntity player = client.player;

            // Create list of available noteblock positions per used instrument
            HashMap<NoteBlockInstrument, ArrayList<BlockPos>> noteblocksForInstrument = new HashMap<>();
            for(NoteBlockInstrument instrument : NoteBlockInstrument.values())
                noteblocksForInstrument.put(instrument, new ArrayList<>());
            final Vec3d playerEyePos = player.getEyePos();

            final int maxOffset; // Rough estimates, of which blocks could be in reach
            if(Main.config.expectedServerVersion == ModConfig.ExpectedServerVersion.v1_20_4_Or_Earlier) {
                maxOffset = 7;
            }else if(Main.config.expectedServerVersion == ModConfig.ExpectedServerVersion.v1_20_5_Or_Later) {
                maxOffset = (int) Math.ceil(player.getBlockInteractionRange() + 1.0 + 1.0);
            }else if(Main.config.expectedServerVersion == ModConfig.ExpectedServerVersion.All) {
                maxOffset = Math.min(7, (int) Math.ceil(player.getBlockInteractionRange() + 1.0 + 1.0));
            }else {
                throw new NotImplementedException("ExpectedServerVersion Value not implemented: " + Main.config.expectedServerVersion.name());
            }
            final ArrayList<Integer> orderedOffsets = new ArrayList<>();
            for(int offset = 0; offset <= maxOffset; offset++) {
                orderedOffsets.add(offset);
                if(offset != 0) orderedOffsets.add(offset * -1);
            }

            for(NoteBlockInstrument instrument : noteblocksForInstrument.keySet().toArray(new NoteBlockInstrument[0])) {
                for (int y : orderedOffsets) {
                    for (int x : orderedOffsets) {
                        for (int z : orderedOffsets) {
                            Vec3d vec3d = playerEyePos.add(x, y, z);
                            BlockPos blockPos = new BlockPos(MathHelper.floor(vec3d.x), MathHelper.floor(vec3d.y), MathHelper.floor(vec3d.z));
                            if (!canInteractWith(player, blockPos))
                                continue;
                            BlockState blockState = world.getBlockState(blockPos);
                            if (!blockState.isOf(Blocks.NOTE_BLOCK) || !world.isAir(blockPos.up()))
                                continue;

                            if (blockState.get(Properties.INSTRUMENT) == instrument)
                                noteblocksForInstrument.get(instrument).add(blockPos);
                        }
                    }
                }
            }

            // Remap instruments for funzies
            if(!instrumentMap.isEmpty()) {
                HashMap<NoteBlockInstrument, ArrayList<BlockPos>> newNoteblocksForInstrument = new HashMap<>();
                for(NoteBlockInstrument orig : noteblocksForInstrument.keySet()) {
                    NoteBlockInstrument mappedInstrument = instrumentMap.getOrDefault(orig, orig);
                    if(mappedInstrument == null) {
                        // Instrument got likely mapped to "nothing"
                        newNoteblocksForInstrument.put(orig, null);
                        continue;
                    }

                    newNoteblocksForInstrument.put(orig, noteblocksForInstrument.getOrDefault(instrumentMap.getOrDefault(orig, orig), new ArrayList<>()));
                }
                noteblocksForInstrument = newNoteblocksForInstrument;
            }

            // Find fitting noteblocks with the least amount of adjustments required (to reduce tuning time)
            ArrayList<Note> capturedNotes = new ArrayList<>();
            for(Note note : song.uniqueNotes) {
                ArrayList<BlockPos> availableBlocks = noteblocksForInstrument.get(note.instrument());
                if(availableBlocks == null) {
                    // Note was mapped to "nothing". Pretend it got captured, but just ignore it
                    capturedNotes.add(note);
                    getNotes(note.instrument()).put(note.note(), null);
                    continue;
                }
                BlockPos bestBlockPos = null;
                int bestBlockTuningSteps = Integer.MAX_VALUE;
                for(BlockPos blockPos : availableBlocks) {
                    int wantedNote = note.note();
                    int currentNote = client.world.getBlockState(blockPos).get(Properties.NOTE);
                    int tuningSteps = wantedNote >= currentNote ? wantedNote - currentNote : (25 - currentNote) + wantedNote;

                    if(tuningSteps < bestBlockTuningSteps) {
                        bestBlockPos = blockPos;
                        bestBlockTuningSteps = tuningSteps;
                    }
                }

                if(bestBlockPos != null) {
                    capturedNotes.add(note);
                    availableBlocks.remove(bestBlockPos);
                    getNotes(note.instrument()).put(note.note(), bestBlockPos);
                } // else will be a missing note
            }

            ArrayList<Note> missingNotes = new ArrayList<>(song.uniqueNotes);
            missingNotes.removeAll(capturedNotes);
            if (!missingNotes.isEmpty()) {
                ChatHud chatHud = MinecraftClient.getInstance().inGameHud.getChatHud();
                chatHud.addMessage(Text.translatable(Main.MOD_ID+".player.invalid_note_blocks").formatted(Formatting.RED));

                HashMap<Block, Integer> missing = new HashMap<>();
                for (Note note : missingNotes) {
                    NoteBlockInstrument mappedInstrument = instrumentMap.getOrDefault(note.instrument(), note.instrument());
                    if(mappedInstrument == null) continue; // Ignore if mapped to nothing
                    Block block = Note.INSTRUMENT_BLOCKS.get(mappedInstrument);
                    Integer got = missing.get(block);
                    if (got == null) got = 0;
                    missing.put(block, got + 1);
                }

                missingInstrumentBlocks = missing;
                missing.forEach((block, integer) -> chatHud.addMessage(Text.literal(block.getName().getString()+" × "+integer).formatted(Formatting.RED)));
                stop();
            }
        } else if (!tuned) {
            //tuned = true;

            int ping = 0;
            {
                PlayerListEntry playerListEntry;
                if (client.getNetworkHandler() != null && (playerListEntry = client.getNetworkHandler().getPlayerListEntry(client.player.getGameProfile().getId())) != null)
                    ping = playerListEntry.getLatency();
            }

            if(lastInteractAt != -1L) {
                // Paper allows 8 interacts per 300 ms (actually 9 it turns out, but lets keep it a bit lower anyway)
                availableInteracts += ((System.currentTimeMillis() - lastInteractAt) / (310.0f / 8.0f));
                availableInteracts = Math.min(8f, Math.max(0f, availableInteracts));
            }else {
                availableInteracts = 8f;
                lastInteractAt = System.currentTimeMillis();
            }

            int fullyTunedBlocks = 0;
            HashMap<BlockPos, Integer> untunedNotes = new HashMap<>();
            for (Note note : song.uniqueNotes) {
                if(noteBlocks == null || noteBlocks.get(note.instrument()) == null)
                    continue;
                BlockPos blockPos = noteBlocks.get(note.instrument()).get(note.note());
                if(blockPos == null) continue;
                BlockState blockState = world.getBlockState(blockPos);
                int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).getLeft() : blockState.get(Properties.NOTE);

                if (blockState.contains(Properties.NOTE)) {
                    if(assumedNote == note.note() && blockState.get(Properties.NOTE) == note.note())
                        fullyTunedBlocks++;
                    if (assumedNote != note.note()) {
                        if (!canInteractWith(client.player, blockPos)) {
                            stop();
                            client.inGameHud.getChatHud().addMessage(Text.translatable(Main.MOD_ID+".player.to_far").formatted(Formatting.RED));
                            return;
                        }
                        untunedNotes.put(blockPos, blockState.get(Properties.NOTE));
                    }
                } else {
                    noteBlocks = null;
                    break;
                }
            }

            if(tuneInitialUntunedBlocks == -1 || tuneInitialUntunedBlocks < untunedNotes.size())
                tuneInitialUntunedBlocks = untunedNotes.size();

            int existingUniqueNotesCount = 0;
            for(Note n : song.uniqueNotes) {
                if(noteBlocks.get(n.instrument()).get(n.note()) != null)
                    existingUniqueNotesCount++;
            }

            if(untunedNotes.isEmpty() && fullyTunedBlocks == existingUniqueNotesCount) {
                // Wait roundrip + 100ms before considering tuned after changing notes (in case the server rejects an interact)
                if(lastInteractAt == -1 || System.currentTimeMillis() - lastInteractAt >= ping * 2 + 100) {
                    tuned = true;
                    pausePlaybackUntil = System.currentTimeMillis() + (long) (Math.abs(Main.config.delayPlaybackStartBySecs) * 1000);
                    tuneInitialUntunedBlocks = -1;
                    // Tuning finished
                }
            }

            BlockPos lastBlockPos = null;
            int lastTunedNote = Integer.MIN_VALUE;
            float roughTuneProgress = 1 - (untunedNotes.size() / Math.max(tuneInitialUntunedBlocks + 0f, 1f));
            while(availableInteracts >= 1f && untunedNotes.size() > 0) {
                BlockPos blockPos = null;
                int searches = 0;
                while(blockPos == null) {
                    searches++;
                    // Find higher note
                    for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                        if (entry.getValue() > lastTunedNote) {
                            blockPos = entry.getKey();
                            break;
                        }
                    }
                    // Find higher note or equal
                    if (blockPos == null) {
                        for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                            if (entry.getValue() >= lastTunedNote) {
                                blockPos = entry.getKey();
                                break;
                            }
                        }
                    }
                    // Not found. Reset last note
                    if(blockPos == null)
                        lastTunedNote = Integer.MIN_VALUE;
                    if(blockPos == null && searches > 1) {
                        // Something went wrong. Take any note (one should at least exist here)
                        blockPos = untunedNotes.keySet().toArray(new BlockPos[0])[0];
                        break;
                    }
                }
                if(blockPos == null) return; // Something went very, very wrong!

                lastTunedNote = untunedNotes.get(blockPos);
                untunedNotes.remove(blockPos);
                int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).getLeft() : client.world.getBlockState(blockPos).get(Properties.NOTE);
                notePredictions.put(blockPos, new Pair<>((assumedNote + 1) % 25, System.currentTimeMillis() + ping * 2 + 100));
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.of(blockPos), Direction.UP, blockPos, false));
                lastInteractAt = System.currentTimeMillis();
                availableInteracts -= 1f;
                lastBlockPos = blockPos;
            }
            if(lastBlockPos != null) {
                // Turn head into spinning with time and lookup up further the further tuning is progressed
                //client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(((float) (System.currentTimeMillis() % 2000)) * (360f/2000f), (1 - roughTuneProgress) * 180 - 90, true));
                client.player.swingHand(Hand.MAIN_HAND);
            }
        }else if((playbackThread == null || !playbackThread.isAlive()) && running && Main.config.disableAsyncPlayback) {
            // Sync playback (off by default). Replacement for playback thread
            try {
                tickPlayback();
            }catch (Exception ex) {
                ex.printStackTrace();
                stop();
            }
        }
    }

    private HashMap<Byte, BlockPos> getNotes(NoteBlockInstrument instrument) {
        return noteBlocks.computeIfAbsent(instrument, k -> new HashMap<>());
    }

    // Before 1.20.5, the server limits interacts to 6 Blocks from Player Eye to Block Center
    // With 1.20.5 and later, the server does a more complex check, to the closest point of a full block hitbox
    // (max distance is BlockInteractRange + 1.0).
    private boolean canInteractWith(ClientPlayerEntity player, BlockPos blockPos) {
        final Vec3d eyePos = player.getEyePos();
        if(Main.config.expectedServerVersion == ModConfig.ExpectedServerVersion.v1_20_4_Or_Earlier) {
            return eyePos.squaredDistanceTo(blockPos.toCenterPos()) <= 6.0 * 6.0;
        }else if(Main.config.expectedServerVersion == ModConfig.ExpectedServerVersion.v1_20_5_Or_Later) {
            double blockInteractRange = player.getBlockInteractionRange() + 1.0;
            return new Box(blockPos).squaredMagnitude(eyePos) < blockInteractRange * blockInteractRange;
        }else if(Main.config.expectedServerVersion == ModConfig.ExpectedServerVersion.All) {
            // Require both checks to succeed (aka use worst distance)
            double blockInteractRange = player.getBlockInteractionRange() + 1.0;
            return eyePos.squaredDistanceTo(blockPos.toCenterPos()) <= 6.0 * 6.0
                    && new Box(blockPos).squaredMagnitude(eyePos) < blockInteractRange * blockInteractRange;
        }else {
            throw new NotImplementedException("ExpectedServerVersion Value not implemented: " + Main.config.expectedServerVersion.name());
        }
    }

    public double getSongElapsedSeconds() {
        if(song == null) return 0;
        return song.ticksToMilliseconds(tick) / 1000;
    }
}
