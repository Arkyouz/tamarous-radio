package fr.tamarou.radio.media;

/**
 * Une chanson trouvée par la recherche. Les infos visibles (titre, artiste, pochette) viennent de Deezer,
 * le son d'une vidéo YouTube ({@code videoId}) trouvée au moment d'ajouter la chanson.
 */
public record TrackInfo(String title, String artist, String album, String coverUrl, int durationSec, String videoId) {
    public TrackInfo withVideo(String id, int duration) {
        return new TrackInfo(title, artist, album, coverUrl, duration > 0 ? duration : durationSec, id);
    }

    public String display() {
        return artist.isEmpty() ? title : artist + " - " + title;
    }
}
