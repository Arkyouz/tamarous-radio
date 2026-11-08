package fr.tamarou.radio.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.media.Http;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Les pochettes : téléchargées une fois, gardées en texture (les 200 dernières). */
public final class CoverCache {
    private static final ResourceLocation LOADING = ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "loading");
    private static final Map<String, ResourceLocation> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static int counter;

    private CoverCache() {}

    /** La texture de la pochette, ou null tant qu'elle n'est pas prête (ou introuvable). */
    public static ResourceLocation get(String url) {
        if (url == null || url.isEmpty()) return null;
        ResourceLocation rl = CACHE.get(url);
        if (rl != null) return rl == LOADING ? null : rl;
        CACHE.put(url, LOADING);
        CompletableFuture.supplyAsync(() -> {
            try {
                return decode(Http.getBytes(url));
            } catch (Throwable e) {
                return null;
            }
        }).thenAccept(img -> Minecraft.getInstance().execute(() -> {
            if (img == null) return;
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "cover/" + (counter++));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
            CACHE.put(url, id);
            trim();
        }));
        return null;
    }

    private static NativeImage decode(byte[] data) throws Exception {
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
        if (bi == null) return NativeImage.read(new ByteArrayInputStream(data));
        // Réduite à 128 px : assez pour l'écran, et moins de mémoire
        int size = 128;
        NativeImage img = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = bi.getRGB(x * bi.getWidth() / size, y * bi.getHeight() / size);
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }
        return img;
    }

    private static void trim() {
        while (CACHE.size() > 200) {
            var it = CACHE.entrySet().iterator();
            var e = it.next();
            it.remove();
            if (e.getValue() != LOADING) Minecraft.getInstance().getTextureManager().release(e.getValue());
        }
    }
}
