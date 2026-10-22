package com.pocketbuilder.app_wslenytvplayer1792356127271;

/**
 * Lista de demostración incluida en la app. Solo enlaces públicos de prueba
 * (streams de test de Apple, Google y Mux) para comprobar el reproductor.
 * La app no incluye ningún canal ni contenido de terceros.
 */
final class DemoData {

    static final String NAME = "Lista de demostración";

    private DemoData() {
    }

    static String text() {
        return "#EXTM3U\n"
                + "#PLAYLIST:Demostración LENY TV\n"
                + "#EXTINF:-1 tvg-name=\"HLS Big Buck Bunny\" group-title=\"HLS\",Prueba HLS · Big Buck Bunny (fMP4)\n"
                + "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
                + "#EXTINF:-1 tvg-name=\"HLS Apple BipBop\" group-title=\"HLS\",Prueba HLS · Apple BipBop\n"
                + "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8\n"
                + "#EXTINF:-1 tvg-name=\"MP4 Big Buck Bunny\" group-title=\"MP4\",Prueba MP4 · Big Buck Bunny\n"
                + "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4\n"
                + "#EXTINF:-1 tvg-name=\"MP4 Elephants Dream\" group-title=\"MP4\",Prueba MP4 · Elephants Dream\n"
                + "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4\n"
                + "#EXTINF:-1 tvg-name=\"WebM Big Buck Bunny\" group-title=\"WebM\",Prueba WebM · Big Buck Bunny tráiler\n"
                + "https://dl8.webmfiles.org/big-buck-bunny_trailer.webm\n"
                + "#EXTINF:-1 tvg-name=\"DASH Envivio\" group-title=\"DASH\",Prueba DASH · Envivio (vídeo y audio en pistas separadas)\n"
                + "https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd\n"
                + "#EXTINF:-1 tvg-name=\"DASH BBB 30fps\" group-title=\"DASH\",Prueba DASH · Big Buck Bunny 30fps (varias calidades)\n"
                + "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd\n"
                + "#EXTINF:-1 tvg-name=\"HLS Apple bipbop TS\" group-title=\"HLS\",Prueba HLS · Apple bipbop (trozos .ts)\n"
                + "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8\n";
    }
}
