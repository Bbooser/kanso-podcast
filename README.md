# Kanso Podcast 0.2.0
Una experiencia de podcast serena: menos mantenimiento, más escucha.

App Android nativa en Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 36.

## Qué hace hoy
- **Descubrir**: búsqueda por nombre en el directorio de Apple Podcasts o pegando la URL de un RSS.
- **Biblioteca**: seguir/dejar de seguir, carpetas, importación y exportación OPML.
- **Escuchar**: vista con *A continuación* (cola), *En progreso* y *Nuevos* de tus suscripciones, más filtros de Favoritos y Archivados (con restauración).
- **Episodios**: favorito, escuchado, archivar, añadir a la cola, descargar; barra de progreso y tiempo restante; los escuchados/archivados se atenúan y los archivados se ocultan en el detalle del podcast.
- **Reproducción** (Media3 / ExoPlayer en un `MediaLibraryService`): segundo plano, notificación, −10 s / +30 s, velocidades 0.8×–3×, temporizador (5–60 min o al terminar el episodio), cola editable, reanudación por episodio y restauración de la sesión al reabrir. Reproduce desde el archivo descargado si existe.
- **Descargas** con el `DownloadManager` del sistema: progreso, reintento y borrado; respeta “solo Wi‑Fi”.
- **Automatización** (WorkManager, cada 6 h): actualiza feeds, descarga N episodios nuevos por podcast (global o por podcast) y borra descargas ya escuchadas tras X días (o nunca). Los favoritos nunca se borran.
- **Android Auto**: navegación de suscripciones y episodios y búsqueda.
- **Widget** (Glance): episodio actual con reproducir/pausa y saltos.
- Tema claro/oscuro con paletas Tierra, Bosque, Océano y Ciruela.

## Arquitectura
- `data/db`: Room (`podcasts`, `episodes` con estado y progreso, `queue`, `downloads`, `folders`).
- `data/KansoRepository`: fuente única de verdad (Flows); `LegacyMigration` importa una sola vez los datos de la versión 0.1.x guardados en SharedPreferences, sin borrarlos.
- `data/PodcastDirectory`: búsqueda en Apple Podcasts y lector RSS; `data/Opml`; `data/Downloads`; `data/AutomationWorker`.
- `playback/KansoPlaybackService`: autoridad de reproducción; guarda progreso, marca episodios terminados, persiste la cola y ejecuta el temporizador.
- `ui/`: una pantalla por archivo con su `ViewModel` (`LibraryScreen`, `ListenScreen`, `PodcastDetailScreen`, `SearchScreen`, `DownloadsScreen`, `SettingsScreen`, `NowPlayingScreen`), `PlayerConnection` (MediaController) y `Components`.
- Ajustes (apariencia, automatización) siguen en SharedPreferences.

## Aún no implementado
- Chromecast, suscripción Kanso+ y anuncios.
- Sincronización entre dispositivos, capítulos, transcripciones, recorte de silencios.
- Tests automatizados.

## Red
Se permite HTTP en claro (vía `res/xml/network_security_config.xml`) solo porque muchos feeds y audios de podcast siguen publicándose en `http://`; solo se confía en CAs del sistema.

## Compilar
JDK 17+ (probado con 21) y Android SDK 36: `./gradlew assembleDebug`.
