#pragma once

#include <windows.h>

#include <string>
#include <vector>

#include "ProcessList.hpp"

namespace cold::injector::ui {

enum class View { Login, SigningIn, Picking, Injecting, Success, Error };

bool init();
void show();
void shutdown();

/* Pumps messages and draws one frame; false once the window should close. */
bool render();

View view();
void setView(View next);
void setInstances(const std::vector<ProcessEntry>& entries);
void showError(const std::wstring& message);

/* Process the user clicked since the last call, or 0. */
DWORD takePick();

/* Credentials submitted since the last call; false when there are none. */
bool takeLogin(std::string& username, std::string& password);

/* Back to the sign-in form with the reason shown under it. */
void showLoginError(const std::wstring& message);

}  // namespace cold::injector::ui
