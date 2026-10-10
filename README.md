# Kanso Podcast 0.1.7.1

Una experiencia de podcast serena: menos mantenimiento, más escucha.

## Estado de la primera entrega

- App Android nativa en Kotlin + Jetpack Compose.
- Inicio, episodios recientes, biblioteca, carpetas, descargas y perfil.
- Ajustes visibles para descarga, archivado y borrado automáticos.
- `MediaLibraryService` compartido por teléfono y Android Auto.
- Widget Glance registrado.
- Media3 Cast incluido para la siguiente conexión a Chromecast.
- Modelo freemium presentado en producto: Gratis con anuncios y Kanso+.
- Tema claro y oscuro con paletas Tierra, Bosque, Océano y Ciruela.
- Búsqueda real por nombre mediante el directorio de Apple Podcasts.
- Lectura y validación de una URL RSS pegada por el usuario.
- Suscripciones persistentes visibles en la biblioteca.
- Lectura de hasta 100 episodios reales por RSS.
- Reproducción en segundo plano mediante la sesión Media3 compartida.
- Recuperación local de la posición de escucha.
- Mini reproductor con pausa y saltos.
- Favoritos y estado escuchado persistentes.
- Descargas manuales mediante el gestor de Android.
- Inicio alimentado únicamente por suscripciones y episodios reales.
- Carpetas persistentes y movimiento de podcasts entre ellas.
- Cola de reproducción, velocidad y temporizador de 30 minutos.
- Automatización periódica de feeds, descargas y limpieza con WorkManager.
- Reglas configurables reales: cantidad, días de retención, archivado y Wi‑Fi.
- Widget alimentado por el episodio actual, sin contenido de demostración.
- Pantalla Now Playing completa con portada, progreso, cola y controles.
- Podcasts agrupados por carpetas reales directamente en Inicio.
- Filtros globales de pendientes, favoritos y archivados.
- Restauración de episodios archivados.
- Cola editable y temporizador de 5 a 60 minutos.
- Cantidad de descargas automáticas configurable por podcast.
- Importación y exportación OPML.
- Caché offline de feeds con actualización transparente.
- Reproducción preferente desde archivos descargados.
- Progreso, cancelación, eliminación y reintento de descargas.
- Widget interactivo conectado a la sesión multimedia.
- Catálogo y búsqueda navegables desde Android Auto.
- Selector Chromecast conservado; transferencia aislada temporalmente del reproductor local hasta validarla con hardware.
- Hotfix: el servicio local ya no inicializa Cast al comenzar una reproducción.
- Hotfix crítico: reproducción desacoplada por completo del servicio que causaba el cierre.
- Now Playing se abre al iniciar un episodio y el mini reproductor usa un diseño ampliado.
- La pantalla principal es únicamente la biblioteca; se retiró Pendientes.
- Fuente HTTP de audio con redirecciones y agente de usuario para servidores de podcast.
- Errores de reproducción visibles y acción para reintentar.
- Servicio de reproducción estable en segundo plano, sin Auto ni Cast.
- Restauración del episodio, posición, velocidad y cola después de cerrar.
- Navegación Atrás jerárquica.
- Mini reproductor de 92 dp con progreso y retroceso rápido.
- Descargas accesibles como pestaña principal.
- Actualización manual de feeds, diagnóstico y permiso de notificaciones.

## Decisiones de producto

- Una sola aplicación, con dos planes.
- Las reglas globales se pueden sobrescribir por podcast o carpeta.
- Favoritos y episodios conservados nunca se eliminan automáticamente.
- Reproducción local, Android Auto, widget y Cast comparten una sesión.
- Los anuncios nunca interrumpen un episodio; aparecen solo en superficies de exploración para usuarios gratis.

## Próximas iteraciones

1. Persistencia con Room, importación OPML y lector RSS.
2. Descargas con WorkManager y motor de reglas de retención.
3. Conectar cada pantalla al `MediaController` y poblar el catálogo de Android Auto.
4. Selector Cast y transferencia local/remota conservando posición y cola.
5. Widget interactivo con play/pausa, avance y episodio actual.
6. Suscripción Kanso+, anuncios, sincronización y pruebas.

## Compilar

Abrir `kanso-podcast` en Android Studio (JDK 21, SDK 36) o ejecutar `gradlew assembleDebug` cuando el wrapper esté generado.
