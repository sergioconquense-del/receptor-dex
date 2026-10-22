# Receptor Miracast / DeX: estado y pendientes

Estado a 22/10/2026. Hardware de pruebas: Philips 55OLED718/12 (MediaTek, Android 14) y un Samsung
Galaxy S23 Ultra. La arquitectura y sus fuentes están en [SPEC.md](SPEC.md).

## Funciona (verificado en vivo)

- La tele se anuncia como receptor desde la propia app, sin script externo (Shizuku).
- Vídeo 1920x1080 con perfil H.264 High, audio LPCM 48 kHz estéreo, ambos por el mismo flujo.
- Servicio en primer plano: salir al menú de la tele no corta la sesión; cortar desde el móvil y volver a
  conectar funciona sin relanzar; con la pantalla apagada sigue anunciándose.
- Recuperación ante fallos: hueco de paquetes (se pide un fotograma completo), error del decodificador
  (ExoPlayer reintenta y se pide otro fotograma).
- Permisos con el diálogo normal de Android y panel de estado en pantalla, manejables con el mando
  (comprobado: el aviso de ubicación sale y "Permitir siempre" se elige desde Ajustes).

- **Shizuku se arranca solo** ([ShizukuStarter.java](app/src/main/java/com/wfdtest/app/ShizukuStarter.java)):
  la app se conecta como cliente ADB a la depuración por red de la propia tele (puerto 5555, que sigue activa
  tras reiniciar) y ejecuta el mismo comando que muestra Shizuku. Comprobado: matando Shizuku, la app lo
  levanta en 6 s sin ningún aviso. La primera vez, la tele pregunta una sola vez si confía en la clave de la
  app (marcar "Permitir siempre desde este ordenador" y pulsar "Permitir"; el foco cae en "Cancelar").
- **Autorización de móviles**: un móvil nuevo solo puede conectarse con la app abierta y aceptándolo con el mando
  ("Aceptar", "Aceptar siempre", "Rechazar"); con la app cerrada se rechaza y se corta el enlace. Los de
  confianza se guardan; la tecla Menú los olvida. **Sin probar con un móvil real** (si DeX aguanta los 30 s de
  espera y si su dirección Wi-Fi Direct es estable entre conexiones).
- **Icono y banner propios** (pantalla con ondas y móvil); nombre "Receptor DeX". Se ve en el launcher.

## Hecho pero sin probar en condiciones reales

- **El mando como puntero: EXPERIMENTAL en v1.0.** Mapa fijo ([RemoteInput.java](app/src/main/java/com/wfdtest/app/RemoteInput.java)):
  flechas = puntero, OK = clic, Atrás = Alt+Izq (mantener = Esc), Verde/Amarillo = scroll, Rojo/Azul = zoom.
  Se corrigió un fallo real (envío desde el hilo de la interfaz, que desactivaba el canal), y el móvil ya
  recibe los paquetes (se anuncian teclado, ratón y táctil). **Comprobado en vivo** comparando capturas:
  solo una tecla con código ASCII (Espacio, 0x20) actúa en el móvil (baja la página en Chrome; el scroll
  "abajo" usa esa tecla). Movimiento de puntero, clic, rueda, táctil de 1 y 2 dedos, Ctrl+rueda y los códigos
  HID/X11/VK/Android de flechas, Ctrl, Alt y Esc no produjeron cambio visible. Falta la tabla de códigos que
  acepta DeX; sin ella el resto del mando no es fiable.

- **Arranque al encender la tele** (`BootReceiver`): el sistema lo tiene registrado, pero Android no deja
  simular el aviso desde `adb` y no se ha reiniciado la tele. Probar reiniciándola.
- **TEARDOWN, PAUSE y PLAY** (cierre limpio y órdenes de pausa/reanudación del móvil, también como
  `wfd_trigger_method`): implementado en `RtspSink`; hace falta bloquear la pantalla del móvil o cerrar DeX
  para ver si se comporta.
- **Cambio de resolución a mitad de sesión**: debería gestionarlo Media3 reconfigurando el decodificador;
  no se ha provocado.

## Pendiente

1. **Latencia del cursor (~1 s)**, ver SPEC.md. Es lo único que impide un uso cómodo.
2. **Mando como puntero**: averiguar la codificación de teclas/puntero que acepta DeX (hoy solo ASCII funciona,
   ver arriba). Pistas: un barrido de códigos mostró cambios con códigos bajos y flechas Unicode, sin analizar.
3. **Arranque tras reiniciar la tele**: probar el `BootReceiver` reiniciándola (Shizuku ya se levanta solo).
   Depende de que la depuración por red siga activa tras reiniciar (lo está en esta tele).
4. **Un solo móvil y una sola tele probados.** Los parámetros `wfd_sec_*` son de Samsung; con otro fabricante
   hay que comprobar que el receptor funciona como Miracast genérico.
5. **AAC opcional** en lugar de LPCM sin comprimir (~1,5 Mbps). Solo ahorraría ancho de banda si el móvil lo
   elige; Media3 ya lo decodifica. No urgente.
6. **Seguridad**: el canal UIBC no está cifrado ni autenticado (riesgo bajo en casa; documentarlo si se publica).

## Publicación

Aconsejado: gratuita y de código abierto, no de pago (certificación Miracast de la Wi-Fi Alliance, patentes
de H.264, extensiones de Samsung sin documentar). Sin `targetSdk 30` no funciona (ver SPEC.md), así que no
cabe en Google Play. El código actual no contiene GPL.

## Lecciones de esta sesión

- Cada síntoma de audio tenía una causa concreta y comprobable (bit de frecuencia invertido, cabecera de
  4 bytes por trama); se resolvieron con volcados y análisis de frecuencia, no ajustando a oído.
- Los experimentos de ritmo de fotogramas a mano (dormir, marcas de tiempo, buffer propio) fallaron uno por
  uno; la solución fue usar el demultiplexador de Media3 y el orden de paquetes de AOSP.
- Cambiar varias cosas a la vez impidió ver qué empeoraba: probar un cambio cada vez.
