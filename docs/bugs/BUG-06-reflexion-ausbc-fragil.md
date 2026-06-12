# BUG-06 — Switch 4K por reflexión frágil (campo `mUVCCamera`)

| Campo | Valor |
|---|---|
| Severidad | ALTO |
| Archivo | `ElgatoCameraFragment.kt` |
| Método | `findUVCCamera()` |
| Esfuerzo estimado | 1-2 días |

---

## Descripción

El método `findUVCCamera()` busca el campo privado `mUVCCamera` en la jerarquía de clases de `CameraUvcStrategy` mediante reflexión:

```kotlin
private fun findUVCCamera(strategy: Any): Any? {
    var cls: Class<*>? = strategy.javaClass
    while (cls != null) {
        try {
            val f = cls.getDeclaredField("mUVCCamera")
            f.isAccessible = true
            val cam = f.get(strategy)
            if (cam != null) return cam
        } catch (_: NoSuchFieldException) {}
        cls = cls.superclass
    }
    return null
}
```

El nombre `mUVCCamera` es un campo **privado interno** de la biblioteca AUSBC. Cualquier actualización de AUSBC puede renombrarlo (`mCamera`, `uvcCamera`, `camera`, etc.) y el switch 4K dejará de funcionar silenciosamente (retorna `null` → reporta "4K: acceso nativo no disponible").

---

## Causa raíz

No existe una API pública en AUSBC 3.2.7 para cambiar la resolución/bandwidth de una cámara UVC ya abierta. El acceso por reflexión es el único workaround conocido.

---

## Impacto

- Una actualización de AUSBC puede romper el switch 4K sin advertencia.
- La app reporta al usuario "4K: acceso nativo no disponible" cuando en realidad el HD60 X tampoco soporta 4K USB (con lo que el impacto funcional es bajo para este hardware específico).

---

## Solución paso a paso

### Opción A — Intentar múltiples nombres de campo (más robusto)

Buscar varios nombres candidatos para el campo UVCCamera:

```kotlin
private val UVC_CAMERA_FIELD_NAMES = listOf(
    "mUVCCamera",
    "uvcCamera",
    "mCamera",
    "camera",
    "mCameraHelper",
)

private fun findUVCCamera(strategy: Any): Any? {
    var cls: Class<*>? = strategy.javaClass
    while (cls != null) {
        for (fieldName in UVC_CAMERA_FIELD_NAMES) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                val cam = f.get(strategy)
                // Verificar que es un objeto UVCCamera válido buscando el método setPreviewSize
                if (cam != null && hasSetPreviewSizeMethod(cam)) {
                    Log.d(TAG, "UVCCamera encontrado via campo '$fieldName' en ${cls.simpleName}")
                    return cam
                }
            } catch (_: NoSuchFieldException) {}
        }
        cls = cls.superclass
    }
    return null
}

private fun hasSetPreviewSizeMethod(obj: Any): Boolean {
    return obj.javaClass.methods.any { it.name == "setPreviewSize" }
}
```

### Opción B — Buscar por tipo en lugar de nombre (más resistente a renombrados)

```kotlin
private fun findUVCCamera(strategy: Any): Any? {
    var cls: Class<*>? = strategy.javaClass
    while (cls != null) {
        for (field in cls.declaredFields) {
            field.isAccessible = true
            val value = runCatching { field.get(strategy) }.getOrNull() ?: continue
            // Si el objeto tiene setPreviewSize y startPreview → es el UVCCamera
            val methods = value.javaClass.methods.map { it.name }.toSet()
            if ("setPreviewSize" in methods && "startPreview" in methods) {
                Log.d(TAG, "UVCCamera encontrado via tipo: ${value.javaClass.simpleName}")
                return value
            }
        }
        cls = cls.superclass
    }
    return null
}
```

### Opción C — Documentar y versionar la dependencia AUSBC

Si ninguna opción es satisfactoria, al menos documentar la versión de AUSBC requerida y bloquear actualizaciones:

```kotlin
// En build.gradle.kts:
// IMPORTANTE: mUVCCamera es un campo privado de AUSBC 3.2.7
// Verificar compatibilidad antes de actualizar
implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7")

// En libs.versions.toml:
ausbc = "3.2.7"  // NO actualizar sin verificar findUVCCamera()
```

---

## Tests sugeridos

- Seleccionar 4K → debería mostrar el error correcto (HD60 X no soporta 4K USB).
- Actualizar AUSBC a una versión mayor → verificar que `findUVCCamera()` sigue devolviendo un objeto no-null.
- `findUVCCamera(strategy)` devuelve null → el error "4K: acceso nativo no disponible" debe mostrarse.
