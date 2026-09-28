// Native side of the Android on-screen controller (see android/.../TouchControlsView.java).
// The overlay drives an SDL virtual gamepad, so the game sees it like any other controller and
// the normal input mappings apply.
#ifdef __ANDROID__

#include <jni.h>
#include <mutex>

#include <SDL2/SDL.h>
#include <libultraship/libultraship.h>
#include <spdlog/spdlog.h>
#include "ship/window/gui/FileBrowserWindow.h"
#include "port/UI/LighthouseGui.hpp"

namespace {

std::mutex sMutex;
SDL_Joystick* sJoystick = nullptr;

// Creates the virtual gamepad once SDL's controller subsystem is up. Returns null until then.
SDL_Joystick* GetJoystick() {
    if (sJoystick != nullptr) {
        return sJoystick;
    }
    if (SDL_WasInit(SDL_INIT_GAMECONTROLLER) == 0) {
        return nullptr;
    }

    SDL_VirtualJoystickDesc desc;
    SDL_zero(desc);
    desc.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
    desc.type = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
    desc.naxes = SDL_CONTROLLER_AXIS_MAX;
    desc.nbuttons = SDL_CONTROLLER_BUTTON_MAX;
    // Declaring every button and axis makes SDL map index N to SDL_GameControllerButton/Axis N.
    desc.button_mask = (1u << SDL_CONTROLLER_BUTTON_MAX) - 1;
    desc.axis_mask = (1u << SDL_CONTROLLER_AXIS_MAX) - 1;
    desc.name = "Lighthouse Touch Controls";

    const int deviceIndex = SDL_JoystickAttachVirtualEx(&desc);
    if (deviceIndex < 0) {
        SPDLOG_ERROR("Touch controls: couldn't create virtual gamepad: {}", SDL_GetError());
        return nullptr;
    }
    sJoystick = SDL_JoystickOpen(deviceIndex);
    if (sJoystick == nullptr) {
        SPDLOG_ERROR("Touch controls: couldn't open virtual gamepad: {}", SDL_GetError());
    }
    return sJoystick;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_cogent_lighthouse_TouchControlsView_nativeSetButton(JNIEnv*, jclass, jint button,
                                                                                     jboolean pressed) {
    std::lock_guard<std::mutex> lock(sMutex);
    if (SDL_Joystick* joystick = GetJoystick()) {
        SDL_JoystickSetVirtualButton(joystick, button, pressed ? SDL_PRESSED : SDL_RELEASED);
    }
}

JNIEXPORT void JNICALL Java_com_cogent_lighthouse_TouchControlsView_nativeSetAxis(JNIEnv*, jclass, jint axis,
                                                                                   jint value) {
    std::lock_guard<std::mutex> lock(sMutex);
    if (SDL_Joystick* joystick = GetJoystick()) {
        SDL_JoystickSetVirtualAxis(joystick, axis, static_cast<Sint16>(SDL_clamp(value, -32768, 32767)));
    }
}

// True while a menu, popup or file browser is up, so the overlay can hand touches to the UI.
JNIEXPORT jboolean JNICALL Java_com_cogent_lighthouse_TouchControlsView_nativeIsUiActive(JNIEnv*, jclass) {
    auto context = Ship::Context::GetRawInstance();
    if (context == nullptr || context->GetWindow() == nullptr || context->GetWindow()->GetGui() == nullptr) {
        return JNI_TRUE;
    }
    const bool active = context->GetWindow()->GetGui()->GetMenuOrMenubarVisible() ||
                        LighthouseGui::PopupsQueued() > 0 || Ship::FileBrowserWindow::IsOpen();
    return active ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

#endif // __ANDROID__
