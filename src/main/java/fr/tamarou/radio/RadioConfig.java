package fr.tamarou.radio;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Réglages : une partie pour chaque joueur (client), une pour les admins du serveur. */
public final class RadioConfig {
    public enum Who { EVERYONE, OWNER_AND_OPS, OPS }

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec SERVER_SPEC;

    // Joueur
    public static final ModConfigSpec.BooleanValue HEAR_RADIOS;
    public static final ModConfigSpec.IntValue VOLUME;
    public static final ModConfigSpec.BooleanValue SHOW_NOW_PLAYING;
    public static final ModConfigSpec.BooleanValue MUTE_MUSIC_NEAR_RADIO;

    // Serveur
    public static final ModConfigSpec.BooleanValue ALLOW_SEARCH;
    public static final ModConfigSpec.BooleanValue ALLOW_STATIONS;
    public static final ModConfigSpec.EnumValue<Who> WHO_CAN_PLACE;
    public static final ModConfigSpec.EnumValue<Who> WHO_CAN_CONTROL;
    public static final ModConfigSpec.IntValue MAX_RANGE;
    public static final ModConfigSpec.IntValue MAX_QUEUE_PER_PLAYER;

    static {
        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        HEAR_RADIOS = c.comment("Hear radios placed in the world").define("hearRadios", true);
        VOLUME = c.comment("Volume of all radios, in percent (on top of the Jukebox/Note Blocks slider)").defineInRange("volume", 80, 0, 100);
        SHOW_NOW_PLAYING = c.comment("Show the song name above the hotbar when a new song starts").define("showNowPlaying", true);
        MUTE_MUSIC_NEAR_RADIO = c.comment("Stop the Minecraft background music while you hear a radio").define("muteMusicNearRadio", true);
        CLIENT_SPEC = c.build();

        ModConfigSpec.Builder s = new ModConfigSpec.Builder();
        ALLOW_SEARCH = s.comment("Allow the song search (Spotify / Deezer links and titles)").define("allowSearch", true);
        ALLOW_STATIONS = s.comment("Allow live web radios").define("allowStations", true);
        WHO_CAN_PLACE = s.comment("Who can place a radio").defineEnum("whoCanPlace", Who.EVERYONE);
        WHO_CAN_CONTROL = s.comment("Who can change what a radio plays. OWNER_AND_OPS: the player who placed it, and operators")
                .defineEnum("whoCanControl", Who.EVERYONE);
        MAX_RANGE = s.comment("Maximum range of a radio, in blocks").defineInRange("maxRange", 48, 8, 128);
        MAX_QUEUE_PER_PLAYER = s.comment("Maximum songs one player can have waiting in a radio's queue").defineInRange("maxQueuePerPlayer", 5, 1, 100);
        SERVER_SPEC = s.build();
    }

    private RadioConfig() {}
}
