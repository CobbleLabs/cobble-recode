#include "modules/Module.hpp"

#include <cmath>

#include "core/Log.hpp"

namespace cold::modules {
namespace {

constexpr std::string_view kScope = "module";
constexpr float kAnimationRate = 11.0f;

}  // namespace

const char* categoryName(Category category) {
    switch (category) {
        case Category::Combat: return "Combat";
        case Category::Movement: return "Movement";
        case Category::Player: return "Player";
        case Category::Render: return "Render";
        case Category::World: return "World";
        case Category::Client: return "Client";
        case Category::Scripts: return "Scripts";
        case Category::Count: break;
    }
    return "?";
}

assets::IconAsset categoryIcon(Category category) {
    switch (category) {
        case Category::Combat: return assets::IconAsset::Swords;
        case Category::Movement: return assets::IconAsset::Footprints;
        case Category::Player: return assets::IconAsset::UserRound;
        case Category::Render: return assets::IconAsset::Eye;
        case Category::World: return assets::IconAsset::Box;
        case Category::Client: return assets::IconAsset::Snowflake;
        case Category::Scripts: return assets::IconAsset::Braces;
        case Category::Count: break;
    }
    return assets::IconAsset::Snowflake;
}

void Setting::setNumber(float value) {
    number_ = clamp(value, minimum_, maximum_);
    if (step_ > 0.0f) {
        number_ = minimum_ + std::round((number_ - minimum_) / step_) * step_;
        number_ = clamp(number_, minimum_, maximum_);
    }
}

float Setting::normalised() const {
    const float span = maximum_ - minimum_;
    return span <= 0.0f ? 0.0f : clamp((number_ - minimum_) / span, 0.0f, 1.0f);
}

void Setting::setNormalised(float fraction) {
    setNumber(minimum_ + clamp(fraction, 0.0f, 1.0f) * (maximum_ - minimum_));
}

void Setting::setChoice(int index) {
    if (choices_.empty()) {
        choice_ = 0;
        return;
    }
    const int count = static_cast<int>(choices_.size());
    choice_ = ((index % count) + count) % count;
}

const std::string& Setting::choiceName() const {
    static const std::string kEmpty;
    if (choices_.empty()) {
        return kEmpty;
    }
    return choices_[static_cast<std::size_t>(clamp(choice_, 0, static_cast<int>(choices_.size()) - 1))];
}

Module::Module(std::string name, std::string description, Category category, int defaultKey)
    : name_(std::move(name)),
      description_(std::move(description)),
      category_(category),
      icon_(categoryIcon(category)),
      key_(defaultKey) {}

void Module::setEnabled(bool value, Context* context) {
    if (enabled_ == value) {
        return;
    }
    enabled_ = value;
    COLD_DEBUG(kScope, "%s %s", name_.c_str(), value ? "enabled" : "disabled");
    if (context == nullptr) {
        return;
    }
    if (value) {
        onEnable(*context);
    } else {
        onDisable(*context);
    }
}

void Module::advanceAnimation(float deltaTime) {
    const float target = enabled_ ? 1.0f : 0.0f;
    const float rate = 1.0f - std::exp(-kAnimationRate * deltaTime);
    animation_ += (target - animation_) * rate;
    if (std::fabs(target - animation_) < 0.002f) {
        animation_ = target;
    }
}

Setting* Module::setting(const std::string& name) {
    for (const std::unique_ptr<Setting>& entry : settings_) {
        if (entry->name() == name) {
            return entry.get();
        }
    }
    return nullptr;
}

Setting& Module::addBoolean(std::string name, std::string description, bool value) {
    auto setting =
        std::make_unique<Setting>(Setting::Type::Boolean, std::move(name), std::move(description));
    setting->boolean_ = value;
    settings_.push_back(std::move(setting));
    return *settings_.back();
}

Setting& Module::addNumber(std::string name, std::string description, float value, float minimum,
                           float maximum, int decimals) {
    auto setting =
        std::make_unique<Setting>(Setting::Type::Number, std::move(name), std::move(description));
    setting->minimum_ = minimum;
    setting->maximum_ = maximum;
    setting->decimals_ = decimals;
    setting->step_ = decimals <= 0 ? 1.0f : std::pow(0.1f, static_cast<float>(decimals));
    setting->setNumber(value);
    settings_.push_back(std::move(setting));
    return *settings_.back();
}

Setting& Module::addChoice(std::string name, std::string description,
                           std::vector<std::string> choices, int index) {
    auto setting =
        std::make_unique<Setting>(Setting::Type::Choice, std::move(name), std::move(description));
    setting->choices_ = std::move(choices);
    setting->setChoice(index);
    settings_.push_back(std::move(setting));
    return *settings_.back();
}

Setting& Module::addKey(std::string name, std::string description, int key) {
    auto setting =
        std::make_unique<Setting>(Setting::Type::Key, std::move(name), std::move(description));
    setting->key_ = key;
    settings_.push_back(std::move(setting));
    return *settings_.back();
}

Setting& Module::addColour(std::string name, std::string description, const Color& colour) {
    auto setting =
        std::make_unique<Setting>(Setting::Type::Colour, std::move(name), std::move(description));
    setting->colour_ = colour;
    settings_.push_back(std::move(setting));
    return *settings_.back();
}

}  // namespace cold::modules
