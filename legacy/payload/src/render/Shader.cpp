#include "render/Shader.hpp"

#include <vector>

#include "core/Log.hpp"

namespace cold::render {
namespace {

constexpr std::string_view kScope = "shader";

GLuint compileStage(const char* name, GLenum type, const char* source) {
    GLuint shader = gl::CreateShader(type);
    gl::ShaderSource(shader, 1, &source, nullptr);
    gl::CompileShader(shader);

    GLint status = GL_FALSE;
    gl::GetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status == GL_TRUE) {
        return shader;
    }

    GLint length = 0;
    gl::GetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
    std::vector<char> log(static_cast<std::size_t>(length > 0 ? length : 1), '\0');
    gl::GetShaderInfoLog(shader, length, nullptr, log.data());
    const char* stageName = type == GL_VERTEX_SHADER      ? "vertex"
                            : type == GL_COMPUTE_SHADER ? "compute"
                                                        : "fragment";
    COLD_ERROR(kScope, "%s %s stage failed to compile:\n%s", name, stageName, log.data());
    gl::DeleteShader(shader);
    return 0;
}

}  // namespace

Shader::~Shader() { destroy(); }

bool Shader::compile(const char* name, const char* vertexSource, const char* fragmentSource) {
    destroy();
    name_ = name;

    GLuint vertex = compileStage(name, GL_VERTEX_SHADER, vertexSource);
    if (vertex == 0) {
        return false;
    }
    GLuint fragment = compileStage(name, GL_FRAGMENT_SHADER, fragmentSource);
    if (fragment == 0) {
        gl::DeleteShader(vertex);
        return false;
    }

    GLuint program = gl::CreateProgram();
    gl::AttachShader(program, vertex);
    gl::AttachShader(program, fragment);
    gl::LinkProgram(program);
    gl::DeleteShader(vertex);
    gl::DeleteShader(fragment);

    GLint status = GL_FALSE;
    gl::GetProgramiv(program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        GLint length = 0;
        gl::GetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<std::size_t>(length > 0 ? length : 1), '\0');
        gl::GetProgramInfoLog(program, length, nullptr, log.data());
        COLD_ERROR(kScope, "%s failed to link:\n%s", name, log.data());
        gl::DeleteProgram(program);
        return false;
    }

    program_ = program;
    COLD_DEBUG(kScope, "%s linked as program %u", name, program_);
    return true;
}

bool Shader::compileCompute(const char* name, const char* source) {
    destroy();
    name_ = name;
    if (gl::DispatchCompute == nullptr) {
        return false;
    }

    GLuint stage = compileStage(name, GL_COMPUTE_SHADER, source);
    if (stage == 0) {
        return false;
    }
    GLuint program = gl::CreateProgram();
    gl::AttachShader(program, stage);
    gl::LinkProgram(program);
    gl::DeleteShader(stage);

    GLint status = GL_FALSE;
    gl::GetProgramiv(program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        GLint length = 0;
        gl::GetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<std::size_t>(length > 0 ? length : 1), 0);
        gl::GetProgramInfoLog(program, length, nullptr, log.data());
        COLD_ERROR(kScope, "%s failed to link:\n%s", name, log.data());
        gl::DeleteProgram(program);
        return false;
    }

    program_ = program;
    COLD_DEBUG(kScope, "%s linked as compute program %u", name, program_);
    return true;
}

void Shader::destroy() {
    if (program_ != 0) {
        gl::DeleteProgram(program_);
        program_ = 0;
    }
    uniforms_.clear();
}

void Shader::bind() const { gl::UseProgram(program_); }

GLint Shader::uniform(const char* name) {
    auto found = uniforms_.find(name);
    if (found != uniforms_.end()) {
        return found->second;
    }
    const GLint location = gl::GetUniformLocation(program_, name);
    uniforms_.emplace(name, location);
    if (location < 0) {
        COLD_TRACE(kScope, "%s has no uniform '%s' (optimised out?)", name_, name);
    }
    return location;
}

void Shader::setUint(const char* name, unsigned value) {
    const GLint location = uniform(name);
    if (location >= 0) gl::Uniform1ui(location, value);
}

void Shader::set(const char* name, int value) {
    const GLint location = uniform(name);
    if (location >= 0) gl::Uniform1i(location, value);
}

void Shader::set(const char* name, float value) {
    const GLint location = uniform(name);
    if (location >= 0) gl::Uniform1f(location, value);
}

void Shader::set(const char* name, float x, float y) {
    const GLint location = uniform(name);
    if (location >= 0) gl::Uniform2f(location, x, y);
}

void Shader::set(const char* name, float x, float y, float z, float w) {
    const GLint location = uniform(name);
    if (location >= 0) gl::Uniform4f(location, x, y, z, w);
}

void Shader::set(const char* name, const Color& color) {
    set(name, color.r, color.g, color.b, color.a);
}

void Shader::set(const char* name, const Mat4& matrix) {
    const GLint location = uniform(name);
    if (location >= 0) gl::UniformMatrix4fv(location, 1, GL_FALSE, matrix.m);
}

}  // namespace cold::render
