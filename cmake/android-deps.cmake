# Android has no system packages, so build ogg/vorbis/SDL2_net from source.
include(FetchContent)

set(BUILD_SHARED_LIBS OFF)
set(BUILD_TESTING OFF)
set(INSTALL_DOCS OFF)

FetchContent_Declare(
    ogg
    GIT_REPOSITORY https://github.com/xiph/ogg.git
    GIT_TAG v1.3.5
    OVERRIDE_FIND_PACKAGE
)
FetchContent_MakeAvailable(ogg)

FetchContent_Declare(
    vorbis
    GIT_REPOSITORY https://github.com/xiph/vorbis.git
    GIT_TAG v1.3.7
    OVERRIDE_FIND_PACKAGE
)
FetchContent_MakeAvailable(vorbis)
foreach(_lib vorbis vorbisenc vorbisfile)
    if(NOT TARGET Vorbis::${_lib})
        add_library(Vorbis::${_lib} ALIAS ${_lib})
    endif()
endforeach()

if(USE_NETWORKING)
    set(SDL2NET_INSTALL OFF)
    set(SDL2NET_SAMPLES OFF)
    FetchContent_Declare(
        SDL2_net
        GIT_REPOSITORY https://github.com/libsdl-org/SDL_net.git
        GIT_TAG release-2.2.0
    )
    FetchContent_MakeAvailable(SDL2_net)
    if(NOT TARGET SDL2_net::SDL2_net)
        add_library(SDL2_net::SDL2_net ALIAS SDL2_net)
    endif()

    # Game code includes <SDL2/SDL_net.h>
    set(SDL2_NET_INCLUDE_DIRS ${CMAKE_BINARY_DIR}/sdl2_net_include)
    file(COPY ${sdl2_net_SOURCE_DIR}/SDL_net.h DESTINATION ${SDL2_NET_INCLUDE_DIRS}/SDL2)
endif()
