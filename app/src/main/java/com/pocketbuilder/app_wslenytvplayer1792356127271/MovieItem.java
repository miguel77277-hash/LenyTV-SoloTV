package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Una película o una serie del catálogo.
 *
 * Es la única estructura con la que trabaja la interfaz de PELÍCULAS: da igual
 * de qué repositorio venga el contenido, todo se convierte aquí.
 */
final class MovieItem {

    static final String KIND_MOVIE = "movie";
    static final String KIND_SERIES = "series";

    /** Enlace reproducible de una película (o de un episodio). */
    static final class Source {
        final String label;
        final String url;
        final String type;

        Source(String label, String url, String type) {
            this.label = label == null ? "" : label;
            this.url = url == null ? "" : url;
            this.type = type == null ? "" : type;
        }

        boolean usable() {
            return url.length() > 4;
        }
    }

    /** Un episodio de una temporada. */
    static final class Episode {
        final int season;
        final int number;
        final String title;
        final String synopsis;
        final String stillUrl;
        final ArrayList<Source> sources = new ArrayList<Source>();

        Episode(int season, int number, String title, String synopsis, String stillUrl) {
            this.season = season;
            this.number = number;
            this.title = title == null ? "" : title;
            this.synopsis = synopsis == null ? "" : synopsis;
            this.stillUrl = stillUrl == null ? "" : stillUrl;
        }

        String label() {
            String name = title.length() > 0 ? title : "Episodio " + number;
            return "T" + season + " · E" + number + " · " + name;
        }
    }

    /** Una temporada con sus episodios. */
    static final class Season {
        final int number;
        final String title;
        final ArrayList<Episode> episodes = new ArrayList<Episode>();

        Season(int number, String title) {
            this.number = number;
            this.title = title == null ? "" : title;
        }

        String label() {
            if (title.length() > 0) return title;
            return "Temporada " + number;
        }
    }

    String id = "";
    String title = "";
    String kind = KIND_MOVIE;
    String poster = "";
    String synopsis = "";
    String year = "";
    double rating = 0d;
    String repoName = "";
    /**
     * En una serie de una lista Xtream: la dirección de la API con la que se piden
     * sus temporadas y episodios. Se guarda con el título y solo se usa cuando el
     * usuario abre la ficha de esa serie (así no se piden cientos de consultas al
     * cargar la lista). En el resto de títulos está vacío.
     */
    String resolveUrl = "";
    final ArrayList<String> genres = new ArrayList<String>();
    final ArrayList<Source> sources = new ArrayList<Source>();
    final ArrayList<Season> seasons = new ArrayList<Season>();

    boolean isSeries() {
        return KIND_SERIES.equals(kind) || (!seasons.isEmpty() && sources.isEmpty());
    }

    /** "2021 · 7.8 · Acción, Drama" para la ficha y las tarjetas. */
    String metaLine() {
        StringBuilder out = new StringBuilder();
        if (year.length() > 0) out.append(year);
        if (rating > 0d) {
            if (out.length() > 0) out.append(" · ");
            out.append(String.format(Locale.US, "%.1f", rating));
        }
        if (!genres.isEmpty()) {
            if (out.length() > 0) out.append(" · ");
            out.append(genres.get(0));
            for (int i = 1; i < genres.size() && i < 2; i++) out.append(", ").append(genres.get(i));
        }
        if (out.length() == 0) out.append(isSeries() ? "Serie" : "Película");
        return out.toString();
    }

    String kindLabel() {
        return isSeries() ? "Serie" : "Película";
    }

    /** Todos los episodios de la serie, temporada a temporada. */
    ArrayList<Episode> allEpisodes() {
        ArrayList<Episode> out = new ArrayList<Episode>();
        for (int s = 0; s < seasons.size(); s++) {
            Season season = seasons.get(s);
            if (season != null) out.addAll(season.episodes);
        }
        return out;
    }

    int episodeCount() {
        int total = 0;
        for (int s = 0; s < seasons.size(); s++) {
            Season season = seasons.get(s);
            if (season != null) total += season.episodes.size();
        }
        return total;
    }

    /** Primer enlace que se puede reproducir (película). */
    Source firstSource() {
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            if (source != null && source.usable()) return source;
        }
        return null;
    }

    boolean playable() {
        if (isSeries()) {
            ArrayList<Episode> episodes = allEpisodes();
            for (int i = 0; i < episodes.size(); i++) {
                Episode episode = episodes.get(i);
                for (int s = 0; s < episode.sources.size(); s++) {
                    if (episode.sources.get(s).usable()) return true;
                }
            }
            return false;
        }
        return firstSource() != null;
    }

    /** Texto que se busca con el buscador. */
    String searchKey() {
        return (title + " " + year + " " + genres).toLowerCase(Locale.US);
    }
}
