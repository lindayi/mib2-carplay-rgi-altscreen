#ifndef HOSTCHECK_GLES2_H
#define HOSTCHECK_GLES2_H
typedef unsigned char GLubyte;
typedef unsigned int  GLenum;
typedef unsigned int  GLuint;
typedef int           GLint;
typedef int           GLsizei;

#define GL_NO_ERROR   0
#define GL_VENDOR     0x1F00
#define GL_RENDERER   0x1F01
#define GL_VERSION    0x1F02
#define GL_EXTENSIONS 0x1F03
#define GL_LINK_STATUS 0x8B82

const GLubyte *glGetString(GLenum name);
GLenum glGetError(void);
void glGetProgramiv(GLuint program, GLenum pname, GLint *value);
void glFinish(void);
#endif
