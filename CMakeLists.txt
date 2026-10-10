cmake_minimum_required(VERSION 3.21)
project(cobble LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "Cobble Recode targets Windows x64 only")
endif()
if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Cobble Recode targets x64 only; configure with -A x64")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(COLD_JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "JDK 17+ providing javac, jni.h and jvmti.h")
if(NOT EXISTS "${COLD_JAVA_HOME}/include/jni.h")
    message(FATAL_ERROR "COLD_JAVA_HOME does not contain include/jni.h: ${COLD_JAVA_HOME}")
endif()

# Public local-only build: no licence wall, no auth server, no HWID binding,
# no debugger checks. Everything is stored locally under %APPDATA%\\cobble.
set(COLD_DIST_DIR "${CMAKE_SOURCE_DIR}/dist")

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${COLD_DIST_DIR}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${COLD_DIST_DIR}")
foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${config} "${COLD_DIST_DIR}")
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${config} "${COLD_DIST_DIR}")
endforeach()

# ------------------------------------------------------------------- jars ----
set(COLD_LIB_DIR "${CMAKE_SOURCE_DIR}/infra/lib")

function(cold_fetch_jar url algorithm hash)
    get_filename_component(name "${url}" NAME)
    file(DOWNLOAD "${url}" "${COLD_LIB_DIR}/${name}" EXPECTED_HASH ${algorithm}=${hash} TLS_VERIFY ON)
endfunction()

set(COLD_MAVEN "https://repo1.maven.org/maven2")
set(COLD_MOJANG "https://libraries.minecraft.net")
set(COLD_FABRIC "https://maven.fabricmc.net")

# Bundled into the client image.
cold_fetch_jar("${COLD_MAVEN}/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
    SHA256 8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281)
cold_fetch_jar("${COLD_MAVEN}/org/ow2/asm/asm-tree/9.7.1/asm-tree-9.7.1.jar"
    SHA256 9929881f59eb6b840e86d54570c77b59ce721d104e6dfd7a40978991c2d3b41f)
cold_fetch_jar("${COLD_MAVEN}/org/luaj/luaj-jse/3.0.1/luaj-jse-3.0.1.jar"
    SHA256 9b1f0a3e8f68427c6d74c2bf00ae0e6dbfce35994d3001fed4cef6ecda50be55)
# Build tools only.
cold_fetch_jar("${COLD_MAVEN}/org/ow2/asm/asm-commons/9.7.1/asm-commons-9.7.1.jar"
    SHA256 9a579b54d292ad9be171d4313fd4739c635592c2b5ac3a459bbd1049cddec6a0)
cold_fetch_jar("${COLD_MAVEN}/org/ow2/asm/asm-analysis/9.7.1/asm-analysis-9.7.1.jar"
    SHA256 85b29371884ba31bb76edf22323c2c24e172c3267a67152eba3d1ccc2e041ef2)
cold_fetch_jar("${COLD_FABRIC}/net/fabricmc/yarn/1.21.11+build.6/yarn-1.21.11+build.6-v2.jar"
    SHA256 e8112359716235dc4fd7f0bd4a6162fd728e0d1067d9fa02f289edaaccd37718)
# What CheatBreaker's names are recovered against: vanilla's client and MCP's names for it.
set(COLD_VANILLA_1_8 "${COLD_LIB_DIR}/minecraft-1.8.9-client.jar")
file(DOWNLOAD "https://launcher.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar"
    "${COLD_VANILLA_1_8}" EXPECTED_HASH SHA1=3870888a6c3d349d3771a3e9d16c9bf5e076b908 TLS_VERIFY ON)
set(COLD_FORGE "https://maven.minecraftforge.net")
cold_fetch_jar("${COLD_FORGE}/de/oceanlabs/mcp/mcp/1.8.9/mcp-1.8.9-srg.zip"
    SHA1 7612cfa7e48787ce5aa989c6c513807d18b31a36)
cold_fetch_jar("${COLD_FORGE}/de/oceanlabs/mcp/mcp_stable/22-1.8.9/mcp_stable-22-1.8.9.zip"
    SHA1 a0ac11737e5e8100f91c65b66da02b1fbbf08809)

# The libraries each version's game runs with, where Lunar's bake does not
# carry them. Hashes are the ones in Mojang's version manifests.
set(COLD_LEGACY_LIBS "")
foreach(entry
        "org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar|697517568c68e78ae0b4544145af031c81082dfe"
        "org/lwjgl/lwjgl/lwjgl_util/2.9.4-nightly-20150209/lwjgl_util-2.9.4-nightly-20150209.jar|d51a7c040a721d13efdfbd34f8b257b2df882ad0"
        "com/google/code/gson/gson/2.2.4/gson-2.2.4.jar|a60a5e993c98c864010053cb901b7eab25306568"
        "com/mojang/authlib/1.5.21/authlib-1.5.21.jar|aefba0d5b53fbcb70860bc8046ab95d5854c07a5"
        "org/apache/logging/log4j/log4j-api/2.0-beta9/log4j-api-2.0-beta9.jar|1dd66e68cccd907880229f9e2de1314bd13ff785")
    string(REPLACE "|" ";" parts "${entry}")
    list(GET parts 0 path)
    list(GET parts 1 sha1)
    cold_fetch_jar("${COLD_MOJANG}/${path}" SHA1 ${sha1})
    get_filename_component(name "${path}" NAME)
    list(APPEND COLD_LEGACY_LIBS "${COLD_LIB_DIR}/${name}")
endforeach()

cold_fetch_jar("${COLD_MOJANG}/com/google/guava/guava/17.0/guava-17.0.jar"
    SHA1 9c6ef172e8de35fd8d4d8783e4821e57cdef7445)
cold_fetch_jar("${COLD_MOJANG}/io/netty/netty-all/4.0.23.Final/netty-all-4.0.23.Final.jar"
    SHA1 0294104aaf1781d6a56a07d561e792c5d0c95f45)
set(COLD_1_8_LIBS ${COLD_LEGACY_LIBS} "${COLD_LIB_DIR}/guava-17.0.jar" "${COLD_LIB_DIR}/netty-all-4.0.23.Final.jar")

set(COLD_1_21_LIBS "")
foreach(entry
        "com/google/code/gson/gson/2.13.2/gson-2.13.2.jar|48b8230771e573b54ce6e867a9001e75977fe78e"
        "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar|aeaffd00d57023a2c947393ed251f0354f0985fc"
        "com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.jar|8699de25f2f979108d6c1b804a7ba38cda1116bc"
        "com/mojang/authlib/7.0.61/authlib-7.0.61.jar|efee1e6b54e863108576eb3b3ae71144626aaefc"
        "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar|d15b53a14cf20fdcaa98f731af5dda654452c010"
        "com/mojang/datafixerupper/9.0.19/datafixerupper-9.0.19.jar|4e91f9712fa1e83231d1501625381b0210a977da"
        "io/netty/netty-buffer/4.2.7.Final/netty-buffer-4.2.7.Final.jar|5555ff561643bf2f8430fb57c24403c0efe15994"
        "io/netty/netty-codec-base/4.2.7.Final/netty-codec-base-4.2.7.Final.jar|915e381ebabcf115f1c7ff7032d55c48afb50210"
        "io/netty/netty-common/4.2.7.Final/netty-common-4.2.7.Final.jar|11aa30df26af4fca3239ac1917f303a280f301e1"
        "io/netty/netty-handler/4.2.7.Final/netty-handler-4.2.7.Final.jar|7ad8a1f851e2e6fe93cdd091871fda2b81c03b5b"
        "io/netty/netty-transport/4.2.7.Final/netty-transport-4.2.7.Final.jar|83ea548981d0d8c4a98027cc1a6f9624f902e142"
        "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar|a6cff377eecc19c2037bf31568a6d7106b50ba1f"
        "org/apache/commons/commons-lang3/3.19.0/commons-lang3-3.19.0.jar|d6524b169a6574cd253760c472d419b47bfd37e6"
        "org/apache/logging/log4j/log4j-api/2.25.2/log4j-api-2.25.2.jar|292c1a2b1702f1e1e3adb13e1c57e5bff60335ff"
        "org/joml/joml/1.10.8/joml-1.10.8.jar|fc0a71dad90a2cf41d82a76156a0e700af8e4f8d"
        "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar|7425a601c1c7ec76645a78d22b8c6a627edee507"
        "org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar|efa1eb78c5ccd840e9f329717109b5e892d72f8e"
        "org/lwjgl/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3.jar|02f6b0147078396a58979125a4c947664e98293a"
        "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar|29589b5f87ed335a6c7e7ee6a5775f81f97ecb84"
        "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar|d9e58ac9c7779ba3bf8142aff6c830617a7fe60f")
    string(REPLACE "|" ";" parts "${entry}")
    list(GET parts 0 path)
    list(GET parts 1 sha1)
    cold_fetch_jar("${COLD_MOJANG}/${path}" SHA1 ${sha1})
    get_filename_component(name "${path}" NAME)
    list(APPEND COLD_1_21_LIBS "${COLD_LIB_DIR}/${name}")
endforeach()

# ------------------------------------------------------------- game jars ----
set(COLD_JAVA "${COLD_JAVA_HOME}/bin/java.exe")
set(COLD_JAVAC "${COLD_JAVA_HOME}/bin/javac.exe")
set(COLD_JAVA_DIR "${CMAKE_CURRENT_BINARY_DIR}/java")
set(COLD_GAME_DIR "${COLD_JAVA_DIR}/game")
set(COLD_YARN "${COLD_LIB_DIR}/yarn-1.21.11+build.6-v2.jar")
set(COLD_ASM "${COLD_LIB_DIR}/asm-9.7.1.jar;${COLD_LIB_DIR}/asm-commons-9.7.1.jar;${COLD_LIB_DIR}/asm-tree-9.7.1.jar"
    "${COLD_LIB_DIR}/asm-analysis-9.7.1.jar")

# The classes Lunar baked for each version are what the client compiles and
# is checked against: the game as it runs, Lunar's own jars beside it.
set(COLD_LUNAR_DIR "$ENV{USERPROFILE}/.lunarclient/offline/multiver" CACHE PATH
    "Lunar Client's multiver folder, holding its jars and its cache of baked game classes")
file(TO_CMAKE_PATH "${COLD_LUNAR_DIR}" COLD_LUNAR_DIR)
if(NOT EXISTS "${COLD_LUNAR_DIR}/lunar.jar")
    message(FATAL_ERROR "no Lunar Client install at ${COLD_LUNAR_DIR}; pass -DCOLD_LUNAR_DIR=<multiver folder>")
endif()
set(COLD_LUNAR_JARS "${COLD_LUNAR_DIR}/lunar.jar" "${COLD_LUNAR_DIR}/common-0.1.0-SNAPSHOT-all-nomappings.jar")
set(COLD_LUNAR_LEGACY_JARS ${COLD_LUNAR_JARS} "${COLD_LUNAR_DIR}/legacy-0.1.0-SNAPSHOT-all-nomappings.jar")
set(COLD_LUNAR_MODERN_JARS ${COLD_LUNAR_JARS} "${COLD_LUNAR_DIR}/modern-0.1.0-SNAPSHOT-all-nomappings.jar")

set(COLD_GAME_1_8 "${COLD_GAME_DIR}/minecraft-1.8.9.jar")
set(COLD_GAME_1_21 "${COLD_GAME_DIR}/minecraft-1.21.11.jar")
set(COLD_GAME_1_21_NAMED "${COLD_GAME_DIR}/minecraft-1.21.11-named.jar")
# Runs every build; the jars are only rewritten when Lunar has baked new classes.
add_custom_target(cobble-game-jars
    COMMAND "${COLD_JAVA}" "${CMAKE_SOURCE_DIR}/tools/GameJars.java"
        "${COLD_LUNAR_DIR}/cache" "${COLD_YARN}" "${COLD_GAME_DIR}"
    BYPRODUCTS "${COLD_GAME_1_8}" "${COLD_GAME_1_21}"
    COMMENT "picking the game classes Lunar baked"
    VERBATIM
)
# CheatBreaker runs its own build of 1.8.9 with every Minecraft name replaced;
# the client runs there under the names tools/CbMap.java recovers from it.
set(COLD_CB_DIR "$ENV{APPDATA}/CheatBreaker/downloads" CACHE PATH
    "CheatBreaker's downloads folder, holding its versions and libraries")
file(TO_CMAKE_PATH "${COLD_CB_DIR}" COLD_CB_DIR)
set(COLD_CB_GAME "${COLD_CB_DIR}/versions/1.8.9/1.8.9.patch")
set(COLD_CB_VERSION "${COLD_CB_DIR}/versions/1.8.9/1.8.9.json")
if(NOT EXISTS "${COLD_CB_GAME}" OR NOT EXISTS "${COLD_CB_VERSION}")
    message(FATAL_ERROR "no CheatBreaker 1.8.9 under ${COLD_CB_DIR}; launch 1.8.9 from CheatBreaker once "
        "or pass -DCOLD_CB_DIR=<downloads folder>")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${COLD_CB_VERSION}")
# Its libraries, the ones its launcher downloaded for this machine.
file(READ "${COLD_CB_VERSION}" COLD_CB_JSON)
string(JSON COLD_CB_LIBRARY_COUNT LENGTH "${COLD_CB_JSON}" libraries)
math(EXPR COLD_CB_LAST_LIBRARY "${COLD_CB_LIBRARY_COUNT} - 1")
set(COLD_CB_LIBS "")
foreach(index RANGE ${COLD_CB_LAST_LIBRARY})
    string(JSON path ERROR_VARIABLE no_artifact GET "${COLD_CB_JSON}" libraries ${index} downloads artifact path)
    if(NOT no_artifact AND EXISTS "${COLD_CB_DIR}/libraries/${path}")
        list(APPEND COLD_CB_LIBS "${COLD_CB_DIR}/libraries/${path}")
    endif()
endforeach()

set(COLD_CB_NAMES_DIR "${COLD_JAVA_DIR}/cheatbreaker")
set(COLD_CB_NAMES "${COLD_CB_NAMES_DIR}/names.tsv")
set(COLD_CB_HEADER "${COLD_CB_NAMES_DIR}/CheatBreakerNames.h")
add_custom_command(
    OUTPUT "${COLD_CB_NAMES}" "${COLD_CB_HEADER}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${COLD_CB_NAMES_DIR}"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/CbMap.java"
        "${COLD_VANILLA_1_8}" "${COLD_CB_GAME}" "${COLD_LIB_DIR}/mcp-1.8.9-srg.zip"
        "${COLD_LIB_DIR}/mcp_stable-22-1.8.9.zip" "${COLD_CB_NAMES}" "${COLD_CB_HEADER}"
    DEPENDS "${COLD_CB_GAME}" "${COLD_VANILLA_1_8}" "${CMAKE_SOURCE_DIR}/tools/CbMap.java"
    COMMENT "naming CheatBreaker 1.8.9's classes after MCP"
    VERBATIM
)

add_custom_command(
    OUTPUT "${COLD_GAME_1_21_NAMED}"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
        named "${COLD_YARN}" "${COLD_GAME_1_21}" "${COLD_GAME_1_21_NAMED}"
    DEPENDS "${COLD_GAME_1_21}" "${COLD_YARN}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
    COMMENT "naming the 1.21.11 classes after Yarn"
    VERBATIM
)

# ------------------------------------------------------------ java client ----
set(COLD_MEMORY_LOADER_JAVA "${CMAKE_SOURCE_DIR}/bootstrap/MemoryLoader.java")
set(COLD_MEMORY_LOADER_CLASS "${COLD_JAVA_DIR}/loader/cobble/boot/MemoryLoader.class")
add_custom_command(
    OUTPUT "${COLD_MEMORY_LOADER_CLASS}"
    COMMAND "${COLD_JAVAC}" --release 17 -encoding UTF-8 -d "${COLD_JAVA_DIR}/loader"
        "${COLD_MEMORY_LOADER_JAVA}"
    DEPENDS "${COLD_MEMORY_LOADER_JAVA}"
    COMMENT "compiling MemoryLoader"
    VERBATIM
)

set(COLD_CLASSES_DIR "${COLD_JAVA_DIR}/classes")
set(COLD_IMAGE_DIR "${COLD_JAVA_DIR}/image")
set(COLD_IMAGE_ZIP "${COLD_JAVA_DIR}/image.zip")
set(COLD_BUNDLED_JARS
    "${COLD_LIB_DIR}/asm-9.7.1.jar" "${COLD_LIB_DIR}/asm-tree-9.7.1.jar" "${COLD_LIB_DIR}/luaj-jse-3.0.1.jar")

# One source set per row: what it may see, and nothing else. common holds no
# Minecraft at all; legacy is written against 1.8.9 and rewritten for 1.21.11
# wherever it links; each version's own set compiles against that version.
# An argument file per set keeps the source lists clear of cmd.exe's
# 8191-character limit and is only rewritten when a source is added or removed.
function(cold_source_set name output classpath)
    file(GLOB_RECURSE sources CONFIGURE_DEPENDS "${CMAKE_SOURCE_DIR}/src/${name}/*.java")
    set(text "--release 17\n-encoding UTF-8\n-nowarn\n-cp \"${classpath}\"\n-d \"${output}\"\n")
    foreach(source IN LISTS sources)
        string(APPEND text "\"${source}\"\n")
    endforeach()
    file(CONFIGURE OUTPUT "${COLD_JAVA_DIR}/${name}.args" CONTENT "${text}" @ONLY)
    set(COLD_SOURCES_${name} ${sources} PARENT_SCOPE)
endfunction()

set(COLD_OUT_COMMON "${COLD_CLASSES_DIR}/common")
set(COLD_OUT_LEGACY "${COLD_CLASSES_DIR}/legacy")
set(COLD_OUT_1_21_NAMED "${COLD_CLASSES_DIR}/v1_21-named")
set(COLD_OUT_1_21 "${COLD_CLASSES_DIR}/v1_21")
set(COLD_OUT_LEGACY_1_21_NAMED "${COLD_CLASSES_DIR}/legacy-1.21-named")
set(COLD_OUT_1_21_LEGACY_NAMED "${COLD_CLASSES_DIR}/v1_21_legacy-named")
set(COLD_OUT_CB "${COLD_CLASSES_DIR}/cb_1_8")
set(COLD_CB_OVERLAY "${COLD_IMAGE_DIR}/versions/cb-1.8.9")
cold_source_set(common "${COLD_OUT_COMMON}"
    "${COLD_BUNDLED_JARS};${COLD_LIB_DIR}/lwjgl-2.9.4-nightly-20150209.jar;${COLD_LIB_DIR}/gson-2.2.4.jar")
cold_source_set(legacy "${COLD_OUT_LEGACY}"
    "${COLD_OUT_COMMON};${COLD_BUNDLED_JARS};${COLD_GAME_1_8};${COLD_LUNAR_LEGACY_JARS};${COLD_1_8_LIBS}")
cold_source_set(v1_21 "${COLD_OUT_1_21_NAMED}"
    "${COLD_OUT_COMMON};${COLD_BUNDLED_JARS};${COLD_GAME_1_21_NAMED};${COLD_LUNAR_MODERN_JARS};${COLD_1_21_LIBS}")
# The 1.21 hooks that raise the legacy code's events, and 1.21 versions of
# legacy classes no rule can retarget, which need that code as rewritten for
# 1.21 and so compile after it. They go to the 1.21 overlay, after the
# retargeted classes they stand in for.
cold_source_set(v1_21_legacy "${COLD_OUT_1_21_LEGACY_NAMED}"
    "${COLD_OUT_LEGACY_1_21_NAMED};${COLD_OUT_LEGACY};${COLD_OUT_1_21_NAMED};${COLD_OUT_COMMON};${COLD_BUNDLED_JARS};${COLD_GAME_1_21_NAMED};${COLD_LUNAR_MODERN_JARS};${COLD_1_21_LIBS}")
# CheatBreaker's adapter, written against the legacy code under MCP names and
# rewritten with it into CheatBreaker's for that version's overlay.
cold_source_set(cb_1_8 "${COLD_OUT_CB}"
    "${COLD_OUT_LEGACY};${COLD_OUT_COMMON};${COLD_BUNDLED_JARS};${COLD_GAME_1_8};${COLD_LUNAR_LEGACY_JARS};${COLD_1_8_LIBS}")

# What each version's code is checked against: the game, then Lunar, then the libraries.
string(JOIN "," COLD_WORLD_1_8 "${COLD_GAME_1_8}" ${COLD_LUNAR_LEGACY_JARS} ${COLD_1_8_LIBS})
string(JOIN "," COLD_WORLD_1_21 "${COLD_GAME_1_21}" ${COLD_LUNAR_MODERN_JARS} ${COLD_1_21_LIBS})
string(JOIN "," COLD_WORLD_1_21_NAMED "${COLD_GAME_1_21_NAMED}" ${COLD_LUNAR_MODERN_JARS} ${COLD_1_21_LIBS}
    "${COLD_OUT_1_21_NAMED}" "${COLD_OUT_COMMON}")
string(JOIN "," COLD_WORLD_CB "${COLD_CB_GAME}" ${COLD_CB_LIBS})

file(GLOB_RECURSE COLD_ASSET_FILES CONFIGURE_DEPENDS "${CMAKE_SOURCE_DIR}/assets/*")

# Only cobble/, org/ and assets/ are packed: the jars' manifests, module
# descriptors and LuaJ's command-line tools have no use inside the game.
add_custom_command(
    OUTPUT "${COLD_IMAGE_ZIP}"
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "${COLD_CLASSES_DIR}" "${COLD_IMAGE_DIR}"
    COMMAND "${COLD_JAVAC}" "@${COLD_JAVA_DIR}/common.args"
    COMMAND "${COLD_JAVAC}" "@${COLD_JAVA_DIR}/legacy.args"
    COMMAND "${COLD_JAVAC}" "@${COLD_JAVA_DIR}/v1_21.args"
    COMMAND "${COLD_JAVAC}" "@${COLD_JAVA_DIR}/cb_1_8.args"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
        cheatbreaker "${COLD_CB_NAMES}" "${COLD_GAME_1_8}" "${COLD_OUT_LEGACY}" "${COLD_CB_OVERLAY}"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
        cheatbreaker "${COLD_CB_NAMES}" "${COLD_GAME_1_8}" "${COLD_OUT_CB}" "${COLD_CB_OVERLAY}"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
        hooks "${COLD_CB_NAMES}" "${COLD_GAME_1_8}" "${COLD_CB_GAME}" "${COLD_OUT_COMMON};${COLD_OUT_LEGACY};${COLD_OUT_CB}"
        cobble.cb.HookTable "${COLD_CB_OVERLAY}/cobble/cb/hooks.tsv"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Retarget.java"
        "${CMAKE_SOURCE_DIR}/src/v1_21/retarget.rules" "${COLD_WORLD_1_8}" "${COLD_WORLD_1_21_NAMED}"
        "${COLD_OUT_LEGACY}" "${COLD_OUT_LEGACY_1_21_NAMED}"
    COMMAND "${COLD_JAVAC}" "@${COLD_JAVA_DIR}/v1_21_legacy.args"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
        intermediary "${COLD_YARN}" "${COLD_GAME_1_21_NAMED}" "${COLD_OUT_1_21_NAMED}" "${COLD_OUT_1_21}"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
        intermediary "${COLD_YARN}" "${COLD_GAME_1_21_NAMED}" "${COLD_OUT_LEGACY_1_21_NAMED}"
        "${COLD_IMAGE_DIR}/versions/1.21.11"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Remap.java"
        intermediary "${COLD_YARN}" "${COLD_GAME_1_21_NAMED}" "${COLD_OUT_1_21_LEGACY_NAMED}"
        "${COLD_IMAGE_DIR}/versions/1.21.11"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${COLD_OUT_COMMON}" "${COLD_IMAGE_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${COLD_OUT_LEGACY}" "${COLD_IMAGE_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${COLD_OUT_1_21}" "${COLD_IMAGE_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${COLD_OUT_CB}" "${COLD_IMAGE_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E chdir "${COLD_IMAGE_DIR}"
        "${CMAKE_COMMAND}" -E tar xf "${COLD_LIB_DIR}/asm-9.7.1.jar"
    COMMAND "${CMAKE_COMMAND}" -E chdir "${COLD_IMAGE_DIR}"
        "${CMAKE_COMMAND}" -E tar xf "${COLD_LIB_DIR}/asm-tree-9.7.1.jar"
    COMMAND "${CMAKE_COMMAND}" -E chdir "${COLD_IMAGE_DIR}"
        "${CMAKE_COMMAND}" -E tar xf "${COLD_LIB_DIR}/luaj-jse-3.0.1.jar"
    COMMAND "${COLD_JAVA}" -cp "${COLD_ASM}" "${CMAKE_SOURCE_DIR}/tools/Linkage.java"
        "${COLD_IMAGE_DIR}" "${COLD_JAVA_DIR}/linkage"
        "1.8.9=v1_8=${COLD_WORLD_1_8}" "1.21.11=v1_21/legacy=${COLD_WORLD_1_21}" "cb-1.8.9=cb=${COLD_WORLD_CB}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory
        "${CMAKE_SOURCE_DIR}/assets" "${COLD_IMAGE_DIR}/assets"
    COMMAND "${CMAKE_COMMAND}" -E chdir "${COLD_IMAGE_DIR}"
        "${CMAKE_COMMAND}" -E tar cf "${COLD_IMAGE_ZIP}" --format=zip cobble org versions assets
    DEPENDS "${COLD_JAVA_DIR}/common.args" "${COLD_JAVA_DIR}/legacy.args"
        "${COLD_JAVA_DIR}/v1_21.args" "${COLD_JAVA_DIR}/v1_21_legacy.args" "${COLD_JAVA_DIR}/cb_1_8.args"
        ${COLD_SOURCES_common} ${COLD_SOURCES_legacy} ${COLD_SOURCES_v1_21}
        ${COLD_SOURCES_v1_21_legacy} ${COLD_SOURCES_cb_1_8} "${CMAKE_SOURCE_DIR}/src/v1_21/retarget.rules"
        "${COLD_GAME_1_8}" "${COLD_GAME_1_21}" "${COLD_GAME_1_21_NAMED}" "${COLD_CB_NAMES}" "${COLD_CB_GAME}"
        "${CMAKE_SOURCE_DIR}/tools/Remap.java" "${CMAKE_SOURCE_DIR}/tools/Linkage.java"
        "${CMAKE_SOURCE_DIR}/tools/Retarget.java"
        ${COLD_ASSET_FILES}
    COMMENT "compiling the client for every version and packing its image"
    VERBATIM
)

# -------------------------------------------------------------- bootstrap ----
set(COLD_BOOTSTRAP_RC "${CMAKE_CURRENT_BINARY_DIR}/bootstrap.rc")
configure_file(bootstrap/bootstrap.rc.in "${COLD_BOOTSTRAP_RC}" @ONLY)
set_source_files_properties("${COLD_BOOTSTRAP_RC}" PROPERTIES
    OBJECT_DEPENDS "${COLD_MEMORY_LOADER_CLASS};${COLD_IMAGE_ZIP}")

add_library(cobble SHARED
    bootstrap/Main.cpp
    "${COLD_BOOTSTRAP_RC}"
    "${COLD_MEMORY_LOADER_CLASS}"
    "${COLD_IMAGE_ZIP}"
    "${COLD_CB_HEADER}"
)
set_target_properties(cobble PROPERTIES OUTPUT_NAME "cobble" PREFIX "")
add_dependencies(cobble cobble-game-jars)
target_include_directories(cobble PRIVATE
    "${COLD_JAVA_HOME}/include"
    "${COLD_JAVA_HOME}/include/win32"
    "${COLD_CB_NAMES_DIR}"
)
target_link_libraries(cobble PRIVATE user32)
target_compile_definitions(cobble PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)
if(MSVC)
    target_compile_options(cobble PRIVATE /W4 /permissive- /utf-8
        $<$<CONFIG:Release>:/O2>)
endif()

# --------------------------------------------------------------- injector ----
# Single-file loader: the payload DLL and the example scripts are baked into
# the injector as RCDATA, so only one exe needs to be sent. At runtime the
# loader extracts them beside itself (temp dir fallback) and injects that.
set(COLD_PAYLOAD_DLL "${COLD_DIST_DIR}/cobble.dll")
file(GLOB COLD_SCRIPT_FILES CONFIGURE_DEPENDS "${CMAKE_SOURCE_DIR}/scripts/*.lua")
set(COLD_LOADER_RC "${CMAKE_CURRENT_BINARY_DIR}/injector.rc")
set(COLD_LOADER_HPP "${CMAKE_CURRENT_BINARY_DIR}/generated/EmbeddedScripts.generated.hpp")
set(COLD_LOADER_STAMP "${CMAKE_CURRENT_BINARY_DIR}/loader-embed.stamp")
add_custom_command(
    OUTPUT "${COLD_LOADER_RC}" "${COLD_LOADER_HPP}" "${COLD_LOADER_STAMP}"
    COMMAND "${CMAKE_COMMAND}"
        "-DCOLD_DLL=${COLD_PAYLOAD_DLL}"
        "-DCOLD_SCRIPTS_DIR=${CMAKE_SOURCE_DIR}/scripts"
        "-DCOLD_RC_IN=${CMAKE_SOURCE_DIR}/loader/injector.rc.in"
        "-DCOLD_RC_OUT=${COLD_LOADER_RC}"
        "-DCOLD_HPP_IN=${CMAKE_SOURCE_DIR}/loader/src/EmbeddedScripts.generated.hpp.in"
        "-DCOLD_HPP_OUT=${COLD_LOADER_HPP}"
        "-DCOLD_STAMP=${COLD_LOADER_STAMP}"
        -P "${CMAKE_SOURCE_DIR}/loader/ConfigureLoader.cmake"
    DEPENDS "${COLD_PAYLOAD_DLL}" ${COLD_SCRIPT_FILES}
    COMMENT "embedding payload + scripts into the loader"
    VERBATIM
)

set(COLD_IMGUI_DIR "${CMAKE_SOURCE_DIR}/infra/ext/imgui")
add_library(imgui STATIC
    "${COLD_IMGUI_DIR}/imgui.cpp"
    "${COLD_IMGUI_DIR}/imgui_draw.cpp"
    "${COLD_IMGUI_DIR}/imgui_tables.cpp"
    "${COLD_IMGUI_DIR}/imgui_widgets.cpp"
    "${COLD_IMGUI_DIR}/imgui_impl_dx11.cpp"
    "${COLD_IMGUI_DIR}/imgui_impl_win32.cpp"
)
target_include_directories(imgui PUBLIC "${COLD_IMGUI_DIR}")
target_link_libraries(imgui PUBLIC d3d11 d3dcompiler dwmapi)

add_executable(cobble-injector WIN32
    loader/src/Main.cpp
    loader/src/LoaderUi.cpp
    loader/src/Inject.cpp
    loader/src/ProcessList.cpp
    loader/src/Embedded.cpp
    "${COLD_LOADER_RC}"
)
target_include_directories(cobble-injector PRIVATE
    loader/src "${CMAKE_CURRENT_BINARY_DIR}/generated")
target_link_libraries(cobble-injector PRIVATE imgui user32 gdi32)
target_compile_definitions(cobble-injector PRIVATE
    WIN32_LEAN_AND_MEAN NOMINMAX _CRT_SECURE_NO_WARNINGS UNICODE _UNICODE)
if(MSVC)
    target_compile_options(cobble-injector PRIVATE /W4 /permissive- /utf-8
        $<$<CONFIG:Release>:/O2>)
endif()
add_dependencies(cobble-injector cobble)

# The example scripts are repository content, not user data: the client takes
# them from beside the DLL on first run, so a build puts the current ones there.
add_custom_command(TARGET cobble POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        "${CMAKE_SOURCE_DIR}/scripts" "${COLD_DIST_DIR}/scripts"
    COMMENT "copying scripts/ to dist")
