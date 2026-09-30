#pragma once

#include "modules/Module.hpp"

namespace cold::modules {

class HudModule : public Module {
public:
    HudModule();

    void onRender(Context& context) override;

private:
    Setting* scale_;
    Setting* watermark_;
    Setting* arrayList_;
    Setting* playerInfo_;
    Setting* targetInfo_;
    Setting* notifications_;
    Setting* speed_;
};

}  // namespace cold::modules
