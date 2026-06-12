# MEJORA-09 — Persistencia de resolución seleccionada

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Backend |
| Esfuerzo estimado | 1 hora |

---

## Descripción

Guardar la resolución seleccionada entre sesiones con DataStore. Ver implementación completa en [BUG-10](../bugs/BUG-10-sin-persistencia-resolucion.md).

---

## Resumen de cambios

1. Añadir `androidx.datastore:datastore-preferences:1.1.1`.
2. En `selectResolution()`: guardar `(width, height, fps)` en DataStore.
3. En `onAvailableResolutions()`: restaurar la resolución guardada si sigue disponible.
4. Fallback: `1080p60` si la resolución guardada no está disponible.

Ver código completo en [BUG-10](../bugs/BUG-10-sin-persistencia-resolucion.md).
