# Receptor DeX

Receptor Miracast / Wi-Fi Display para Android TV (probado en Philips 55OLED718/12, Android 14) que permite
usar **Samsung DeX inalámbrico** (probado con un Galaxy S23 Ultra). Vídeo H.264 1080p y audio LPCM.

> **Versión 1.0, experimental.** Un solo móvil y una sola tele probados. Estado detallado en
> [REQUIREMENTS.md](REQUIREMENTS.md), arquitectura en [SPEC.md](SPEC.md), licencias en [NOTICE.md](NOTICE.md).

## Manual de uso

**Requisitos:** tele Android TV con depuración USB/por red activada, y móvil Samsung con DeX.

1. Instala el APK (`adb connect IP:5555` y `adb install -r ReceptorDeX-v1.0.apk`).
2. Abre **Receptor DeX**. Concede los permisos (ubicación → "Permitir siempre" en Ajustes) y, la primera
   vez, acepta en la tele la clave ADB ("Permitir siempre desde este ordenador"; el foco empieza en
   "Cancelar", muévelo a "Permitir"). La app arranca Shizuku sola.
3. En el móvil: ajustes rápidos → **DeX** → elige la tele.
4. En la tele, acepta al móvil: **Aceptar / Aceptar siempre / Rechazar**. Los de confianza se guardan;
   la tecla **Menú** los olvida.
5. Para terminar, corta desde DeX en el móvil. La app sigue anunciándose en segundo plano.

**Mando (experimental, puede que el móvil no responda):**

| Botón | Acción |
|---|---|
| Flechas | Mover puntero |
| OK | Clic |
| Atrás (mantener) | Atrás (Esc) |
| Verde / Amarillo | Scroll arriba / abajo |
| Rojo / Azul | Zoom − / + |

**Si falla la conexión:** cierra DeX en el móvil, apaga y enciende su Wi-Fi y reintenta.

## Limitaciones conocidas

Latencia del cursor ~1 s; mando sin verificar salvo la tecla Espacio; arranque al encender la tele,
TEARDOWN/PAUSE/PLAY y otros móviles sin probar; canal de entrada UIBC sin cifrar (úsalo en red doméstica).
`targetSdk 30` es deliberado (ruta de permisos P2P previa a Android 13), así que no es apta para Google Play.

## Compilar

`./gradlew assembleRelease` (JDK 17, Android SDK). El APK de release se firma con la clave de depuración;
firma con la tuya para distribuirlo.

## Cómo ayudar

Ver [CONTRIBUTING.md](CONTRIBUTING.md) y los issues con `help wanted`. Los informes de prueba con otros móviles/teles son muy útiles.

## Licencia

Apache-2.0. Ver [LICENSE](LICENSE) y [NOTICE.md](NOTICE.md).
