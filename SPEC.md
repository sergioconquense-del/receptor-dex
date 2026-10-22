# Especificación: receptor Miracast / Samsung DeX para Android TV

Estado a 22/10/2026. Describe lo que está construido, de dónde sale cada pieza y por qué.

## Qué es

Una sesión de streaming en tiempo real e interactiva, no un reproductor de vídeo: el móvil emite
su pantalla como un único flujo MPEG-TS (H.264 + audio) sobre RTP, controlado por RTSP, y la tele
devuelve la entrada del usuario (UIBC). El cursor, los clics y el scroll ocurren en el móvil, que
los dibuja dentro de la propia imagen: la tele solo muestra vídeo.

## Arquitectura

```
Wi-Fi Direct (WifiP2pManager vía Shizuku)  -> la tele aparece como receptor WFD
RtspSink  (TCP 7236)  -> control M1-M7, keep-alive, TEARDOWN/PAUSE/PLAY, petición de IDR
RtpReceiver (UDP 19000) -> ordena por nº de secuencia, espera <=50 ms a un hueco
RtpTsDataSource -> StreamSession (Media3 ExoPlayer + TsExtractor) -> pantalla y audio
UibcSender (TCP 19001)  -> teclas del mando hacia el móvil
WfdService (servicio en primer plano) lo mantiene todo vivo; MainActivity solo pinta y guía.
```

## De dónde sale cada pieza

| Pieza | Fuente |
|---|---|
| Orden de paquetes RTP, espera máxima de 50 ms, descarte de duplicados | Receptor Miracast de AOSP (android-4.2, `wifi-display/sink/TunnelRenderer.cpp`) |
| El puerto RTP se abre antes del SETUP | `WifiDisplaySink` de AOSP; si no, se pierden el SPS/PPS y el primer fotograma |
| Demultiplexado TS, H.264, decodificación, sincronía A/V | Media3 (`TsExtractor`, `MediaCodecVideoRenderer`) |
| LPCM de WFD (cabecera `0xA0`, 80 muestras estéreo por trama, big-endian) | Port de `ESQueue::dequeueAccessUnitPCMAudio` de AOSP → `LpcmReader` |
| Detección de fotogramas H.264 sin delimitadores | `FLAG_DETECT_ACCESS_UNITS` de Media3: DeX no envía AUD y sin él no sale ni un fotograma |
| Perfiles H.264 (Baseline y High) y mapas de resoluciones | Ejemplo verificado de miraclecast (`res/sinkctl.protocol-extension.example`) |
| Formato de paquete UIBC | `miracle-uibcctl.c` de miraclecast |
| `CONFIGURE_WIFI_DISPLAY` (permiso de firma) | Shizuku: un `UserService` con identidad `shell` anuncia el receptor |

No se usa código con licencia GPL. Se probó el modelo de decodificación de Moonlight (GPL-3.0) y se
revirtió, así que la app no lo contiene.

## Decisiones que no son evidentes

- `targetSdk 30` a propósito: `WifiP2pServiceImpl` usa la ruta antigua de permisos (ubicación en vez
  de `NEARBY_WIFI_DEVICES`). Impide publicar en Google Play; la distribución realista es APK o F-Droid.
- `ACCESS_BACKGROUND_LOCATION` es obligatorio: con la pantalla de la tele apagada y solo la ubicación
  "mientras se usa", `startListening()` falla con `reason=0` y la tele deja de verse desde el móvil.
- Audio LPCM: big-endian a little-endian, 48 kHz con `wfd_audio_codecs: LPCM 00000002`
  (bit1 = 48 kHz, bit0 = 44,1 kHz; el orden contrario fue un error inicial) y 4 bytes de cabecera por trama.

## Criterios de aceptación

| Criterio | Estado |
|---|---|
| Imagen limpia en movimiento, sin bloques | Cumple |
| Audio sincronizado, sin pitidos ni cortes | Cumple |
| Latencia del cursor suficiente para usarlo con comodidad | **No cumple**: casi 1 s al desplazar con el dedo |
| Reconexión, reposo de pantalla, salir al menú de la tele | Cumple |
| Mando de la tele moviendo el cursor de DeX | Experimental: solo la tecla Espacio verificada |

## Latencia: lo que se sabe

Media3 acumula entre 0,5 y 1 s entre lo que llega y lo que se ve (medido en el registro: `buffered`).
Dos causas identificadas: el buffer de audio de Media3 (unos 385 ms en esta tele, cuyo mínimo es
96 ms) y el retraso que se acumula al arrancar y no se recupera. Intentos descartados:
reproducir más rápido para recuperar retraso y bajar el buffer de audio a 100 ms (entrecortó el vídeo),
y sustituir ExoPlayer por un renderizador al estilo Moonlight (el audio se cortaba). Siguiente paso
de menor riesgo: buffer de audio a 2 veces el mínimo del hardware (~190 ms), probando cada cambio por separado.
