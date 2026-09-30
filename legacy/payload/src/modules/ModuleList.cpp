#include "modules/ModuleList.hpp"

#include "modules/client/ClickGuiModule.hpp"
#include "modules/client/HudModule.hpp"
#include "modules/client/RadarModule.hpp"
#include "modules/combat/Displace.hpp"
#include "modules/combat/JumpReset.hpp"
#include "modules/combat/TriggerBot.hpp"
#include "modules/combat/WTap.hpp"
#include "modules/movement/InvMove.hpp"
#include "modules/movement/Sprint.hpp"
#include "modules/player/AutoTool.hpp"
#include "modules/player/BridgeAssist.hpp"
#include "modules/player/Clutch.hpp"
#include "modules/player/FastMine.hpp"
#include "modules/player/FastPlace.hpp"
#include "modules/render/CuteVisuals.hpp"
#include "modules/render/Esp.hpp"
#include "modules/render/TargetHud.hpp"
#include "modules/render/Trajectories.hpp"

namespace cold::modules {

std::vector<std::unique_ptr<Module>> createModules() {
    std::vector<std::unique_ptr<Module>> list;
    /* 1.8.9 set. The 1.21-only combat modules (crystals, anchors, mace,
     * inventory totems) have no target-version concept and stay out. */
    list.push_back(std::make_unique<TriggerBot>());
    list.push_back(std::make_unique<Displace>());
    list.push_back(std::make_unique<JumpReset>());
    list.push_back(std::make_unique<WTap>());

    list.push_back(std::make_unique<Sprint>());
    list.push_back(std::make_unique<InvMove>());

    list.push_back(std::make_unique<AutoTool>());
    list.push_back(std::make_unique<BridgeAssist>());
    list.push_back(std::make_unique<Clutch>());
    list.push_back(std::make_unique<FastMine>());
    list.push_back(std::make_unique<FastPlace>());

    list.push_back(std::make_unique<Esp>());
    list.push_back(std::make_unique<TargetHud>());
    list.push_back(std::make_unique<Trajectories>());
    list.push_back(std::make_unique<CuteVisuals>());

    list.push_back(std::make_unique<ClickGuiModule>());
    list.push_back(std::make_unique<HudModule>());
    list.push_back(std::make_unique<RadarModule>());
    return list;
}

}  // namespace cold::modules
