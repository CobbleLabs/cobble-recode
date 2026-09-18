#pragma once

#include <string>
#include <string_view>

namespace cold::log {

enum class Level {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
};

void open(const std::wstring& directory);
void close();
void setMinimumLevel(Level level);
Level minimumLevel();

void writeLine(Level level, std::string_view scope, std::string text);
std::string format(const char* fmt, ...);

/* Scoped timer that logs how long a startup stage took. Injection is the one
 * place where a slow stage is invisible without this, because the game window
 * simply sits there. */
class Stage {
public:
    Stage(std::string_view scope, std::string_view name);
    ~Stage();

    void fail(std::string reason);

private:
    std::string_view scope_;
    std::string_view name_;
    long long start_;
    bool failed_ = false;
};

}  // namespace cold::log

#define COLD_LOG(level, scope, ...)                                              \
    do {                                                                         \
        if ((level) >= ::cold::log::minimumLevel()) {                            \
            ::cold::log::writeLine((level), (scope), ::cold::log::format(__VA_ARGS__)); \
        }                                                                        \
    } while (false)

#define COLD_TRACE(scope, ...) COLD_LOG(::cold::log::Level::Trace, scope, __VA_ARGS__)
#define COLD_DEBUG(scope, ...) COLD_LOG(::cold::log::Level::Debug, scope, __VA_ARGS__)
#define COLD_INFO(scope, ...) COLD_LOG(::cold::log::Level::Info, scope, __VA_ARGS__)
#define COLD_WARN(scope, ...) COLD_LOG(::cold::log::Level::Warn, scope, __VA_ARGS__)
#define COLD_ERROR(scope, ...) COLD_LOG(::cold::log::Level::Error, scope, __VA_ARGS__)
