package com.pocketbuilder.app_wslenytvplayer1792356127271.movies;

/**
 * Contrato de un ADAPTADOR DE PROVEEDOR del módulo de películas.
 *
 * Un adaptador sabe convertir UNA fuente concreta (un catálogo JSON, una web, un
 * índice de repositorio…) en los títulos que entiende la cuadrícula:
 * cartel, sinopsis, temporadas, episodios y enlaces de reproducción.
 *
 * Está aquí para poder añadir proveedores de uno en uno sin tocar el resto del
 * módulo: cada proveedor nuevo implementa esta interfaz y se añade a
 * {@link ProviderRegistry}. La pantalla no cambia.
 *
 * Aviso importante y explícito: los complementos .cs3 de CloudStream NO se
 * ejecutan en esta app (necesitan el motor y las librerías de CloudStream, que
 * este proyecto no tiene). Un adaptador se escribe aquí, en Java, para cada
 * proveedor. Mientras un proveedor no tenga adaptador propio, su estado es
 * "pendiente" y la app lo dice tal cual.
 */
public interface ProviderAdapter {

    /** Identificador interno del proveedor (el mismo con el que se guarda). */
    String repoId();

    /** Nombre que se enseña al usuario. */
    String providerName();

    /** Qué motor lee este proveedor, en palabras sencillas. */
    String engineLabel();

    /** Verdadero si el motor ya está implementado en esta versión de la app. */
    boolean ready();

    /** Qué hace falta para que funcione (dirección, cuenta, nada...). */
    String requirement();

    /** Explicación corta de lo que se puede esperar (y de lo que no). */
    String note();
}
