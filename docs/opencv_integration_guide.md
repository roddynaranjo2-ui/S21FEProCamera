# Guía de Integración de OpenCV en Android con CMake

## Resumen de la Solución (Manual/Advanced Way)

Esta guía se basa en la respuesta de [ahasbini en Stack Overflow](https://stackoverflow.com/a/43886764).

### 1. Crear un Módulo de Librería para OpenCV

1.  **Crear Nuevo Módulo**: `File > New > New Module...`
2.  **Seleccionar "Android Library"**:
    *   **Library name**: `OpenCV`
    *   **Module name**: `opencv`
    *   **Package name**: `org.opencv`

### 2. Copiar los Archivos del SDK de OpenCV

1.  **Código Java**: Copiar el contenido de `path_to_opencv_sdk/sdk/java/src` a `path_to_your_project/opencv/src/main/java`.
2.  **AIDL**: Crear el directorio `path_to_your_project/opencv/src/main/aidl/org/opencv/engine` y mover `main/java/org/opencv/engine/OpenCVEngineInterface.aidl` a este nuevo directorio.
3.  **Recursos**: Copiar el contenido de `path_to_opencv_sdk/sdk/java/res` a `path_to_your_project/opencv/src/main/res`.
4.  **Librerías Nativas**: Crear la carpeta `sdk` dentro de `path_to_your_project/opencv/src/` y copiar la carpeta `path_to_opencv_sdk/sdk/native` dentro de `sdk`.

### 3. Configurar CMake para el Módulo `opencv`

Crear un archivo `CMakeLists.txt` dentro del módulo `opencv` con el siguiente contenido:

```cmake
cmake_minimum_required(VERSION 3.4.1)
set(OpenCV_DIR "src/sdk/native/jni")
find_package(OpenCV REQUIRED)
message(STATUS "OpenCV libraries: ${OpenCV_LIBS}")
include_directories(${OpenCV_INCLUDE_DIRS})
```

### 4. Configurar `build.gradle` para el Módulo `opencv`

Editar el archivo `build.gradle` del módulo `opencv`:

```gradle
android {
    // ...

    defaultConfig {
        minSdkVersion 8
        targetSdkVersion 25
        versionCode 3200
        versionName "3.2.0"

        externalNativeBuild {
            cmake {
                cppFlags "-frtti -fexceptions"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path "CMakeLists.txt"
        }
    }

    sourceSets {
        main {
            jni.srcDirs = [jni.srcDirs, 'src/sdk/native/jni/include']
            jniLibs.srcDirs = [jniLibs.srcDirs, 'src/sdk/native/3rdparty/libs', 'src/sdk/native/libs']
        }
    }
}
```

### 5. Configurar CMake para el Módulo `app`

Editar el `CMakeLists.txt` del módulo `app` para encontrar y enlazar OpenCV:

```cmake
set(OpenCV_DIR "../opencv/src/sdk/native/jni")
find_package(OpenCV REQUIRED)
message(STATUS "OpenCV libraries: ${OpenCV_LIBS}")
target_link_libraries(YOUR_TARGET_LIB ${OpenCV_LIBS})
```

Reemplazar `YOUR_TARGET_LIB` con el nombre de la librería nativa de la aplicación (en nuestro caso, `procamera`).

### 6. Añadir Dependencia del Módulo

En el `build.gradle` del módulo `app`, añadir la dependencia del módulo `opencv`:

```gradle
dependencies {
    // ...
    implementation project(':opencv')
}
```

### 7. Sincronizar Gradle

Finalmente, sincronizar el proyecto con los archivos de Gradle.
