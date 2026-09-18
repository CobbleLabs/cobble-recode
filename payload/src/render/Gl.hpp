#pragma once

#include <windows.h>

#include <cstddef>
#include <cstdint>

/* A hand-rolled OpenGL 3.3 core loader.
 *
 * Cobble Recode draws inside Minecraft's context, so it only needs the handful of
 * entry points the overlay actually calls. Pulling in a generated loader would
 * add a few thousand unused symbols to a DLL that wants to stay small and fast
 * to inject. */

using GLenum = unsigned int;
using GLboolean = unsigned char;
using GLbitfield = unsigned int;
using GLbyte = signed char;
using GLshort = short;
using GLint = int;
using GLsizei = int;
using GLubyte = unsigned char;
using GLushort = unsigned short;
using GLuint = unsigned int;
using GLfloat = float;
using GLclampf = float;
using GLdouble = double;
using GLchar = char;
using GLintptr = std::intptr_t;
using GLsizeiptr = std::intptr_t;

#define GL_FALSE 0
#define GL_TRUE 1
#define GL_ZERO 0
#define GL_ONE 1
/* ---- fixed-function state the legacy (Lunar 1.8.9) context still runs ---- */
#define GL_MATRIX_MODE 0x0BA0
#define GL_MODELVIEW 0x1700
#define GL_PROJECTION 0x1701
#define GL_TEXTURE 0x1702
#define GL_MODELVIEW_STACK_DEPTH 0x0BA3
#define GL_PROJECTION_STACK_DEPTH 0x0BA4
#define GL_TEXTURE_STACK_DEPTH 0x0BA5
#define GL_MAX_MODELVIEW_STACK_DEPTH 0x0D36
#define GL_CLIENT_ACTIVE_TEXTURE 0x84E1
#define GL_VERTEX_ARRAY 0x8074
#define GL_NORMAL_ARRAY 0x8075
#define GL_COLOR_ARRAY 0x8076
#define GL_TEXTURE_COORD_ARRAY 0x8078
#define GL_LIGHTING 0x0B50
#define GL_ALPHA_TEST 0x0BC0
#define GL_FOG 0x0B60
#define GL_TRIANGLES 0x0004
#define GL_TRIANGLE_STRIP 0x0005
#define GL_SRC_ALPHA 0x0302
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
#define GL_DST_ALPHA 0x0304
#define GL_ONE_MINUS_DST_ALPHA 0x0305
#define GL_DEPTH_TEST 0x0B71
#define GL_BLEND 0x0BE2
#define GL_SCISSOR_TEST 0x0C11
#define GL_CULL_FACE 0x0B44
#define GL_STENCIL_TEST 0x0B90
#define GL_TEXTURE_2D 0x0DE1
#define GL_EXTENSIONS 0x1F03
#define GL_MAJOR_VERSION 0x821B
#define GL_MINOR_VERSION 0x821C
#define GL_NUM_EXTENSIONS 0x821D
#define GL_COMPUTE_SHADER 0x91B9
#define GL_SHADER_STORAGE_BUFFER 0x90D2
#define GL_SHADER_STORAGE_BARRIER_BIT 0x00002000
#define GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT 0x00000001
#define GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS 0x90EB
#define GL_UNPACK_SWAP_BYTES 0x0CF0
#define GL_UNPACK_LSB_FIRST 0x0CF1
#define GL_UNPACK_ROW_LENGTH 0x0CF2
#define GL_UNPACK_SKIP_ROWS 0x0CF3
#define GL_UNPACK_SKIP_PIXELS 0x0CF4
#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_UNPACK_IMAGE_HEIGHT 0x806E
#define GL_UNPACK_SKIP_IMAGES 0x806D
#define GL_PACK_SWAP_BYTES 0x0D00
#define GL_PACK_LSB_FIRST 0x0D01
#define GL_PACK_ROW_LENGTH 0x0D02
#define GL_PACK_SKIP_ROWS 0x0D03
#define GL_PACK_SKIP_PIXELS 0x0D04
#define GL_PACK_ALIGNMENT 0x0D05
#define GL_PIXEL_PACK_BUFFER 0x88EB
#define GL_PIXEL_UNPACK_BUFFER 0x88EC
#define GL_PIXEL_PACK_BUFFER_BINDING 0x88ED
#define GL_PIXEL_UNPACK_BUFFER_BINDING 0x88EF
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_NEAREST 0x2600
#define GL_LINEAR 0x2601
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_RED 0x1903
#define GL_RGB 0x1907
#define GL_RGBA 0x1908
#define GL_RGBA8 0x8058
#define GL_R8 0x8229
#define GL_UNSIGNED_BYTE 0x1401
#define GL_FLOAT 0x1406
#define GL_UNSIGNED_INT 0x1405
#define GL_COLOR_BUFFER_BIT 0x00004000
#define GL_DEPTH_BUFFER_BIT 0x00000100
#define GL_ARRAY_BUFFER 0x8892
#define GL_ELEMENT_ARRAY_BUFFER 0x8893
#define GL_STREAM_DRAW 0x88E0
#define GL_STATIC_DRAW 0x88E4
#define GL_DYNAMIC_DRAW 0x88E8
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_VERTEX_SHADER 0x8B31
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_INFO_LOG_LENGTH 0x8B84
#define GL_TEXTURE0 0x84C0
#define GL_TEXTURE1 0x84C1
#define GL_TEXTURE2 0x84C2
#define GL_TEXTURE3 0x84C3
#define GL_ACTIVE_TEXTURE 0x84E0
#define GL_TEXTURE_BINDING_2D 0x8069
#define GL_TEXTURE_WIDTH 0x1000
#define GL_TEXTURE_HEIGHT 0x1001
#define GL_ARRAY_BUFFER_BINDING 0x8894
#define GL_ELEMENT_ARRAY_BUFFER_BINDING 0x8895
#define GL_VERTEX_ARRAY_BINDING 0x85B5
#define GL_CURRENT_PROGRAM 0x8B8D
#define GL_FRAMEBUFFER 0x8D40
#define GL_READ_FRAMEBUFFER 0x8CA8
#define GL_DRAW_FRAMEBUFFER 0x8CA9
#define GL_FRAMEBUFFER_BINDING 0x8CA6
#define GL_READ_FRAMEBUFFER_BINDING 0x8CAA
#define GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_DEPTH_ATTACHMENT 0x8D00
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME 0x8CD1
#define GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE 0x8CD0
#define GL_VIEWPORT 0x0BA2
#define GL_SCISSOR_BOX 0x0C10
#define GL_BLEND_SRC_RGB 0x80C9
#define GL_BLEND_DST_RGB 0x80C8
#define GL_BLEND_SRC_ALPHA 0x80CB
#define GL_BLEND_DST_ALPHA 0x80CA
#define GL_BLEND_EQUATION_RGB 0x8009
#define GL_BLEND_EQUATION_ALPHA 0x883D
#define GL_FUNC_ADD 0x8006
#define GL_DEPTH_WRITEMASK 0x0B72
#define GL_COLOR_WRITEMASK 0x0C23
#define GL_SAMPLER_BINDING 0x8919
#define GL_NO_ERROR 0
#define GL_VERSION 0x1F02
#define GL_RENDERER 0x1F01

namespace cold::gl {

using PFNGLGENBUFFERS = void(APIENTRY*)(GLsizei, GLuint*);
using PFNGLDELETEBUFFERS = void(APIENTRY*)(GLsizei, const GLuint*);
using PFNGLBINDBUFFER = void(APIENTRY*)(GLenum, GLuint);
using PFNGLBUFFERDATA = void(APIENTRY*)(GLenum, GLsizeiptr, const void*, GLenum);
using PFNGLBUFFERSUBDATA = void(APIENTRY*)(GLenum, GLintptr, GLsizeiptr, const void*);
using PFNGLGENVERTEXARRAYS = void(APIENTRY*)(GLsizei, GLuint*);
using PFNGLDELETEVERTEXARRAYS = void(APIENTRY*)(GLsizei, const GLuint*);
using PFNGLBINDVERTEXARRAY = void(APIENTRY*)(GLuint);
using PFNGLENABLEVERTEXATTRIBARRAY = void(APIENTRY*)(GLuint);
using PFNGLVERTEXATTRIBPOINTER = void(APIENTRY*)(GLuint, GLint, GLenum, GLboolean, GLsizei,
                                                 const void*);
using PFNGLCREATESHADER = GLuint(APIENTRY*)(GLenum);
using PFNGLDELETESHADER = void(APIENTRY*)(GLuint);
using PFNGLSHADERSOURCE = void(APIENTRY*)(GLuint, GLsizei, const GLchar* const*, const GLint*);
using PFNGLCOMPILESHADER = void(APIENTRY*)(GLuint);
using PFNGLGETSHADERIV = void(APIENTRY*)(GLuint, GLenum, GLint*);
using PFNGLGETSHADERINFOLOG = void(APIENTRY*)(GLuint, GLsizei, GLsizei*, GLchar*);
using PFNGLCREATEPROGRAM = GLuint(APIENTRY*)();
using PFNGLDELETEPROGRAM = void(APIENTRY*)(GLuint);
using PFNGLATTACHSHADER = void(APIENTRY*)(GLuint, GLuint);
using PFNGLLINKPROGRAM = void(APIENTRY*)(GLuint);
using PFNGLGETPROGRAMIV = void(APIENTRY*)(GLuint, GLenum, GLint*);
using PFNGLGETPROGRAMINFOLOG = void(APIENTRY*)(GLuint, GLsizei, GLsizei*, GLchar*);
using PFNGLUSEPROGRAM = void(APIENTRY*)(GLuint);
using PFNGLGETUNIFORMLOCATION = GLint(APIENTRY*)(GLuint, const GLchar*);
using PFNGLUNIFORM1I = void(APIENTRY*)(GLint, GLint);
using PFNGLUNIFORM1F = void(APIENTRY*)(GLint, GLfloat);
using PFNGLUNIFORM2F = void(APIENTRY*)(GLint, GLfloat, GLfloat);
using PFNGLUNIFORM3F = void(APIENTRY*)(GLint, GLfloat, GLfloat, GLfloat);
using PFNGLUNIFORM4F = void(APIENTRY*)(GLint, GLfloat, GLfloat, GLfloat, GLfloat);
using PFNGLUNIFORMMATRIX4FV = void(APIENTRY*)(GLint, GLsizei, GLboolean, const GLfloat*);
using PFNGLACTIVETEXTURE = void(APIENTRY*)(GLenum);
using PFNGLBLENDFUNCSEPARATE = void(APIENTRY*)(GLenum, GLenum, GLenum, GLenum);
using PFNGLBLENDEQUATION = void(APIENTRY*)(GLenum);
using PFNGLGENFRAMEBUFFERS = void(APIENTRY*)(GLsizei, GLuint*);
using PFNGLDELETEFRAMEBUFFERS = void(APIENTRY*)(GLsizei, const GLuint*);
using PFNGLBINDFRAMEBUFFER = void(APIENTRY*)(GLenum, GLuint);
using PFNGLFRAMEBUFFERTEXTURE2D = void(APIENTRY*)(GLenum, GLenum, GLenum, GLuint, GLint);
using PFNGLCHECKFRAMEBUFFERSTATUS = GLenum(APIENTRY*)(GLenum);
using PFNGLGETFRAMEBUFFERATTACHMENTPARAMETERIV = void(APIENTRY*)(GLenum, GLenum, GLenum, GLint*);
using PFNGLBLITFRAMEBUFFER = void(APIENTRY*)(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint,
                                             GLbitfield, GLenum);
using PFNGLBINDSAMPLER = void(APIENTRY*)(GLuint, GLuint);
using PFNGLGENSAMPLERS = void(APIENTRY*)(GLsizei, GLuint*);
using PFNGLDELETESAMPLERS = void(APIENTRY*)(GLsizei, const GLuint*);
using PFNGLSAMPLERPARAMETERI = void(APIENTRY*)(GLuint, GLenum, GLint);
using PFNGLGETSTRINGI = const GLubyte*(APIENTRY*)(GLenum, GLuint);
using PFNGLDISPATCHCOMPUTE = void(APIENTRY*)(GLuint, GLuint, GLuint);
using PFNGLMEMORYBARRIER = void(APIENTRY*)(GLbitfield);
using PFNGLBINDBUFFERBASE = void(APIENTRY*)(GLenum, GLuint, GLuint);
using PFNGLUNIFORM1UI = void(APIENTRY*)(GLint, GLuint);
using PFNGLVERTEXATTRIBDIVISOR = void(APIENTRY*)(GLuint, GLuint);
using PFNGLDRAWARRAYSINSTANCED = void(APIENTRY*)(GLenum, GLint, GLsizei, GLsizei);
using PFNGLDRAWELEMENTSBASEVERTEX = void(APIENTRY*)(GLenum, GLsizei, GLenum, const void*, GLint);

/* Fixed-function entry points. Present on every desktop GL implementation,
 * resolved from opengl32.dll directly - they are declared in the system
 * headers, but Lunar's context makes them live state so the overlay manages
 * them explicitly rather than assuming they are inert. */
extern "C" {
WINGDIAPI void APIENTRY glMatrixMode(GLenum);
WINGDIAPI void APIENTRY glLoadIdentity(void);
WINGDIAPI void APIENTRY glOrtho(GLdouble, GLdouble, GLdouble, GLdouble, GLdouble, GLdouble);
WINGDIAPI void APIENTRY glPushAttrib(GLbitfield);
WINGDIAPI void APIENTRY glPopAttrib(void);
WINGDIAPI void APIENTRY glPushClientAttrib(GLbitfield);
WINGDIAPI void APIENTRY glPopClientAttrib(void);
WINGDIAPI void APIENTRY glClientActiveTexture(GLenum);
WINGDIAPI void APIENTRY glDisableClientState(GLenum);
WINGDIAPI void APIENTRY glEnableClientState(GLenum);
WINGDIAPI void APIENTRY glMultiTexCoord2f(GLenum, GLfloat, GLfloat);
WINGDIAPI void APIENTRY glPushMatrix(void);
WINGDIAPI void APIENTRY glPopMatrix(void);
WINGDIAPI void APIENTRY glDisableClientState(GLenum);
}

using PFNGLCLIENTACTIVETEXTURE = void(APIENTRY*)(GLenum);
extern PFNGLCLIENTACTIVETEXTURE ClientActiveTexture;

inline void MatrixMode(GLenum mode) { glMatrixMode(mode); }
inline void LoadIdentity() { glLoadIdentity(); }
inline void PushMatrix() { glPushMatrix(); }
inline void PopMatrix() { glPopMatrix(); }
inline void Ortho(GLdouble l, GLdouble r, GLdouble b, GLdouble t, GLdouble n, GLdouble f) {
    glOrtho(l, r, b, t, n, f);
}
inline void PopAttrib() { glPopAttrib(); }
inline void PopClientAttrib() { glPopClientAttrib(); }
inline void DisableClientState(GLenum array) { glDisableClientState(array); }
inline void EnableClientState(GLenum array) { glEnableClientState(array); }

/* Modern entry points, resolved through wglGetProcAddress. */
extern PFNGLGENBUFFERS GenBuffers;
extern PFNGLDELETEBUFFERS DeleteBuffers;
extern PFNGLBINDBUFFER BindBuffer;
extern PFNGLBUFFERDATA BufferData;
extern PFNGLBUFFERSUBDATA BufferSubData;
extern PFNGLGENVERTEXARRAYS GenVertexArrays;
extern PFNGLDELETEVERTEXARRAYS DeleteVertexArrays;
extern PFNGLBINDVERTEXARRAY BindVertexArray;
extern PFNGLENABLEVERTEXATTRIBARRAY EnableVertexAttribArray;
extern PFNGLVERTEXATTRIBPOINTER VertexAttribPointer;
extern PFNGLCREATESHADER CreateShader;
extern PFNGLDELETESHADER DeleteShader;
extern PFNGLSHADERSOURCE ShaderSource;
extern PFNGLCOMPILESHADER CompileShader;
extern PFNGLGETSHADERIV GetShaderiv;
extern PFNGLGETSHADERINFOLOG GetShaderInfoLog;
extern PFNGLCREATEPROGRAM CreateProgram;
extern PFNGLDELETEPROGRAM DeleteProgram;
extern PFNGLATTACHSHADER AttachShader;
extern PFNGLLINKPROGRAM LinkProgram;
extern PFNGLGETPROGRAMIV GetProgramiv;
extern PFNGLGETPROGRAMINFOLOG GetProgramInfoLog;
extern PFNGLUSEPROGRAM UseProgram;
extern PFNGLGETUNIFORMLOCATION GetUniformLocation;
extern PFNGLUNIFORM1I Uniform1i;
extern PFNGLUNIFORM1F Uniform1f;
extern PFNGLUNIFORM2F Uniform2f;
extern PFNGLUNIFORM3F Uniform3f;
extern PFNGLUNIFORM4F Uniform4f;
extern PFNGLUNIFORMMATRIX4FV UniformMatrix4fv;
extern PFNGLACTIVETEXTURE ActiveTexture;
extern PFNGLBLENDFUNCSEPARATE BlendFuncSeparate;
extern PFNGLBLENDEQUATION BlendEquation;
extern PFNGLGENFRAMEBUFFERS GenFramebuffers;
extern PFNGLDELETEFRAMEBUFFERS DeleteFramebuffers;
extern PFNGLBINDFRAMEBUFFER BindFramebuffer;
extern PFNGLFRAMEBUFFERTEXTURE2D FramebufferTexture2D;
extern PFNGLCHECKFRAMEBUFFERSTATUS CheckFramebufferStatus;
extern PFNGLGETFRAMEBUFFERATTACHMENTPARAMETERIV GetFramebufferAttachmentParameteriv;
extern PFNGLBLITFRAMEBUFFER BlitFramebuffer;
extern PFNGLBINDSAMPLER BindSampler;
extern PFNGLGENSAMPLERS GenSamplers;
extern PFNGLDELETESAMPLERS DeleteSamplers;
extern PFNGLSAMPLERPARAMETERI SamplerParameteri;

/* Resolved only when the context actually offers them; see Capabilities. */
extern PFNGLGETSTRINGI GetStringi;
extern PFNGLDISPATCHCOMPUTE DispatchCompute;
extern PFNGLMEMORYBARRIER MemoryBarrier_;
extern PFNGLBINDBUFFERBASE BindBufferBase;
extern PFNGLUNIFORM1UI Uniform1ui;
extern PFNGLVERTEXATTRIBDIVISOR VertexAttribDivisor;
extern PFNGLDRAWARRAYSINSTANCED DrawArraysInstanced;

/* What the context this payload was injected into can actually do.
 *
 * The overlay does not create a context, it borrows the one Minecraft made,
 * and Minecraft asks GLFW for a 3.3 core profile. A driver honours that
 * request literally: everything above 3.3 is absent from a 3.3 core context
 * whatever the card is capable of, compute shaders included. So the answer to
 * "can we dispatch a compute shader" is a property of the host process rather
 * than of the machine, it has to be asked at runtime, and the renderer has to
 * have a path for the answer being no. */
struct Capabilities {
    int major = 0;
    int minor = 0;
    /* Everything a GPU-side vertex build needs: dispatch, an SSBO to write
     * into, and a barrier before the draw reads it. */
    bool compute = false;
    bool storageBuffers = false;
    int maxWorkGroupInvocations = 0;
    /* GL 3.3 has no compute but does have instanced attribute divisors, which
     * is the other way to hand per-primitive expansion to the GPU. */
    bool instancing = false;
};

const Capabilities& capabilities();

/* Resolves every extern above. Must run on the thread owning the GL context. */
bool load();
bool loaded();

}  // namespace cold::gl

/* OpenGL 1.1 entry points come straight from opengl32.dll and stay at global
 * scope, the way <GL/gl.h> would declare them. */
extern "C" {
WINGDIAPI void APIENTRY glEnable(GLenum);
WINGDIAPI void APIENTRY glDisable(GLenum);
WINGDIAPI void APIENTRY glBlendFunc(GLenum, GLenum);
WINGDIAPI void APIENTRY glViewport(GLint, GLint, GLsizei, GLsizei);
WINGDIAPI void APIENTRY glScissor(GLint, GLint, GLsizei, GLsizei);
WINGDIAPI void APIENTRY glGetIntegerv(GLenum, GLint*);
WINGDIAPI void APIENTRY glGetBooleanv(GLenum, GLboolean*);
WINGDIAPI void APIENTRY glGetFloatv(GLenum, GLfloat*);
WINGDIAPI GLboolean APIENTRY glIsEnabled(GLenum);
WINGDIAPI GLboolean APIENTRY glIsTexture(GLuint);
WINGDIAPI void APIENTRY glGenTextures(GLsizei, GLuint*);
WINGDIAPI void APIENTRY glDeleteTextures(GLsizei, const GLuint*);
WINGDIAPI void APIENTRY glBindTexture(GLenum, GLuint);
WINGDIAPI void APIENTRY glTexImage2D(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum,
                                     const void*);
WINGDIAPI void APIENTRY glTexSubImage2D(GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum,
                                        GLenum, const void*);
WINGDIAPI void APIENTRY glTexParameteri(GLenum, GLenum, GLint);
WINGDIAPI void APIENTRY glPixelStorei(GLenum, GLint);
WINGDIAPI void APIENTRY glDrawArrays(GLenum, GLint, GLsizei);
WINGDIAPI void APIENTRY glDrawElements(GLenum, GLsizei, GLenum, const void*);
WINGDIAPI void APIENTRY glReadPixels(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void*);
WINGDIAPI void APIENTRY glGetTexImage(GLenum, GLint, GLenum, GLenum, void*);
WINGDIAPI void APIENTRY glGetTexLevelParameteriv(GLenum, GLint, GLenum, GLint*);
WINGDIAPI void APIENTRY glClear(GLbitfield);
WINGDIAPI void APIENTRY glClearColor(GLclampf, GLclampf, GLclampf, GLclampf);
WINGDIAPI void APIENTRY glDepthMask(GLboolean);
WINGDIAPI void APIENTRY glColorMask(GLboolean, GLboolean, GLboolean, GLboolean);
WINGDIAPI GLenum APIENTRY glGetError();
WINGDIAPI const GLubyte* APIENTRY glGetString(GLenum);
}

namespace cold::gl {

inline constexpr GLenum kModelview = 0x1700;
inline constexpr GLenum kProjection = 0x1701;
inline constexpr GLenum kTexture = 0x1702;
inline constexpr GLenum kColor = 0x1800;
inline constexpr GLenum kVertexArrayClientState = 0x8074;
inline constexpr GLenum kColorArrayClientState = 0x8076;
inline constexpr GLenum kTextureCoordArrayClientState = 0x8078;
inline constexpr GLbitfield kAttribAllBits = 0x000FFFFF;
inline constexpr GLbitfield kClientAttribAllBits = 0x00010001;

/* Snapshot of everything the overlay touches, so Minecraft's cached
 * GlStateManager view of the driver stays true after the client draws. */
struct StateGuard {
    StateGuard();
    ~StateGuard();

    StateGuard(const StateGuard&) = delete;
    StateGuard& operator=(const StateGuard&) = delete;

private:
    GLint program_ = 0;
    GLint vertexArray_ = 0;
    GLint arrayBuffer_ = 0;
    GLint elementBuffer_ = 0;
    GLint activeTexture_ = GL_TEXTURE0;
    GLint textureBinding_[4] = {0, 0, 0, 0};
    GLint samplerBinding_[4] = {0, 0, 0, 0};
    /* Fixed-function per-unit enables. Lunar 1.8.9's own font and GUI rendering
     * is fixed-function and samples only with GL_TEXTURE_2D enabled on the
     * unit; restoring bindings but not enables turns every glyph white. */
    GLboolean textureEnabled_[4] = {GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE};
    GLint framebuffer_ = 0;
    GLint viewport_[4] = {0, 0, 0, 0};
    GLint scissorBox_[4] = {0, 0, 0, 0};
    GLint blendSrcRgb_ = GL_ONE;
    GLint blendDstRgb_ = GL_ZERO;
    GLint blendSrcAlpha_ = GL_ONE;
    GLint blendDstAlpha_ = GL_ZERO;
    GLint blendEquationRgb_ = GL_FUNC_ADD;
    GLint blendEquationAlpha_ = GL_FUNC_ADD;
    GLboolean depthWriteMask_ = GL_TRUE;
    GLboolean colorWriteMask_[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
    bool blend_ = false;
    bool depthTest_ = false;
    bool scissorTest_ = false;
    bool cullFace_ = false;
    bool stencilTest_ = false;
    bool alphaTest_ = false;
    bool lighting_ = false;
    /* Fixed-function matrix stacks. A compatibility context never has VAOs
     * doing this job, so the matrices themselves are the state that has to
     * survive the overlay; OpenGL 1.1 offers no query for them, which is why
     * they are pushed onto the driver's own stack rather than read back. */
    GLint modelviewDepth_ = 0;
    GLint projectionDepth_ = 0;
    GLint textureDepth_ = 0;
    GLint matrixMode_ = GL_MODELVIEW;
};

/* Puts the fixed-function and shader state back to what a fresh Minecraft
 * frame expects, for use after a mid-draw fault: our program, our textures and
 * our matrices are un-bound, every capability we enable is disabled and the
 * matrix stacks we pushed are popped. Not a substitute for StateGuard's exact
 * restore - this is the coarse "stop the overlay haunting the game" sweep. */
void resetFixedFunctionState();

/* Neutralises the pixel transfer state around a texture upload or read-back.
 *
 * Minecraft's own uploads leave GL_UNPACK_ROW_LENGTH, the skip offsets and — on
 * the 1.21.9+ GpuDevice path — a pixel unpack buffer binding in place. With a
 * PBO bound, the pointer handed to glTexImage2D is reinterpreted as an offset
 * into that buffer, so the texture silently fills with unrelated memory. Every
 * transfer this client makes goes through one of these. */
struct PixelTransferGuard {
    explicit PixelTransferGuard(bool unpack);
    ~PixelTransferGuard();

    PixelTransferGuard(const PixelTransferGuard&) = delete;
    PixelTransferGuard& operator=(const PixelTransferGuard&) = delete;

private:
    bool unpack_;
    GLint alignment_ = 4;
    GLint rowLength_ = 0;
    GLint skipRows_ = 0;
    GLint skipPixels_ = 0;
    GLint imageHeight_ = 0;
    GLint skipImages_ = 0;
    GLint swapBytes_ = GL_FALSE;
    GLint lsbFirst_ = GL_FALSE;
    GLint bufferBinding_ = 0;
};

/* Logs any pending GL error with a caller tag. Compiled out of hot paths but
 * kept around every setup step, where a silent failure is expensive to find. */
void checkError(const char* stage);

}  // namespace cold::gl
