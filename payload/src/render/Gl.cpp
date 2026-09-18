#include "render/Gl.hpp"

#include <cstring>

#include "core/Log.hpp"

namespace cold::gl {
namespace {

constexpr std::string_view kScope = "gl";
bool g_loaded = false;
int g_missing = 0;

using WglGetProcAddress = PROC(WINAPI*)(LPCSTR);

WglGetProcAddress g_wglGetProcAddress = nullptr;
HMODULE g_opengl32 = nullptr;

void* resolve(const char* name) {
    void* address = nullptr;
    if (g_wglGetProcAddress != nullptr) {
        address = reinterpret_cast<void*>(g_wglGetProcAddress(name));
        /* wglGetProcAddress returns these sentinels for unsupported entries. */
        if (address == reinterpret_cast<void*>(-1) || address == reinterpret_cast<void*>(1) ||
            address == reinterpret_cast<void*>(2) || address == reinterpret_cast<void*>(3)) {
            address = nullptr;
        }
    }
    if (address == nullptr && g_opengl32 != nullptr) {
        address = reinterpret_cast<void*>(GetProcAddress(g_opengl32, name));
    }
    if (address == nullptr) {
        COLD_ERROR(kScope, "unresolved entry point %s", name);
        ++g_missing;
    }
    return address;
}

template <typename T>
void bind(T& slot, const char* name) {
    slot = reinterpret_cast<T>(resolve(name));
}

/* Same, for an entry point whose absence is an answer rather than a failure.
 * resolve() counts a miss and logs an error, which is right for the 3.3 core
 * set and wrong for anything being probed for. */
template <typename T>
void bindOptional(T& slot, const char* name) {
    void* address = nullptr;
    if (g_wglGetProcAddress != nullptr) {
        address = reinterpret_cast<void*>(g_wglGetProcAddress(name));
        if (address == reinterpret_cast<void*>(-1) || address == reinterpret_cast<void*>(1) ||
            address == reinterpret_cast<void*>(2) || address == reinterpret_cast<void*>(3)) {
            address = nullptr;
        }
    }
    if (address == nullptr && g_opengl32 != nullptr) {
        address = reinterpret_cast<void*>(GetProcAddress(g_opengl32, name));
    }
    slot = reinterpret_cast<T>(address);
}

Capabilities g_caps;

}  // namespace

PFNGLGENBUFFERS GenBuffers = nullptr;
PFNGLDELETEBUFFERS DeleteBuffers = nullptr;
PFNGLBINDBUFFER BindBuffer = nullptr;
PFNGLBUFFERDATA BufferData = nullptr;
PFNGLBUFFERSUBDATA BufferSubData = nullptr;
PFNGLGENVERTEXARRAYS GenVertexArrays = nullptr;
PFNGLDELETEVERTEXARRAYS DeleteVertexArrays = nullptr;
PFNGLBINDVERTEXARRAY BindVertexArray = nullptr;
PFNGLENABLEVERTEXATTRIBARRAY EnableVertexAttribArray = nullptr;
PFNGLVERTEXATTRIBPOINTER VertexAttribPointer = nullptr;
PFNGLCREATESHADER CreateShader = nullptr;
PFNGLDELETESHADER DeleteShader = nullptr;
PFNGLSHADERSOURCE ShaderSource = nullptr;
PFNGLCOMPILESHADER CompileShader = nullptr;
PFNGLGETSHADERIV GetShaderiv = nullptr;
PFNGLGETSHADERINFOLOG GetShaderInfoLog = nullptr;
PFNGLCREATEPROGRAM CreateProgram = nullptr;
PFNGLDELETEPROGRAM DeleteProgram = nullptr;
PFNGLATTACHSHADER AttachShader = nullptr;
PFNGLLINKPROGRAM LinkProgram = nullptr;
PFNGLGETPROGRAMIV GetProgramiv = nullptr;
PFNGLGETPROGRAMINFOLOG GetProgramInfoLog = nullptr;
PFNGLUSEPROGRAM UseProgram = nullptr;
PFNGLGETUNIFORMLOCATION GetUniformLocation = nullptr;
PFNGLUNIFORM1I Uniform1i = nullptr;
PFNGLUNIFORM1F Uniform1f = nullptr;
PFNGLUNIFORM2F Uniform2f = nullptr;
PFNGLUNIFORM3F Uniform3f = nullptr;
PFNGLUNIFORM4F Uniform4f = nullptr;
PFNGLUNIFORMMATRIX4FV UniformMatrix4fv = nullptr;
PFNGLACTIVETEXTURE ActiveTexture = nullptr;
PFNGLBLENDFUNCSEPARATE BlendFuncSeparate = nullptr;
PFNGLBLENDEQUATION BlendEquation = nullptr;
PFNGLGENFRAMEBUFFERS GenFramebuffers = nullptr;
PFNGLDELETEFRAMEBUFFERS DeleteFramebuffers = nullptr;
PFNGLBINDFRAMEBUFFER BindFramebuffer = nullptr;
PFNGLFRAMEBUFFERTEXTURE2D FramebufferTexture2D = nullptr;
PFNGLCHECKFRAMEBUFFERSTATUS CheckFramebufferStatus = nullptr;
PFNGLGETFRAMEBUFFERATTACHMENTPARAMETERIV GetFramebufferAttachmentParameteriv = nullptr;
PFNGLBLITFRAMEBUFFER BlitFramebuffer = nullptr;
PFNGLBINDSAMPLER BindSampler = nullptr;
PFNGLGENSAMPLERS GenSamplers = nullptr;
PFNGLDELETESAMPLERS DeleteSamplers = nullptr;
PFNGLSAMPLERPARAMETERI SamplerParameteri = nullptr;
PFNGLCLIENTACTIVETEXTURE ClientActiveTexture = nullptr;

PFNGLGETSTRINGI GetStringi = nullptr;
PFNGLDISPATCHCOMPUTE DispatchCompute = nullptr;
PFNGLMEMORYBARRIER MemoryBarrier_ = nullptr;
PFNGLBINDBUFFERBASE BindBufferBase = nullptr;
PFNGLUNIFORM1UI Uniform1ui = nullptr;
PFNGLVERTEXATTRIBDIVISOR VertexAttribDivisor = nullptr;
PFNGLDRAWARRAYSINSTANCED DrawArraysInstanced = nullptr;

bool loaded() { return g_loaded; }

const Capabilities& capabilities() { return g_caps; }

bool load() {
    if (g_loaded) {
        return true;
    }
    log::Stage stage(kScope, "load OpenGL entry points");

    g_opengl32 = GetModuleHandleW(L"opengl32.dll");
    if (g_opengl32 == nullptr) {
        g_opengl32 = LoadLibraryW(L"opengl32.dll");
    }
    if (g_opengl32 == nullptr) {
        stage.fail("opengl32.dll unavailable");
        return false;
    }
    g_wglGetProcAddress =
        reinterpret_cast<WglGetProcAddress>(GetProcAddress(g_opengl32, "wglGetProcAddress"));

    g_missing = 0;
    bind(GenBuffers, "glGenBuffers");
    bind(DeleteBuffers, "glDeleteBuffers");
    bind(BindBuffer, "glBindBuffer");
    bind(BufferData, "glBufferData");
    bind(BufferSubData, "glBufferSubData");
    bind(GenVertexArrays, "glGenVertexArrays");
    bind(DeleteVertexArrays, "glDeleteVertexArrays");
    bind(BindVertexArray, "glBindVertexArray");
    bind(EnableVertexAttribArray, "glEnableVertexAttribArray");
    bind(VertexAttribPointer, "glVertexAttribPointer");
    bind(CreateShader, "glCreateShader");
    bind(DeleteShader, "glDeleteShader");
    bind(ShaderSource, "glShaderSource");
    bind(CompileShader, "glCompileShader");
    bind(GetShaderiv, "glGetShaderiv");
    bind(GetShaderInfoLog, "glGetShaderInfoLog");
    bind(CreateProgram, "glCreateProgram");
    bind(DeleteProgram, "glDeleteProgram");
    bind(AttachShader, "glAttachShader");
    bind(LinkProgram, "glLinkProgram");
    bind(GetProgramiv, "glGetProgramiv");
    bind(GetProgramInfoLog, "glGetProgramInfoLog");
    bind(UseProgram, "glUseProgram");
    bind(GetUniformLocation, "glGetUniformLocation");
    bind(Uniform1i, "glUniform1i");
    bind(Uniform1f, "glUniform1f");
    bind(Uniform2f, "glUniform2f");
    bind(Uniform3f, "glUniform3f");
    bind(Uniform4f, "glUniform4f");
    bind(UniformMatrix4fv, "glUniformMatrix4fv");
    bind(ActiveTexture, "glActiveTexture");
    bind(BlendFuncSeparate, "glBlendFuncSeparate");
    bind(BlendEquation, "glBlendEquation");
    bind(GenFramebuffers, "glGenFramebuffers");
    bind(DeleteFramebuffers, "glDeleteFramebuffers");
    bind(BindFramebuffer, "glBindFramebuffer");
    bind(FramebufferTexture2D, "glFramebufferTexture2D");
    bind(CheckFramebufferStatus, "glCheckFramebufferStatus");
    bind(GetFramebufferAttachmentParameteriv, "glGetFramebufferAttachmentParameteriv");
    bind(BlitFramebuffer, "glBlitFramebuffer");
    bind(BindSampler, "glBindSampler");
    bind(GenSamplers, "glGenSamplers");
    bind(DeleteSamplers, "glDeleteSamplers");
    bind(SamplerParameteri, "glSamplerParameteri");

    /* GL 1.3, exported straight from opengl32.dll; the fallback inside
     * bindOptional finds it even on a driver whose ICD hides it. */
    bindOptional(ClientActiveTexture, "glClientActiveTexture");

    if (g_missing > 0) {
        stage.fail(log::format("%d entry points missing", g_missing));
        return false;
    }

    bindOptional(GetStringi, "glGetStringi");
    bindOptional(DispatchCompute, "glDispatchCompute");
    bindOptional(MemoryBarrier_, "glMemoryBarrier");
    bindOptional(BindBufferBase, "glBindBufferBase");
    bindOptional(Uniform1ui, "glUniform1ui");
    bindOptional(VertexAttribDivisor, "glVertexAttribDivisor");
    bindOptional(DrawArraysInstanced, "glDrawArraysInstanced");

    const GLubyte* version = glGetString(GL_VERSION);
    const GLubyte* renderer = glGetString(GL_RENDERER);
    COLD_INFO(kScope, "context %s on %s", version == nullptr ? "?" : reinterpret_cast<const char*>(version),
              renderer == nullptr ? "?" : reinterpret_cast<const char*>(renderer));

    glGetIntegerv(GL_MAJOR_VERSION, &g_caps.major);
    glGetIntegerv(GL_MINOR_VERSION, &g_caps.minor);
    g_caps.instancing = VertexAttribDivisor != nullptr && DrawArraysInstanced != nullptr;

    /* Both halves have to be true. An entry point resolving proves only that
     * the ICD exports the symbol - on Windows opengl32.dll and the driver
     * export the whole of GL regardless of what the current context offers -
     * so the extension string is what actually answers the question. */
    bool advertisesCompute = false;
    bool advertisesStorage = false;
    GLint extensionCount = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &extensionCount);
    if (GetStringi != nullptr) {
        for (GLint index = 0; index < extensionCount; ++index) {
            const GLubyte* name = GetStringi(GL_EXTENSIONS, static_cast<GLuint>(index));
            if (name == nullptr) continue;
            const char* text = reinterpret_cast<const char*>(name);
            if (std::strcmp(text, "GL_ARB_compute_shader") == 0) advertisesCompute = true;
            if (std::strcmp(text, "GL_ARB_shader_storage_buffer_object") == 0)
                advertisesStorage = true;
        }
    }
    const bool version43 = g_caps.major > 4 || (g_caps.major == 4 && g_caps.minor >= 3);
    g_caps.compute = DispatchCompute != nullptr && MemoryBarrier_ != nullptr &&
                     (version43 || advertisesCompute);
    g_caps.storageBuffers = BindBufferBase != nullptr && (version43 || advertisesStorage);
    if (g_caps.compute) {
        glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &g_caps.maxWorkGroupInvocations);
    }
    COLD_INFO(kScope,
              "caps %d.%d  compute=%d ssbo=%d instancing=%d  extensions=%d  workgroup=%d",
              g_caps.major, g_caps.minor, g_caps.compute ? 1 : 0,
              g_caps.storageBuffers ? 1 : 0, g_caps.instancing ? 1 : 0,
              static_cast<int>(extensionCount), g_caps.maxWorkGroupInvocations);

    g_loaded = true;
    return true;
}

PixelTransferGuard::PixelTransferGuard(bool unpack) : unpack_(unpack) {
    const GLenum alignment = unpack ? GL_UNPACK_ALIGNMENT : GL_PACK_ALIGNMENT;
    const GLenum rowLength = unpack ? GL_UNPACK_ROW_LENGTH : GL_PACK_ROW_LENGTH;
    const GLenum skipRows = unpack ? GL_UNPACK_SKIP_ROWS : GL_PACK_SKIP_ROWS;
    const GLenum skipPixels = unpack ? GL_UNPACK_SKIP_PIXELS : GL_PACK_SKIP_PIXELS;
    const GLenum swapBytes = unpack ? GL_UNPACK_SWAP_BYTES : GL_PACK_SWAP_BYTES;
    const GLenum lsbFirst = unpack ? GL_UNPACK_LSB_FIRST : GL_PACK_LSB_FIRST;
    const GLenum target = unpack ? GL_PIXEL_UNPACK_BUFFER : GL_PIXEL_PACK_BUFFER;
    const GLenum binding =
        unpack ? GL_PIXEL_UNPACK_BUFFER_BINDING : GL_PIXEL_PACK_BUFFER_BINDING;

    glGetIntegerv(alignment, &alignment_);
    glGetIntegerv(rowLength, &rowLength_);
    glGetIntegerv(skipRows, &skipRows_);
    glGetIntegerv(skipPixels, &skipPixels_);
    glGetIntegerv(swapBytes, &swapBytes_);
    glGetIntegerv(lsbFirst, &lsbFirst_);
    glGetIntegerv(binding, &bufferBinding_);
    if (unpack) {
        glGetIntegerv(GL_UNPACK_IMAGE_HEIGHT, &imageHeight_);
        glGetIntegerv(GL_UNPACK_SKIP_IMAGES, &skipImages_);
    }

    glPixelStorei(alignment, 1);
    glPixelStorei(rowLength, 0);
    glPixelStorei(skipRows, 0);
    glPixelStorei(skipPixels, 0);
    glPixelStorei(swapBytes, GL_FALSE);
    glPixelStorei(lsbFirst, GL_FALSE);
    if (unpack) {
        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
    }
    if (bufferBinding_ != 0) {
        BindBuffer(target, 0);
    }
}

PixelTransferGuard::~PixelTransferGuard() {
    const GLenum alignment = unpack_ ? GL_UNPACK_ALIGNMENT : GL_PACK_ALIGNMENT;
    const GLenum rowLength = unpack_ ? GL_UNPACK_ROW_LENGTH : GL_PACK_ROW_LENGTH;
    const GLenum skipRows = unpack_ ? GL_UNPACK_SKIP_ROWS : GL_PACK_SKIP_ROWS;
    const GLenum skipPixels = unpack_ ? GL_UNPACK_SKIP_PIXELS : GL_PACK_SKIP_PIXELS;
    const GLenum swapBytes = unpack_ ? GL_UNPACK_SWAP_BYTES : GL_PACK_SWAP_BYTES;
    const GLenum lsbFirst = unpack_ ? GL_UNPACK_LSB_FIRST : GL_PACK_LSB_FIRST;
    const GLenum target = unpack_ ? GL_PIXEL_UNPACK_BUFFER : GL_PIXEL_PACK_BUFFER;

    glPixelStorei(alignment, alignment_);
    glPixelStorei(rowLength, rowLength_);
    glPixelStorei(skipRows, skipRows_);
    glPixelStorei(skipPixels, skipPixels_);
    glPixelStorei(swapBytes, swapBytes_);
    glPixelStorei(lsbFirst, lsbFirst_);
    if (unpack_) {
        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, imageHeight_);
        glPixelStorei(GL_UNPACK_SKIP_IMAGES, skipImages_);
    }
    if (bufferBinding_ != 0) {
        BindBuffer(target, static_cast<GLuint>(bufferBinding_));
    }
}

StateGuard::StateGuard() {
    glGetIntegerv(GL_CURRENT_PROGRAM, &program_);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vertexArray_);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer_);
    glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING, &elementBuffer_);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTexture_);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &framebuffer_);
    glGetIntegerv(GL_VIEWPORT, viewport_);
    glGetIntegerv(GL_SCISSOR_BOX, scissorBox_);
    glGetIntegerv(GL_BLEND_SRC_RGB, &blendSrcRgb_);
    glGetIntegerv(GL_BLEND_DST_RGB, &blendDstRgb_);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &blendSrcAlpha_);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &blendDstAlpha_);
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &blendEquationRgb_);
    glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &blendEquationAlpha_);
    glGetBooleanv(GL_DEPTH_WRITEMASK, &depthWriteMask_);
    glGetBooleanv(GL_COLOR_WRITEMASK, colorWriteMask_);
    glGetIntegerv(GL_MATRIX_MODE, &matrixMode_);
    glGetIntegerv(GL_MODELVIEW_STACK_DEPTH, &modelviewDepth_);
    glGetIntegerv(GL_PROJECTION_STACK_DEPTH, &projectionDepth_);
    glGetIntegerv(GL_TEXTURE_STACK_DEPTH, &textureDepth_);
    alphaTest_ = glIsEnabled(GL_ALPHA_TEST) == GL_TRUE;
    lighting_ = glIsEnabled(GL_LIGHTING) == GL_TRUE;

    for (int unit = 0; unit < 4; ++unit) {
        ActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(unit));
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &textureBinding_[unit]);
        textureEnabled_[unit] = glIsEnabled(GL_TEXTURE_2D);
        if (BindSampler != nullptr) {
            glGetIntegerv(GL_SAMPLER_BINDING, &samplerBinding_[unit]);
        }
    }

    blend_ = glIsEnabled(GL_BLEND) == GL_TRUE;
    depthTest_ = glIsEnabled(GL_DEPTH_TEST) == GL_TRUE;
    scissorTest_ = glIsEnabled(GL_SCISSOR_TEST) == GL_TRUE;
    cullFace_ = glIsEnabled(GL_CULL_FACE) == GL_TRUE;
    stencilTest_ = glIsEnabled(GL_STENCIL_TEST) == GL_TRUE;

    /* The compatibility context has no VAO-backed attribute state and no way
     * to read a matrix back, so both are saved the only way GL 1.1 offers:
     * onto the driver's own stacks. Everything else above is queried so the
     * restore is exact even where the attrib stack is too coarse. */
    glMatrixMode(GL_MODELVIEW);
    glPushMatrix();
    glMatrixMode(GL_PROJECTION);
    glPushMatrix();
    glMatrixMode(GL_TEXTURE);
    glPushMatrix();
    glMatrixMode(matrixMode_);
}

StateGuard::~StateGuard() {
    for (int unit = 3; unit >= 0; --unit) {
        ActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(unit));
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(textureBinding_[unit]));
        if (BindSampler != nullptr) {
            BindSampler(static_cast<GLuint>(unit), static_cast<GLuint>(samplerBinding_[unit]));
        }
        /* The per-unit enable is restored, not cleared: 1.8.9's own font and
         * GUI draws are fixed-function and sample through GL_TEXTURE_2D on the
         * unit the capture left it enabled on. Blinding every unit off here is
         * what turned the game's text white after the first overlay frame. */
        if (textureEnabled_[unit] == GL_TRUE) {
            glEnable(GL_TEXTURE_2D);
        } else {
            glDisable(GL_TEXTURE_2D);
        }
    }
    ActiveTexture(static_cast<GLenum>(activeTexture_));

    if (BindFramebuffer != nullptr) {
        BindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(framebuffer_));
    }
    if (BindVertexArray != nullptr) {
        BindVertexArray(static_cast<GLuint>(vertexArray_));
    }
    if (BindBuffer != nullptr) {
        BindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(arrayBuffer_));
        BindBuffer(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLuint>(elementBuffer_));
    }
    if (UseProgram != nullptr) {
        UseProgram(static_cast<GLuint>(program_));
    }

    glViewport(viewport_[0], viewport_[1], viewport_[2], viewport_[3]);
    glScissor(scissorBox_[0], scissorBox_[1], scissorBox_[2], scissorBox_[3]);
    if (BlendFuncSeparate != nullptr) {
        BlendFuncSeparate(static_cast<GLenum>(blendSrcRgb_), static_cast<GLenum>(blendDstRgb_),
                          static_cast<GLenum>(blendSrcAlpha_), static_cast<GLenum>(blendDstAlpha_));
    } else {
        glBlendFunc(static_cast<GLenum>(blendSrcRgb_), static_cast<GLenum>(blendDstRgb_));
    }
    if (BlendEquation != nullptr && blendEquationRgb_ == blendEquationAlpha_) {
        BlendEquation(static_cast<GLenum>(blendEquationRgb_));
    }
    glDepthMask(depthWriteMask_);
    glColorMask(colorWriteMask_[0], colorWriteMask_[1], colorWriteMask_[2], colorWriteMask_[3]);

    /* Matrix and client arrays come off the driver stacks first, then the
     * per-enable restores below land on top. */
    glMatrixMode(GL_TEXTURE);
    glPopMatrix();
    glMatrixMode(GL_PROJECTION);
    glPopMatrix();
    glMatrixMode(GL_MODELVIEW);
    glPopMatrix();
    glMatrixMode(matrixMode_);

    const auto restore = [](GLenum capability, bool enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    };
    restore(GL_BLEND, blend_);
    restore(GL_DEPTH_TEST, depthTest_);
    restore(GL_SCISSOR_TEST, scissorTest_);
    restore(GL_CULL_FACE, cullFace_);
    restore(GL_STENCIL_TEST, stencilTest_);
    restore(GL_ALPHA_TEST, alphaTest_);
    restore(GL_LIGHTING, lighting_);
}

void resetFixedFunctionState() {
    if (UseProgram != nullptr) {
        UseProgram(0);
    }
    if (BindFramebuffer != nullptr) {
        BindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    if (BindVertexArray != nullptr) {
        BindVertexArray(0);
    }
    if (BindBuffer != nullptr) {
        BindBuffer(GL_ARRAY_BUFFER, 0);
        BindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }
    if (BindSampler != nullptr) {
        for (int unit = 3; unit >= 0; --unit) {
            ActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(unit));
            BindSampler(GLuint(unit), 0);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    } else {
        ActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, 0);
        ActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, 0);
        ActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, 0);
        ActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    ActiveTexture(GL_TEXTURE0);

    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glDepthMask(GL_TRUE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBlendFunc(GL_ONE, GL_ZERO);

    /* The compatibility context carries fixed-function state Minecraft's own
     * draws rely on; park it in the identity pose the game starts a frame in. */
    glMatrixMode(GL_TEXTURE);
    glLoadIdentity();
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();

    if (ClientActiveTexture != nullptr) {
        for (GLenum unit = 0; unit < 4; ++unit) {
            ClientActiveTexture(GL_TEXTURE0 + unit);
            DisableClientState(kVertexArrayClientState);
            DisableClientState(kColorArrayClientState);
            DisableClientState(kTextureCoordArrayClientState);
        }
        ClientActiveTexture(GL_TEXTURE0);
    }
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_ALPHA_TEST);
    glDisable(GL_LIGHTING);
    glMatrixMode(GL_MODELVIEW);
}

void checkError(const char* stage) {
    GLenum error = glGetError();
    int guard = 0;
    while (error != GL_NO_ERROR && guard++ < 8) {
        COLD_ERROR(kScope, "GL error 0x%04X during %s", error, stage);
        error = glGetError();
    }
}

}  // namespace cold::gl
