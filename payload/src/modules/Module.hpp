#pragma once

#include <jni.h>

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "assets/Assets.generated.hpp"
#include "bridge/Minecraft.hpp"
#include "core/Math.hpp"
#include "game/Projection.hpp"

namespace cold::modules {

enum class Category : std::uint8_t {
    Combat,
    Movement,
    Player,
    Render,
    World,
    Client,
    Scripts,
    Count,
};

const char* categoryName(Category category);
assets::IconAsset categoryIcon(Category category);

class Setting {
public:
    enum class Type : std::uint8_t {
        Boolean,
        Number,
        Choice,
        Key,
        Colour,
    };

    Setting(Type type, std::string name, std::string description)
        : type_(type), name_(std::move(name)), description_(std::move(description)) {}

    Type type() const { return type_; }
    const std::string& name() const { return name_; }
    const std::string& description() const { return description_; }

    bool booleanValue() const { return boolean_; }
    void setBoolean(bool value) { boolean_ = value; }

    float number() const { return number_; }
    void setNumber(float value);
    float minimum() const { return minimum_; }
    float maximum() const { return maximum_; }
    int decimals() const { return decimals_; }
    /* 0..1 position of the current value, for slider geometry. */
    float normalised() const;
    void setNormalised(float fraction);

    int choice() const { return choice_; }
    void setChoice(int index);
    const std::vector<std::string>& choices() const { return choices_; }
    const std::string& choiceName() const;

    int key() const { return key_; }
    void setKey(int value) { key_ = value; }

    const Color& colour() const { return colour_; }
    void setColour(const Color& value) { colour_ = value; }

    /* Settings that only make sense under another setting hide themselves
     * instead of greying out, which keeps the panel short. */
    Setting& visibleWhen(std::function<bool()> predicate) {
        visible_ = std::move(predicate);
        return *this;
    }
    bool visible() const { return !visible_ || visible_(); }

private:
    friend class Module;

    Type type_;
    std::string name_;
    std::string description_;

    bool boolean_ = false;
    float number_ = 0.0f;
    float minimum_ = 0.0f;
    float maximum_ = 1.0f;
    float step_ = 0.01f;
    int decimals_ = 2;
    int choice_ = 0;
    std::vector<std::string> choices_;
    int key_ = 0;
    Color colour_ = Color::rgb(0x58C6FF);
    std::function<bool()> visible_;
};

struct Context {
    JNIEnv* env = nullptr;
    const game::FrameState* state = nullptr;
    const game::Projection* projection = nullptr;
    float deltaTime = 0.0f;
    float time = 0.0f;
    bool tickEdge = false;
};

class Module {
public:
    Module(std::string name, std::string description, Category category, int defaultKey = 0);
    virtual ~Module() = default;

    Module(const Module&) = delete;
    Module& operator=(const Module&) = delete;

    const std::string& name() const { return name_; }
    const std::string& description() const { return description_; }
    Category category() const { return category_; }
    assets::IconAsset icon() const { return icon_; }

    bool enabled() const { return enabled_; }
    void setEnabled(bool value, Context* context);
    void toggle(Context* context) { setEnabled(!enabled_, context); }

    int key() const { return key_; }
    void setKey(int value) { key_ = value; }

    /* 0..1 fade used by the HUD list and the menu toggle, driven centrally so
     * every surface animates in step. */
    float animation() const { return animation_; }
    void advanceAnimation(float deltaTime);

    /* Extra text shown beside the name in the array list, such as the active
     * mode. */
    virtual std::string suffix() const { return {}; }

    virtual void onEnable(Context&) {}
    virtual void onDisable(Context&) {}
    virtual void onTick(Context&) {}
    virtual void onRender(Context&) {}

    const std::vector<std::unique_ptr<Setting>>& settings() const { return settings_; }
    Setting* setting(const std::string& name);

protected:
    Setting& addBoolean(std::string name, std::string description, bool value);
    Setting& addNumber(std::string name, std::string description, float value, float minimum,
                       float maximum, int decimals);
    Setting& addChoice(std::string name, std::string description, std::vector<std::string> choices,
                       int index);
    Setting& addKey(std::string name, std::string description, int key);
    Setting& addColour(std::string name, std::string description, const Color& colour);
    void setIcon(assets::IconAsset icon) { icon_ = icon; }

private:
    std::string name_;
    std::string description_;
    Category category_;
    assets::IconAsset icon_;
    int key_ = 0;
    bool enabled_ = false;
    float animation_ = 0.0f;
    std::vector<std::unique_ptr<Setting>> settings_;
};

}  // namespace cold::modules
