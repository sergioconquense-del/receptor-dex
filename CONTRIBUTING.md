# Cómo ayudar

¡Gracias! El proyecto es una v1.0 experimental y necesita ayuda sobre todo en estos puntos
(ver los issues con la etiqueta `help wanted`):

1. **Mando de la tele como ratón/teclado**: solo la tecla Espacio llega a DeX. Falta la tabla de códigos UIBC que acepta.
2. **Latencia del cursor (~1 s)**: buffer de Media3 y del audio.
3. **Probar con otros móviles y teles** y contar qué pasa (plantilla "Informe de prueba").
4. **Arranque al encender la tele, TEARDOWN/PAUSE/PLAY, cambio de resolución**: implementados, sin probar.

## Antes de enviar un cambio
- Lee [SPEC.md](SPEC.md) (arquitectura) y [REQUIREMENTS.md](REQUIREMENTS.md) (estado).
- Un cambio por pull request, y cuenta cómo lo probaste (modelo de móvil y de tele).
- **Licencias:** el proyecto es Apache-2.0. No pegues código GPL/AGPL ni de licencia incompatible (p. ej. Moonlight es GPL-3.0).
  Si te basas en otro proyecto, cítalo en `NOTICE.md`.
- Compila con `./gradlew assembleDebug`.
