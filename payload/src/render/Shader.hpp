#pragma once

#include <string>
#include <unordered_map>

#include "core/Math.hpp"
#include "render/Gl.hpp"

namespace cold::render {

class Shader {
public:
    Shader() = default;
    ~Shader();

    Shader(const Shader&) = delete;
    Shader& operator=(const Shader&) = delete;

    bool compile(const char* name, const char* vertexSource, const char* fragmentSource);
    /* A compute-only program. Fails rather than logs a fatal when the context
     * has no compute stage, because that is a supported outcome here - the
     * renderer keeps its CPU path for exactly that case. */
    bool compileCompute(const char* name, const char* source);
    void destroy();

    void bind() const;
    bool valid() const { return program_ != 0; }
    GLuint id() const { return program_; }

    GLint uniform(const char* name);
    void set(const char* name, int value);
    void setUint(const char* name, unsigned value);
    void set(const char* name, float value);
    void set(const char* name, float x, float y);
    void set(const char* name, float x, float y, float z, float w);
    void set(const char* name, const Color& color);
    void set(const char* name, const Mat4& matrix);

private:
    GLuint program_ = 0;
    const char* name_ = "shader";
    std::unordered_map<std::string, GLint> uniforms_;
};

}  // namespace cold::render
