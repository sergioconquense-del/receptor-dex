# Licencias y atribuciones

Receptor DeX se distribuye bajo **Apache License 2.0** (ver `LICENSE`).

## Librerías incluidas (todas Apache-2.0)

| Librería | Uso |
|---|---|
| AndroidX Media3 (ExoPlayer, extractor) 1.4.1 | Demultiplexado MPEG-TS, decodificación y sincronía A/V |
| Shizuku api / provider 13.1.5 (RikkaApps) | Proceso con privilegios de shell para anunciar el receptor |
| Dadb 1.2.10 (mobile-dev-inc) | Cliente ADB para arrancar Shizuku desde la propia app |

## Obras de referencia (no se ha copiado su código)

- **Android Open Source Project (Apache-2.0)**: la política de ordenación de paquetes RTP
  (`RtpReceiver`), el tratamiento de LPCM (`LpcmReader`) y el orden de la negociación RTSP están
  reimplementados a partir del comportamiento del sumidero WFD de AOSP (`TunnelRenderer`, `ESQueue`).
- **MiracleCast (LGPL-2.1+, David Herrmann y colaboradores)**: solo se tomaron como referencia datos del
  protocolo (máscaras de `wfd_video_formats`, formato de paquetes UIBC). No contiene código de MiracleCast.
- **Shizuku**: marca de RikkaApps; esta app no está afiliada.

## Marcas

"Miracast" es marca de la Wi-Fi Alliance y "Samsung DeX" de Samsung Electronics. Esta app no está certificada
ni afiliada a ninguna de ellas; se nombran solo para indicar compatibilidad. El icono es de diseño propio.
