#pragma once

#include "modules/Module.hpp"

namespace cold::modules {

/* The menu is a module so its key lives in the same bind list as everything
 * else. Toggling it is the enable/disable edge rather than a special case in
 * the input hook. */
class ClickGuiModule : public Module {
public:
    ClickGuiModule();

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onRender(Context& context) override;

private:
    Setting* scale_;
    Setting* blur_;
    Setting* accent_;
};

}  // namespace cold::modules
