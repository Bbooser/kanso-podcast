# Kanso Podcast 0.3.0
Una experiencia de podcast serena: menos mantenimiento, más escucha.

App Android nativa en Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 36.

## Qué hace hoy
Cinco pestañas, con mini reproductor flotante (−30 s · reproducir · +30 s · cola):
- **Podcasts**: cuadrícula de portadas con carpetas (mantén presionado para mover o dejar de seguir), orden por título o recientes, Chromecast y búsqueda.
- **Playlists**: playlists inteligentes con reglas (podcasts, estado de escucha, descargados, duración, fecha de publicación, archivados). Cabecera con número de episodios y tiempo total, *Reproducir todo* y búsqueda. Se crean cuatro por defecto: En curso, Nuevos, Para un rato y Descargados.
- **Descubrir**: búsqueda por nombre o URL de RSS, categorías y tendencias de Apple Podcasts (México).
- **A continuación**: episodio actual y cola; desliza a la derecha cualquier episodio para añadirlo y arrastra ≡ para reordenar.
- **Perfil**: podcasts, días escuchados y minutos ahorrados; Estadísticas, Descargas, Archivos, Destacado, Marcadores, Historial y Ajustes.

Reproducción (Media3 / ExoPlayer en un `MediaLibraryService`):
- Saltos de 30 s hacia atrás y adelante en la app, la notificación, el widget y Android Auto.
- Velocidad 0.8×–3×, **recorte de silencios** y **refuerzo de voz**; el tiempo ahorrado se cuenta en las estadísticas.
- Temporizador (5–60 min o al terminar el episodio), marcadores en cualquier momento, historial de escucha.
- **Capítulos** (`podcast:chapters`) y **transcripciones** (`podcast:transcript`: VTT, SRT, JSON, HTML o texto) cuando el feed los incluye.
- **Chromecast** (receptor multimedia por defecto de Google) usando `media3-cast`; se inicializa solo al tocar el botón.
- Reanudación por episodio, restauración de la sesión, reproducción desde el archivo descargado si existe.

Además: descargas con el `DownloadManager` (solo Wi‑Fi opcional), automatización cada 6 h (feeds, descargas, borrado de escuchados), OPML, Android Auto, widget y tema claro/oscuro con paletas Tierra, Bosque, Océano y Ciruela.

## Arquitectura
- `data/db`: Room v2 (`podcasts`, `episodes` con estado, progreso, capítulos y transcripción, `queue`, `downloads`, `folders`, `playlists`, `bookmarks`, `listening_days`); migración automática 1→2.
- `data/KansoRepository`: fuente única de verdad (Flows); `LegacyMigration` importa una sola vez los datos de la versión 0.1.x guardados en SharedPreferences, sin borrarlos.
- `data/PodcastDirectory`: búsqueda en Apple Podcasts y lector RSS; `data/Opml`; `data/Downloads`; `data/AutomationWorker`.
- `playback/KansoPlaybackService`: autoridad de reproducción; guarda progreso, marca episodios terminados, persiste la cola y ejecuta el temporizador.
- `ui/`: una pantalla por archivo con su `ViewModel` (`PodcastsScreen`, `PlaylistsScreen`, `DiscoverScreen`, `UpNextScreen`, `ProfileScreen`, `PodcastDetailScreen`, `NowPlayingScreen`, `DownloadsScreen`, `SettingsScreen`), `PlayerConnection` (MediaController) y `Components`.
- `data/EpisodeExtras`: lectores de capítulos y transcripciones.
- Ajustes (apariencia, automatización) siguen en SharedPreferences.

## Aún no implementado
- Suscripción Kanso+ y anuncios.
- Sincronización entre dispositivos.
- Tests automatizados.

## Red
Se permite HTTP en claro (vía `res/xml/network_security_config.xml`) solo porque muchos feeds y audios de podcast siguen publicándose en `http://`; solo se confía en CAs del sistema.

## Compilar
JDK 17+ (probado con 21) y Android SDK 36: `./gradlew assembleDebug`.
