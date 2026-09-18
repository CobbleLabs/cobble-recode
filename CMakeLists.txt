cmake_minimum_required(VERSION 3.21)
project(cobble LANGUAGES C CXX)

if(NOT WIN32)
    message(FATAL_ERROR "Cobble Recode targets Windows x64 only")
endif()
if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Cobble Recode targets x64 only; configure with -A x64")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(COLD_JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "JDK providing jni.h and jvmti.h")
if(NOT EXISTS "${COLD_JAVA_HOME}/include/jni.h")
    message(FATAL_ERROR "COLD_JAVA_HOME does not contain include/jni.h: ${COLD_JAVA_HOME}")
endif()

# Public local-only build: no licence wall, no auth server, no HWID binding,
# no debugger checks. Everything is stored locally under %APPDATA%\\cobble.
set(COLD_DIST_DIR "${CMAKE_SOURCE_DIR}/dist")

set(COLD_ASSET_BLOB "${CMAKE_SOURCE_DIR}/build/assets/cobble.assets")
if(NOT EXISTS "${COLD_ASSET_BLOB}")
    message(FATAL_ERROR "missing asset blob, run: node tools/packassets.mjs")
endif()

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${COLD_DIST_DIR}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${COLD_DIST_DIR}")
foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${config} "${COLD_DIST_DIR}")
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${config} "${COLD_DIST_DIR}")
endforeach()

# ---------------------------------------------------------------- minhook ----
add_library(minhook STATIC
    payload/thirdparty/minhook/src/buffer.c
    payload/thirdparty/minhook/src/hook.c
    payload/thirdparty/minhook/src/trampoline.c
    payload/thirdparty/minhook/src/hde/hde64.c
)
target_include_directories(minhook PUBLIC payload/thirdparty/minhook/include)
target_compile_definitions(minhook PRIVATE _CRT_SECURE_NO_WARNINGS)
if(MSVC)
    target_compile_options(minhook PRIVATE /W3 /wd4152 /wd4213)
endif()

# -------------------------------------------------------------------- lua ----
# Built as C++ on purpose. Lua's error path is a longjmp in C, which would skip
# every destructor between lua_pcall and the C function that raised - and every
# binding in payload/src/script holds std::strings. Compiled as C++, ldo.c
# switches to throw/catch (see its LUAI_THROW block) and the same errors unwind
# cleanly.
set(COLD_LUA_SOURCES
    payload/thirdparty/lua/lapi.c
    payload/thirdparty/lua/lcode.c
    payload/thirdparty/lua/lctype.c
    payload/thirdparty/lua/ldebug.c
    payload/thirdparty/lua/ldo.c
    payload/thirdparty/lua/ldump.c
    payload/thirdparty/lua/lfunc.c
    payload/thirdparty/lua/lgc.c
    payload/thirdparty/lua/llex.c
    payload/thirdparty/lua/lmem.c
    payload/thirdparty/lua/lobject.c
    payload/thirdparty/lua/lopcodes.c
    payload/thirdparty/lua/lparser.c
    payload/thirdparty/lua/lstate.c
    payload/thirdparty/lua/lstring.c
    payload/thirdparty/lua/ltable.c
    payload/thirdparty/lua/ltm.c
    payload/thirdparty/lua/lundump.c
    payload/thirdparty/lua/lvm.c
    payload/thirdparty/lua/lzio.c
    payload/thirdparty/lua/lauxlib.c
    payload/thirdparty/lua/lbaselib.c
    payload/thirdparty/lua/lcorolib.c
    payload/thirdparty/lua/ldblib.c
    payload/thirdparty/lua/lmathlib.c
    payload/thirdparty/lua/loslib.c
    payload/thirdparty/lua/lstrlib.c
    payload/thirdparty/lua/ltablib.c
    payload/thirdparty/lua/lutf8lib.c
)
set_source_files_properties(${COLD_LUA_SOURCES} PROPERTIES LANGUAGE CXX)
add_library(lua STATIC ${COLD_LUA_SOURCES})
target_include_directories(lua PUBLIC payload/thirdparty/lua)
target_compile_definitions(lua PRIVATE _CRT_SECURE_NO_WARNINGS)
if(MSVC)
    target_compile_options(lua PRIVATE /W0)
endif()

# ---------------------------------------------------------------- payload ----
set(COLD_FLAVOUR_DEFINITIONS COLD_BUILD_DEV=1)
set(COLD_FLAVOUR_LABEL "public")
configure_file(payload/payload.rc.in "${CMAKE_CURRENT_BINARY_DIR}/payload.rc" @ONLY)

add_library(cobble SHARED
    payload/src/Main.cpp

    payload/src/core/Log.cpp
    payload/src/core/Config.cpp
    payload/src/core/TestHook.cpp
    payload/src/core/Net.cpp
    payload/src/core/Auth.cpp
    payload/src/core/Cloud.cpp

    payload/src/bridge/Jvm.cpp
    payload/src/bridge/Mappings.cpp
    payload/src/bridge/Mappings.generated.cpp
    payload/src/bridge/Minecraft.cpp

    payload/src/hooks/Hooks.cpp
    payload/src/hooks/Input.cpp
    payload/src/hooks/Window.cpp

    payload/src/render/Gl.cpp
    payload/src/render/Shader.cpp
    payload/src/render/Renderer.cpp
    payload/src/render/Font.cpp
    payload/src/render/IconAtlas.cpp
    payload/src/render/Backdrop.cpp
    payload/src/render/Thirdparty.cpp

    payload/src/game/Raycast.cpp
    payload/src/game/RotationEngine.cpp
    payload/src/game/Projection.cpp
    payload/src/game/PlayerRadar.cpp
    payload/src/game/TargetFinder.cpp

    payload/src/modules/Module.cpp
    payload/src/modules/ModuleManager.cpp
    payload/src/modules/ModuleList.cpp

    payload/src/script/Lua.cpp
    payload/src/script/Script.cpp
    payload/src/script/ScriptModule.cpp
    payload/src/script/HudElement.cpp
    payload/src/script/ScriptManager.cpp
    payload/src/script/ApiCore.cpp
    payload/src/script/ApiGame.cpp
    payload/src/script/ApiRender.cpp
    payload/src/script/ApiRender3d.cpp
    payload/src/script/ApiHud.cpp
    payload/src/script/ApiModule.cpp
    
    
    payload/src/modules/combat/Displace.cpp
    payload/src/modules/combat/JumpReset.cpp
    payload/src/modules/combat/TriggerBot.cpp
    payload/src/modules/combat/WTap.cpp

    payload/src/modules/movement/InvMove.cpp
    payload/src/modules/movement/Sprint.cpp

    payload/src/modules/player/AutoTool.cpp
    payload/src/modules/player/BridgeAssist.cpp
    payload/src/modules/player/Clutch.cpp
    payload/src/modules/player/FastMine.cpp
    payload/src/modules/player/FastPlace.cpp

    payload/src/modules/render/CuteVisuals.cpp
    payload/src/modules/render/Esp.cpp
    payload/src/modules/render/TargetHud.cpp
    payload/src/modules/render/Trajectories.cpp
    
    payload/src/modules/client/ClickGuiModule.cpp
    payload/src/modules/client/RadarModule.cpp
    payload/src/modules/client/HudModule.cpp

    payload/src/ui/Theme.cpp
    payload/src/ui/ClickGui.cpp
    payload/src/ui/Radar.cpp
    payload/src/ui/Hud.cpp
    payload/src/ui/Client.cpp

    payload/src/assets/Assets.generated.cpp
    payload/src/assets/AssetStore.cpp

    "${CMAKE_CURRENT_BINARY_DIR}/payload.rc"
)

set_target_properties(cobble PROPERTIES OUTPUT_NAME "cobble" PREFIX "")
target_include_directories(cobble PRIVATE
    payload/src
    payload/thirdparty
    payload/thirdparty/lua
    "${COLD_JAVA_HOME}/include"
    "${COLD_JAVA_HOME}/include/win32"
)
target_link_libraries(cobble PRIVATE minhook lua opengl32 gdi32 user32 shlwapi shell32 winhttp)
target_compile_definitions(cobble PRIVATE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _CRT_SECURE_NO_WARNINGS
    UNICODE
    _UNICODE
    ${COLD_FLAVOUR_DEFINITIONS}
)
if(MSVC)
    target_compile_options(cobble PRIVATE /W4 /permissive- /Zc:__cplusplus /utf-8 /MP
        /wd4100  # unreferenced formal parameter, common in JNI callbacks
        $<$<CONFIG:Release>:/O2 /Oi /GL>
    )
    target_link_options(cobble PRIVATE $<$<CONFIG:Release>:/LTCG /OPT:REF /OPT:ICF>)
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
        "-DCOLD_RC_IN=${CMAKE_SOURCE_DIR}/injector/injector.rc.in"
        "-DCOLD_RC_OUT=${COLD_LOADER_RC}"
        "-DCOLD_HPP_IN=${CMAKE_SOURCE_DIR}/injector/src/EmbeddedScripts.generated.hpp.in"
        "-DCOLD_HPP_OUT=${COLD_LOADER_HPP}"
        "-DCOLD_STAMP=${COLD_LOADER_STAMP}"
        -P "${CMAKE_SOURCE_DIR}/injector/ConfigureLoader.cmake"
    DEPENDS "${COLD_PAYLOAD_DLL}" ${COLD_SCRIPT_FILES}
    COMMENT "embedding payload + scripts into the loader"
    VERBATIM
)

add_executable(cobble-injector
    injector/src/Main.cpp
    injector/src/Inject.cpp
    injector/src/ProcessList.cpp
    injector/src/Embedded.cpp
    injector/src/KeyCheck.cpp
    "${COLD_LOADER_RC}"
)
target_include_directories(cobble-injector PRIVATE
    injector/src payload/src "${CMAKE_CURRENT_BINARY_DIR}/generated")
target_link_libraries(cobble-injector PRIVATE user32 shlwapi winhttp)
target_compile_definitions(cobble-injector PRIVATE
    WIN32_LEAN_AND_MEAN NOMINMAX _CRT_SECURE_NO_WARNINGS UNICODE _UNICODE
    ${COLD_FLAVOUR_DEFINITIONS})
if(MSVC)
    target_compile_options(cobble-injector PRIVATE /W4 /permissive- /utf-8
        $<$<CONFIG:Release>:/O2>)
endif()
add_dependencies(cobble-injector cobble)

# The example scripts are repository content, not user data: the payload reads
# them from beside the DLL, so a build puts the current ones there.
add_custom_command(TARGET cobble POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        "${CMAKE_SOURCE_DIR}/scripts" "${COLD_DIST_DIR}/scripts"
    COMMENT "copying scripts/ to ${COLD_FLAVOUR_LABEL} dist")
