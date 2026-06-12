# ElgatoRecorder — Documentación técnica

App Android que captura vídeo desde una Elgato HD60 X via USB/UVC con pipeline MediaCodec propia.

---

## Bugs

| Archivo | Severidad | Esfuerzo |
|---|---|---|
| [BUG-01 — Condición imposible switch 4K](bugs/BUG-01-condicion-imposible-switch-4k.md) | CRÍTICO | 1h |
| [BUG-02 — Sin audio en grabaciones](bugs/BUG-02-sin-audio-grabaciones.md) | ALTO (blocker) | 1-2 días |
| [BUG-03 — Path grabación ignorado](bugs/BUG-03-path-grabacion-ignorado.md) | ALTO | 30 min |
| [BUG-04 — Race condition USB attach](bugs/BUG-04-race-condition-usb-attach.md) | ALTO | 1h |
| [BUG-05 — Receptores USB no registrados](bugs/BUG-05-receptores-usb-no-registrados.md) | ALTO | 30 min |
| [BUG-06 — Reflexión AUSBC frágil](bugs/BUG-06-reflexion-ausbc-fragil.md) | ALTO | 1-2 días |
| [BUG-07 — NV21→NV12 off-by-one](bugs/BUG-07-nv21-nv12-off-by-one.md) | MEDIO | 30 min |
| [BUG-08 — createCaptureSession deprecado](bugs/BUG-08-createCaptureSession-deprecado.md) | MEDIO | 1h |
| [BUG-09 — Timestamps inconsistentes](bugs/BUG-09-timestamps-inconsistentes-framequeue.md) | MEDIO | 2h |
| [BUG-10 — Sin persistencia resolución](bugs/BUG-10-sin-persistencia-resolucion.md) | BAJO | 1h |

---

## Mejoras

| Archivo | Tipo | Prioridad | Esfuerzo |
|---|---|---|---|
| [MEJORA-01 — Audio AAC VideoRecorder](mejoras/MEJORA-01-audio-aac-videorecorder.md) | Backend | CRÍTICA | 1-2 días |
| [MEJORA-02 — Registrar receptores USB](mejoras/MEJORA-02-registrar-receptores-usb.md) | Backend | CRÍTICA | 30 min |
| [MEJORA-03 — Soporte HEVC H.265](mejoras/MEJORA-03-soporte-hevc.md) | Backend | Alta | 2h |
| [MEJORA-04 — Bitrate configurable](mejoras/MEJORA-04-bitrate-configurable.md) | Backend+UI | Media | 2h |
| [MEJORA-05 — Timestamps por frame](mejoras/MEJORA-05-timestamps-por-frame.md) | Backend | Media | 2h |
| [MEJORA-06 — Indicador señal HDMI](mejoras/MEJORA-06-indicador-senal-hdmi.md) | Frontend | Alta | 2h |
| [MEJORA-07 — Advertencia espacio disco](mejoras/MEJORA-07-advertencia-espacio-disco.md) | Frontend | Alta | 1h |
| [MEJORA-08 — VU meter audio](mejoras/MEJORA-08-vu-meter-audio.md) | Frontend | CRÍTICA | 2h |
| [MEJORA-09 — Persistencia resolución](mejoras/MEJORA-09-selector-resolucion-persistencia.md) | Backend | Alta | 1h |
