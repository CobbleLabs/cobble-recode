#pragma once

#include <string>

#include "modules/Module.hpp"
#include "script/Script.hpp"

namespace cold::script {

/* A module whose behaviour lives in Lua.
 *
 * It is a `modules::Module` like any other, which is the point: it sits in the
 * same manager, the same config file, the same menu category and the same array
 * list as the ones written in C++, and everything that walks the module list
 * gets it for free. The only difference is where onTick goes. */
class ScriptModule : public modules::Module {
public:
    ScriptModule(Script& owner, std::string name, std::string description,
                 modules::Category category, int key);
    ~ScriptModule() override;

    ScriptModule(const ScriptModule&) = delete;
    ScriptModule& operator=(const ScriptModule&) = delete;

    Script& owner() const { return owner_; }
    /* Registry reference to the Lua table the script holds. */
    void bind(int reference) { tableRef_ = reference; }
    int table() const { return tableRef_; }

    std::string suffix() const override;

    void onEnable(modules::Context& context) override;
    void onDisable(modules::Context& context) override;
    void onTick(modules::Context& context) override;
    void onRender(modules::Context& context) override;

    /* The settings a definition table asks for. Module keeps its builders
     * protected, so the script side goes through these. */
    modules::Setting& defineBoolean(std::string name, std::string description, bool value);
    modules::Setting& defineNumber(std::string name, std::string description, float value,
                                   float minimum, float maximum, int decimals);
    modules::Setting& defineChoice(std::string name, std::string description,
                                   std::vector<std::string> choices, int index);
    modules::Setting& defineKey(std::string name, std::string description, int key);
    modules::Setting& defineColour(std::string name, std::string description, const Color& colour);
    void defineIcon(assets::IconAsset icon) { setIcon(icon); }
    /* Hides a setting behind a Lua predicate, the same way the C++ modules hide
     * theirs behind a lambda. */
    void defineVisibility(modules::Setting& setting, int functionRef);

private:
    /* Calls the named field of the module table with the table as its only
     * argument, so both `function m.onTick()` and `function m:onTick()` work. */
    void invoke(const char* key) const;
    bool pushCallback(const char* key) const;
    /* A module the config turned on is enabled before there is any context to
     * call onEnable with, exactly as the built-in ones are. The difference is
     * that a script keeps its state in that callback, so the first tick or
     * frame that finds the module already on runs it. */
    void ensureStarted();

    Script& owner_;
    int tableRef_ = LUA_NOREF;
    bool started_ = false;
};

}  // namespace cold::script
