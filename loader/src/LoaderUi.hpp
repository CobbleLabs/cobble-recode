#pragma once

#include <windows.h>

#include <string>
#include <vector>

#include "ProcessList.hpp"

namespace cold::injector::ui {

enum class View { Picking, Injecting, Success, Error };

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

}  // namespace cold::injector::ui
