package fr.tamarou.radio.client;

import fr.tamarou.radio.RadioConfig;
import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.block.RadioBlockEntity;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * Le son d'une radio : il part de la radio (ou de l'enceinte, ou du joueur qui porte la radio portable)
 * et baisse jusqu'à zéro à la portée choisie. Au casque, il n'a pas de position : il est « dans la tête ».
 * Il passe par le réglage « Juke-box / Blocs musicaux » du jeu.
 */
public final class RadioSound extends AbstractTickableSoundInstance {
    private RadioBlockEntity radio;
    private final SharedPcm.Reader stream;
    private final int attenuationDistance;
    private float factor = 1;

    public RadioSound(RadioBlockEntity radio, SharedPcm.Reader stream, Vec3 where, int attenuationDistance, boolean headphones) {
        super(RadioMod.RADIO_STREAM.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.radio = radio;
        this.stream = stream;
        this.attenuationDistance = attenuationDistance;
        this.looping = false;
        this.delay = 0;
        this.relative = headphones;
        this.attenuation = headphones ? Attenuation.NONE : Attenuation.LINEAR;
        moveTo(where);
        this.volume = targetVolume();
    }

    private void moveTo(Vec3 where) {
        if (relative) {
            x = y = z = 0;
        } else {
            x = where.x;
            y = where.y;
            z = where.z;
        }
    }

    /** Appelé à chaque tick par le gestionnaire : la radio peut avoir changé (portable), bougé, ou l'enceinte la plus proche aussi. */
    void follow(RadioBlockEntity radio, Vec3 where, float factor) {
        this.radio = radio;
        this.factor = factor;
        moveTo(where);
    }

    int attenuation() {
        return attenuationDistance;
    }

    float factor() {
        return factor;
    }

    private float targetVolume() {
        return Math.max(0.0001f, radio.volume() / 100f * RadioConfig.VOLUME.get() / 100f * factor);
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents w = super.resolve(manager);
        // Même son « porteur », mais en flux et avec la portée de cette radio
        this.sound = new Sound(sound.getLocation(), ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE,
                true, false, attenuationDistance);
        return w;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (radio.isRemoved()) {
            stop();
            return;
        }
        volume = targetVolume();
    }


}
