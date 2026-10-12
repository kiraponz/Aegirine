package com.kira3.scphx.emucore

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * The system GL driver's identity strings, read from a throwaway offscreen GLES2 context.
 * The core's "Auto" renderer decides between OpenGL and Vulkan from these (Adreno and the
 * Mali generations that run Vulkan well go to Vulkan), so they are handed over before a
 * game starts. GL_VERSION carries the driver revision, which the core's driver-bug
 * database matches on.
 */
internal data class GlStrings(val vendor: String?, val renderer: String?, val version: String?) {
    companion object {
        /** Null if no GL context could be created. */
        fun probe(): GlStrings? {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            try {
                val configAttributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
                val context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
                )
                if (context == EGL14.EGL_NO_CONTEXT) return null
                val surface = EGL14.eglCreatePbufferSurface(
                    display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
                )
                try {
                    if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
                    return GlStrings(
                        vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                        renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                        version = GLES20.glGetString(GLES20.GL_VERSION),
                    )
                } finally {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                }
            } finally {
                EGL14.eglTerminate(display)
            }
        }
    }
}
