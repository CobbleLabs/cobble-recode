#pragma once

#include <memory>
#include <vector>

#include "modules/Module.hpp"

namespace cold::modules {

/* Single registration point. Keeping it in one translation unit means adding a
 * module touches exactly two files: its own, and this list. */
std::vector<std::unique_ptr<Module>> createModules();

}  // namespace cold::modules
